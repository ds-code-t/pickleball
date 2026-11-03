package tools.dscode.launcher;

import io.cucumber.cucumberexpressions.Expression;
import io.cucumber.cucumberexpressions.ExpressionFactory;
import io.cucumber.cucumberexpressions.ParameterTypeRegistry;
import tools.dscode.control.protocol.WorkbenchFeatureIndex;
import tools.dscode.control.protocol.Workspace;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Dry-run over the feature index and the same step expressions the runner
 * uses. It does not start a browser, Maven, or a worker. Undefined and
 * ambiguous steps and unresolved {@code RUN SCENARIO} targets are one line
 * each. Outline rows are expanded before that match. A long suite prints
 * progress and stops at {@link #STEP_CAP}.
 */
public final class WorkbenchValidate {
    static final int STEP_CAP = 2000;
    private static final int PROGRESS_EVERY = 100;
    private static final String CORE_PACKAGE = "tools.dscode.coredefinitions";
    private static final ExpressionFactory EXPRESSIONS = new ExpressionFactory(
            new ParameterTypeRegistry(Locale.ENGLISH)
    );
    private static final ConcurrentHashMap<String, Optional<Expression>> COMPILED = new ConcurrentHashMap<>();
    private static volatile List<String> corePatterns;
    private static final Pattern STEP = Pattern.compile(
            "^\\s*(?::+\\s*)?(?:Given|When|Then|And|But|\\*)\\s+(.*)$"
    );
    /** Head of a step annotation, including an optional {@code value =}. The pattern itself is read as Java string literals. */
    private static final Pattern ANNOTATION_HEAD = Pattern.compile(
            "@(?:Given|When|Then|And|But)\\s*\\(\\s*(?:value\\s*=\\s*)?"
    );
    private static final Pattern RUN_TARGET = Pattern.compile(
            "^RUN SCENARIOS?\\s*:\\s*(.+)$"
    );

    private WorkbenchValidate() {
    }

    public record Finding(String kind, String where, String text) {
    }

    public static int run(Path project, String tags, String name, PrintStream out, PrintStream err) {
        return run(project, tags, name, null, null, false, out, err);
    }

    /**
     * @param runId when set, glue comes only from that workspace's {@code pkb_glue}
     *              (blank means no consumer patterns; core steps still match)
     * @param maxSteps step budget; null keeps {@link #STEP_CAP}
     * @param allSteps when true, there is no step cap ({@code --all} wins over {@code maxSteps})
     */
    public static int run(
            Path project,
            String tags,
            String name,
            String runId,
            Integer maxSteps,
            boolean allSteps,
            PrintStream out,
            PrintStream err
    ) {
        if (project == null || !Files.isDirectory(project)) {
            if (err != null) err.print(WorkbenchCliHelp.usageError("missing project", "validate"));
            return 2;
        }
        long started = System.nanoTime();
        if (out != null) out.println("validate progress stage=glue");
        List<String> glue = glueFor(project, runId, out);
        WorkbenchFeatureIndex.Selection all = WorkbenchFeatureIndex.select(new WorkbenchFeatureIndex.Request(
                project, List.of(), null, null, null, List.of(), false
        ));
        boolean filtered = (tags != null && !tags.isBlank()) || (name != null && !name.isBlank());
        WorkbenchFeatureIndex.Selection selection = filtered
                ? WorkbenchFeatureIndex.select(new WorkbenchFeatureIndex.Request(
                        project, List.of(), tags, name, null, List.of(), false))
                : all;
        List<Finding> findings = new ArrayList<>();
        int limit = allSteps ? Integer.MAX_VALUE : (maxSteps != null && maxSteps > 0 ? maxSteps : STEP_CAP);
        Budget budget = new Budget(limit);
        if (out != null) progress(out, budget);
        for (WorkbenchFeatureIndex.Hit hit : selection.hits()) {
            if (budget.capped) break;
            Path file = project.resolve(hit.relativePath().replace('/', java.io.File.separatorChar));
            addFindings(findings, scan(
                    project, file, hit.startLine(), hit.endLine(), glue, all,
                    hit.headers(), hit.values(), budget, out
            ));
            budget.scenarios++;
        }
        if (selection.hits().isEmpty() && selection.fullScanFallback() && !budget.capped) {
            addFindings(findings, scanTree(project, glue, all, budget, out));
        }
        if (out != null) progress(out, budget);
        long elapsed = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        int undefined = count(findings, "undefined");
        int ambiguous = count(findings, "ambiguous");
        int unresolved = count(findings, "unresolved");
        int skipped = Math.max(0, selection.hits().size() - budget.scenarios);
        if (out != null) {
            if (budget.capped) {
                out.println("fix: narrow validate with --tags or --name, or pass --max-steps / --all");
            }
            for (Finding finding : findings) {
                out.println(finding.kind() + ": " + finding.where() + " " + finding.text());
            }
            out.println("validate undefined=" + undefined
                    + " ambiguous=" + ambiguous
                    + " unresolved=" + unresolved
                    + " elapsedMs=" + elapsed
                    + " capped=" + budget.capped
                    + " skipped=" + skipped);
        }
        return undefined + ambiguous + unresolved == 0 ? 0 : 1;
    }

    private static List<String> glueFor(Path project, String runId, PrintStream out) {
        if (runId == null || runId.isBlank()) return gluePatterns(project);
        Path workspace = Workspace.directory(project, runId.trim()).toAbsolutePath().normalize();
        if (out != null) out.println("workspace=" + workspace);
        String packages = "";
        if (Workspace.exists(workspace)) {
            String stored = Workspace.readRunVars(workspace).get("pkb_glue");
            packages = stored == null ? "" : stored;
        }
        return gluePatternsForPackages(project, packages);
    }

    private static List<Finding> scanTree(
            Path project,
            List<String> glue,
            WorkbenchFeatureIndex.Selection selection,
            Budget budget,
            PrintStream progress
    ) {
        List<Finding> findings = new ArrayList<>();
        Path features = project.resolve("src/test/resources");
        if (!Files.isDirectory(features)) return findings;
        try (Stream<Path> walk = Files.walk(features)) {
            walk.filter(path -> path.toString().endsWith(".feature")).forEach(file -> {
                if (budget.capped) return;
                addFindings(findings, scan(
                        project, file, 1, Integer.MAX_VALUE, glue, selection,
                        List.of(), List.of(), budget, progress
                ));
            });
        } catch (IOException ignored) {
            // A missing tree is an empty validate, not a launch.
        }
        return findings;
    }

    static List<Finding> scan(
            Path project,
            Path file,
            int start,
            int end,
            List<String> glue,
            WorkbenchFeatureIndex.Selection selection
    ) {
        return scan(project, file, start, end, glue, selection, List.of(), List.of(), null, null);
    }

    private static List<Finding> scan(
            Path project,
            Path file,
            int start,
            int end,
            List<String> glue,
            WorkbenchFeatureIndex.Selection selection,
            List<String> headers,
            List<String> values,
            Budget budget,
            PrintStream progress
    ) {
        List<Finding> findings = new ArrayList<>();
        if (file == null || !Files.isRegularFile(file)) return findings;
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            return findings;
        }
        String where = project == null ? file.toString() : project.relativize(file).toString().replace('\\', '/');
        int from = Math.max(1, start);
        int to = Math.min(lines.size(), end <= 0 ? lines.size() : end);
        for (int number = from; number <= to; number++) {
            if (budget != null && budget.capped) break;
            String raw = lines.get(number - 1);
            Matcher step = STEP.matcher(raw);
            if (!step.matches()) continue;
            if (budget != null && !budget.tick(progress)) break;
            String text = expand(step.group(1).strip(), headers, values);
            if (text.isBlank() || framework(text)) {
                findings.addAll(runTarget(where, number, text, selection));
                continue;
            }
            int matches = countGlue(glue, text);
            if (matches > 1) findings.add(new Finding("ambiguous", where + ":" + number, text));
            else if (matches == 0) findings.add(new Finding("undefined", where + ":" + number, text));
        }
        return findings;
    }

    static String expand(String text, List<String> headers, List<String> values) {
        if (text == null || headers == null || headers.isEmpty() || text.indexOf('<') < 0) return text;
        StringBuilder expanded = new StringBuilder();
        int index = 0;
        while (index < text.length()) {
            int open = text.indexOf('<', index);
            if (open < 0) {
                expanded.append(text, index, text.length());
                break;
            }
            int close = text.indexOf('>', open + 1);
            if (close < 0) {
                expanded.append(text, index, text.length());
                break;
            }
            expanded.append(text, index, open);
            String name = text.substring(open + 1, close);
            int header = headers.indexOf(name);
            if (header < 0) expanded.append(text, open, close + 1);
            else {
                String value = values != null && header < values.size() && values.get(header) != null
                        ? values.get(header)
                        : "";
                expanded.append(value);
            }
            index = close + 1;
        }
        return expanded.toString();
    }

    private static List<Finding> runTarget(
            String where,
            int number,
            String text,
            WorkbenchFeatureIndex.Selection selection
    ) {
        Matcher matcher = RUN_TARGET.matcher(text.strip());
        if (!matcher.matches()) return List.of();
        String target = matcher.group(1).strip();
        if (target.isBlank() || target.indexOf('<') >= 0) return List.of();
        if (selection == null) return List.of(new Finding("unresolved", where + ":" + number, target));
        boolean found = false;
        String name = target.startsWith("%") || target.startsWith("@") ? "" : target;
        String tag = target.startsWith("%") ? "@" + target.substring(1) : target.startsWith("@") ? target : "";
        for (WorkbenchFeatureIndex.Hit hit : selection.hits()) {
            if (!name.isEmpty() && hit.scenarioName() != null && hit.scenarioName().contains(name)) {
                found = true;
                break;
            }
            if (!tag.isEmpty() && hit.tags() != null && hit.tags().stream().anyMatch(tag::equals)) {
                found = true;
                break;
            }
        }
        if (found) return List.of();
        return List.of(new Finding("unresolved", where + ":" + number, target));
    }

    static boolean framework(String text) {
        if (text == null || text.isBlank()) return true;
        String trimmed = text.strip();
        if (trimmed.startsWith(",")) return true;
        String head = trimmed.toUpperCase(Locale.ROOT);
        return head.startsWith("TRACE:")
                || head.startsWith("DEBUG:")
                || head.startsWith("INFO:")
                || head.startsWith("WARN:")
                || head.startsWith("ERROR:")
                || head.startsWith("FAIL:")
                || head.startsWith("ASSERT:")
                || head.startsWith("SOFT ASSERT:")
                || head.startsWith("SOFT FAIL")
                || head.startsWith("RUN ")
                || head.startsWith("IF ")
                || head.startsWith("IF:")
                || head.startsWith("ELSE")
                || head.startsWith("MAP ")
                || head.startsWith("CLEAR ")
                || head.startsWith("ALWAYS ")
                || head.startsWith("END SCENARIO")
                || head.startsWith("END TEST")
                || head.startsWith("NAVIGATE TO:");
    }

    private static int countGlue(List<String> glue, String text) {
        int matches = 0;
        if (glue != null) {
            for (String pattern : glue) {
                if (patternMatches(pattern, text)) matches++;
            }
        }
        for (String pattern : corePatterns()) {
            if (patternMatches(pattern, text)) matches++;
        }
        return matches;
    }

    static boolean patternMatches(String cucumberPattern, String step) {
        Expression expression = compile(cucumberPattern);
        if (expression == null || step == null) return false;
        try {
            return expression.match(step) != null;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static Expression compile(String pattern) {
        if (pattern == null || pattern.isBlank()) return null;
        Optional<Expression> compiled = COMPILED.computeIfAbsent(pattern, WorkbenchValidate::tryCompile);
        return compiled.orElse(null);
    }

    private static Optional<Expression> tryCompile(String pattern) {
        try {
            return Optional.of(EXPRESSIONS.createExpression(pattern));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static List<String> corePatterns() {
        List<String> cached = corePatterns;
        if (cached != null) return cached;
        synchronized (WorkbenchValidate.class) {
            if (corePatterns == null) corePatterns = List.copyOf(loadCorePatterns());
            return corePatterns;
        }
    }

    private static Set<String> loadCorePatterns() {
        Set<String> patterns = new LinkedHashSet<>();
        ClassLoader loader = WorkbenchValidate.class.getClassLoader();
        try {
            Enumeration<URL> resources = loader.getResources(CORE_PACKAGE.replace('.', '/'));
            while (resources.hasMoreElements()) {
                for (String className : classNames(resources.nextElement())) {
                    patterns.addAll(annotationPatterns(className, loader));
                }
            }
        } catch (IOException ignored) {
            // Core patterns that cannot be read are not a match.
        }
        return patterns;
    }

    private static List<String> classNames(URL url) throws IOException {
        List<String> names = new ArrayList<>();
        if (url == null) return names;
        if ("jar".equals(url.getProtocol())) {
            JarURLConnection connection = (JarURLConnection) url.openConnection();
            String prefix = connection.getEntryName();
            if (prefix == null) prefix = "";
            if (!prefix.endsWith("/")) prefix = prefix + "/";
            try (JarFile jar = connection.getJarFile()) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String entry = entries.nextElement().getName();
                    if (!entry.startsWith(prefix) || !entry.endsWith(".class")) continue;
                    String rest = entry.substring(prefix.length());
                    if (rest.isEmpty() || rest.indexOf('/') >= 0) continue;
                    names.add(CORE_PACKAGE + "." + rest.substring(0, rest.length() - ".class".length()));
                }
            }
            return names;
        }
        if (!"file".equals(url.getProtocol())) return names;
        Path dir = Path.of(URI.create(url.toString()));
        if (!Files.isDirectory(dir)) return names;
        try (Stream<Path> walk = Files.list(dir)) {
            walk.filter(path -> path.getFileName().toString().endsWith(".class")).forEach(path -> {
                String file = path.getFileName().toString();
                names.add(CORE_PACKAGE + "." + file.substring(0, file.length() - ".class".length()));
            });
        }
        return names;
    }

    private static List<String> annotationPatterns(String className, ClassLoader loader) {
        List<String> patterns = new ArrayList<>();
        Class<?> type;
        try {
            type = Class.forName(className, true, loader);
        } catch (Throwable ignored) {
            return List.of();
        }
        for (Method method : type.getDeclaredMethods()) {
            for (Annotation annotation : method.getDeclaredAnnotations()) {
                String name = annotation.annotationType().getName();
                if (!name.startsWith("io.cucumber.java.en.")) continue;
                try {
                    Object value = annotation.annotationType().getMethod("value").invoke(annotation);
                    if (value instanceof String pattern && !pattern.isBlank()) patterns.add(pattern);
                } catch (ReflectiveOperationException ignored) {
                    // An annotation without a string value is not a step pattern.
                }
            }
        }
        return patterns;
    }

    private static List<String> gluePatterns(Path project) {
        return readJavaGlue(List.of(project.resolve("src/test/java"), project.resolve("src/main/java")));
    }

    /** Blank or missing packages contribute no consumer patterns. Subpackages are included. */
    private static List<String> gluePatternsForPackages(Path project, String packages) {
        if (packages == null || packages.isBlank()) return List.of();
        List<Path> roots = new ArrayList<>();
        for (String raw : packages.split(",")) {
            String pkg = raw.trim();
            if (pkg.isEmpty()) continue;
            Path relative = Path.of(pkg.replace('.', '/'));
            roots.add(project.resolve("src/test/java").resolve(relative));
            roots.add(project.resolve("src/main/java").resolve(relative));
        }
        return readJavaGlue(roots);
    }

    private static List<String> readJavaGlue(List<Path> roots) {
        List<String> patterns = new ArrayList<>();
        for (Path root : roots) {
            if (root == null || !Files.isDirectory(root)) continue;
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(path -> path.toString().endsWith(".java")).forEach(file -> patterns.addAll(readGlue(file)));
            } catch (IOException ignored) {
                // Glue that cannot be read is not a match.
            }
        }
        return patterns;
    }

    private static List<String> readGlue(Path file) {
        List<String> patterns = new ArrayList<>();
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Matcher annotations = ANNOTATION_HEAD.matcher(text);
            while (annotations.find()) {
                String pattern = readAnnotationValue(text, annotations.end());
                if (pattern != null && !pattern.isBlank()) patterns.add(pattern);
            }
        } catch (IOException ignored) {
            return List.of();
        }
        return patterns;
    }

    /** Java string literals joined by {@code +}. A non-literal (a constant) skips the annotation. */
    static String readAnnotationValue(String text, int index) {
        if (text == null) return null;
        StringBuilder joined = new StringBuilder();
        boolean any = false;
        int cursor = index;
        while (cursor < text.length()) {
            while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))) cursor++;
            if (cursor >= text.length()) return null;
            char current = text.charAt(cursor);
            if (current == ')') return any ? joined.toString() : null;
            if (current != '"' && current != '\'') return null;
            JavaLiteral literal = readJavaLiteral(text, cursor);
            if (literal == null) return null;
            joined.append(literal.value);
            any = true;
            cursor = literal.end;
            while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))) cursor++;
            if (cursor < text.length() && text.charAt(cursor) == '+') {
                cursor++;
                continue;
            }
            if (cursor < text.length() && text.charAt(cursor) == ')') return joined.toString();
            return null;
        }
        return null;
    }

    private record JavaLiteral(String value, int end) {
    }

    private static JavaLiteral readJavaLiteral(String text, int start) {
        char quote = text.charAt(start);
        StringBuilder value = new StringBuilder();
        for (int i = start + 1; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current == '\\') {
                if (i + 1 >= text.length()) return null;
                char escaped = text.charAt(++i);
                switch (escaped) {
                    case 'n' -> value.append('\n');
                    case 't' -> value.append('\t');
                    case 'r' -> value.append('\r');
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case '\\', '"', '\'' -> value.append(escaped);
                    case 'u' -> {
                        if (i + 4 >= text.length()) return null;
                        try {
                            value.append((char) Integer.parseInt(text.substring(i + 1, i + 5), 16));
                        } catch (NumberFormatException failure) {
                            return null;
                        }
                        i += 4;
                    }
                    default -> value.append(escaped);
                }
                continue;
            }
            if (current == quote) return new JavaLiteral(value.toString(), i + 1);
            if (current == '\n' || current == '\r') return null;
            value.append(current);
        }
        return null;
    }

    private static void addFindings(List<Finding> into, List<Finding> more) {
        for (Finding finding : more) {
            if (!into.contains(finding)) into.add(finding);
        }
    }

    private static void progress(PrintStream out, Budget budget) {
        out.println("validate progress stage=steps scenarios=" + budget.scenarios
                + " steps=" + budget.steps
                + " capped=" + budget.capped);
    }

    private static int count(List<Finding> findings, String kind) {
        int count = 0;
        for (Finding finding : findings) if (kind.equals(finding.kind())) count++;
        return count;
    }

    private static final class Budget {
        private final int limit;
        private int steps;
        private int scenarios;
        private boolean capped;

        private Budget(int limit) {
            this.limit = limit;
        }

        private boolean tick(PrintStream progress) {
            if (capped || steps >= limit) {
                capped = true;
                return false;
            }
            steps++;
            if (progress != null && steps % PROGRESS_EVERY == 0) {
                WorkbenchValidate.progress(progress, this);
            }
            return true;
        }
    }
}
