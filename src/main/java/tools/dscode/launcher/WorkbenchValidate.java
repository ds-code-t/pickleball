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
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * uses. It does not start a browser, Maven, or a worker. Consumer glue is the
 * definitions the runner would register from compiled classes, not source text.
 * Undefined and
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
    private static volatile List<StepDef> corePatterns;
    private static final Pattern STEP = Pattern.compile(
            "^\\s*(?::+\\s*)?(?:Given|When|Then|And|But|\\*)\\s+(.*)$"
    );
    /** Head of a step annotation, including an optional {@code value =}. The pattern itself is read as Java string literals. */
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
        GlueIndex index = GlueIndex.of(glueFor(project, runId, out));
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
                    project, file, hit.startLine(), hit.endLine(), index, all,
                    hit.headers(), hit.values(), budget, out
            ));
            budget.scenarios++;
        }
        if (selection.hits().isEmpty() && selection.fullScanFallback() && !budget.capped) {
            addFindings(findings, scanTree(project, index, all, budget, out));
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

    private static List<StepDef> glueFor(Path project, String runId, PrintStream out) {
        if (runId == null || runId.isBlank()) return compiledGlue(project, null);
        Path workspace = Workspace.directory(project, runId.trim()).toAbsolutePath().normalize();
        if (out != null) out.println("workspace=" + workspace);
        String packages = "";
        if (Workspace.exists(workspace)) {
            String stored = Workspace.readRunVars(workspace).get("pkb_glue");
            packages = stored == null ? "" : stored;
        }
        return compiledGlue(project, packages);
    }

    private static List<Finding> scanTree(
            Path project,
            GlueIndex index,
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
                        project, file, 1, Integer.MAX_VALUE, index, selection,
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
        List<StepDef> defs = new ArrayList<>();
        if (glue != null) {
            for (String pattern : glue) defs.add(new StepDef("", "", pattern));
        }
        return scan(project, file, start, end, GlueIndex.of(defs), selection, List.of(), List.of(), null, null);
    }

    private static List<Finding> scan(
            Path project,
            Path file,
            int start,
            int end,
            GlueIndex index,
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
            List<StepDef> matches = index == null ? List.of() : index.matching(text);
            if (matches.size() > 1) findings.add(new Finding("ambiguous", where + ":" + number, describe(text, matches)));
            else if (matches.isEmpty()) findings.add(new Finding("undefined", where + ":" + number, text));
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

    private static List<StepDef> corePatterns() {
        List<StepDef> cached = corePatterns;
        if (cached != null) return cached;
        synchronized (WorkbenchValidate.class) {
            if (corePatterns == null) corePatterns = List.copyOf(loadCorePatterns());
            return corePatterns;
        }
    }

    private static List<StepDef> loadCorePatterns() {
        List<StepDef> patterns = new ArrayList<>();
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

    private static List<StepDef> annotationPatterns(String className, ClassLoader loader) {
        List<StepDef> patterns = new ArrayList<>();
        Class<?> type;
        try {
            type = Class.forName(className, false, loader);
        } catch (Throwable ignored) {
            return List.of();
        }
        for (Method method : type.getDeclaredMethods()) {
            for (Annotation annotation : method.getDeclaredAnnotations()) {
                String name = annotation.annotationType().getName();
                if (!name.startsWith("io.cucumber.java.en.")) continue;
                try {
                    Object value = annotation.annotationType().getMethod("value").invoke(annotation);
                    if (value instanceof String pattern && !pattern.isBlank()) {
                        patterns.add(new StepDef(className, method.getName(), pattern));
                    }
                } catch (ReflectiveOperationException ignored) {
                    // An annotation without a string value is not a step pattern.
                }
            }
        }
        return patterns;
    }

    /**
     * Blank packages contribute no consumer definitions. A null package list is every
     * compiled test and main class. Subpackages are included. A commented-out
     * annotation is not on the class file, so it is not a definition.
     */
    private static List<StepDef> compiledGlue(Path project, String packages) {
        if (packages != null && packages.isBlank()) return List.of();
        List<String> prefixes = new ArrayList<>();
        if (packages != null) {
            for (String raw : packages.split(",")) {
                String pkg = raw.trim();
                if (!pkg.isEmpty()) prefixes.add(pkg);
            }
            if (prefixes.isEmpty()) return List.of();
        }
        List<Path> roots = classRoots(project);
        List<URL> urls = new ArrayList<>();
        for (Path root : roots) {
            if (root == null || !Files.isDirectory(root)) continue;
            try {
                urls.add(root.toUri().toURL());
            } catch (IOException ignored) {
                // A root that cannot be opened contributes no classes.
            }
        }
        if (urls.isEmpty()) return List.of();
        List<StepDef> defs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        ClassLoader parent = WorkbenchValidate.class.getClassLoader();
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), parent)) {
            for (Path root : roots) {
                if (root == null || !Files.isDirectory(root)) continue;
                try (Stream<Path> walk = Files.walk(root)) {
                    for (Path path : walk.filter(file -> file.getFileName().toString().endsWith(".class")).toList()) {
                        String relative = root.relativize(path).toString().replace('\\', '/');
                        if (!relative.endsWith(".class") || relative.indexOf('$') >= 0) continue;
                        String className = relative.substring(0, relative.length() - ".class".length()).replace('/', '.');
                        if (!packageAllowed(className, prefixes) || !seen.add(className)) continue;
                        defs.addAll(annotationPatterns(className, loader));
                    }
                } catch (IOException ignored) {
                    // A class tree that cannot be read is not a match.
                }
            }
        } catch (IOException ignored) {
            return defs;
        }
        return defs;
    }

    private static List<Path> classRoots(Path project) {
        return List.of(
                project.resolve("target/test-classes"),
                project.resolve("target/classes"),
                project.resolve("build/classes/java/test"),
                project.resolve("build/classes/java/main")
        );
    }

    private static boolean packageAllowed(String className, List<String> prefixes) {
        if (prefixes == null || prefixes.isEmpty()) return true;
        for (String prefix : prefixes) {
            if (className.equals(prefix) || className.startsWith(prefix + ".")) return true;
        }
        return false;
    }

    /** Literal text a step must start with. Empty means the pattern is always tested. */
    static String literalPrefix(String pattern) {
        if (pattern == null || pattern.isEmpty()) return "";
        boolean anchored = pattern.charAt(0) == '^';
        boolean slashRegex = pattern.length() >= 2 && pattern.charAt(0) == '/' && pattern.charAt(pattern.length() - 1) == '/';
        if (!anchored && (slashRegex || pattern.charAt(pattern.length() - 1) == '$')) return "";
        int index = anchored ? 1 : 0;
        StringBuilder prefix = new StringBuilder();
        while (index < pattern.length()) {
            char current = pattern.charAt(index);
            if (current == '\\') {
                if (index + 1 >= pattern.length()) break;
                char escaped = pattern.charAt(++index);
                if (escaped == 'd' || escaped == 'D' || escaped == 's' || escaped == 'S'
                        || escaped == 'w' || escaped == 'W') {
                    break;
                }
                if (escaped == 'n') prefix.append('\n');
                else if (escaped == 't') prefix.append('\t');
                else if (escaped == 'r') prefix.append('\r');
                else prefix.append(escaped);
                index++;
                continue;
            }
            if ("()[]{}.*+?|^$".indexOf(current) >= 0) break;
            prefix.append(current);
            index++;
        }
        return prefix.toString();
    }

    private static String describe(String text, List<StepDef> matches) {
        StringBuilder line = new StringBuilder(text == null ? "" : text);
        for (StepDef def : matches) {
            line.append(" | ");
            if (def.owner() != null && !def.owner().isBlank()) {
                line.append(def.owner()).append('#').append(def.method()).append(' ');
            }
            line.append(def.pattern());
        }
        return line.toString();
    }

    private record StepDef(String owner, String method, String pattern) {
    }

    /** One literal-prefix trie. Patterns with no safe prefix stay in {@code always}. */
    private static final class GlueIndex {
        private final List<StepDef> always = new ArrayList<>();
        private final Node root = new Node();

        private static GlueIndex of(List<StepDef> consumer) {
            GlueIndex index = new GlueIndex();
            if (consumer != null) {
                for (StepDef def : consumer) index.add(def);
            }
            for (StepDef def : corePatterns()) index.add(def);
            return index;
        }

        private void add(StepDef def) {
            if (def == null || def.pattern() == null || def.pattern().isBlank()) return;
            String prefix = literalPrefix(def.pattern());
            if (prefix.isEmpty()) {
                always.add(def);
                return;
            }
            Node node = root;
            for (int i = 0; i < prefix.length(); i++) {
                node = node.next.computeIfAbsent(prefix.charAt(i), ignored -> new Node());
            }
            node.here.add(def);
        }

        private List<StepDef> matching(String text) {
            List<StepDef> candidates = new ArrayList<>(always);
            Node node = root;
            if (text != null) {
                for (int i = 0; i < text.length() && node != null; i++) {
                    node = node.next.get(text.charAt(i));
                    if (node != null && !node.here.isEmpty()) candidates.addAll(node.here);
                }
            }
            List<StepDef> matched = new ArrayList<>();
            for (StepDef def : candidates) {
                if (patternMatches(def.pattern(), text)) matched.add(def);
            }
            return matched;
        }

        private static final class Node {
            private final Map<Character, Node> next = new HashMap<>();
            private final List<StepDef> here = new ArrayList<>();
        }
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
