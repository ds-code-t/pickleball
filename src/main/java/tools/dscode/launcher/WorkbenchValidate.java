package tools.dscode.launcher;

import io.cucumber.cucumberexpressions.Expression;
import io.cucumber.cucumberexpressions.ExpressionFactory;
import io.cucumber.cucumberexpressions.ParameterTypeRegistry;
import tools.dscode.control.protocol.WorkbenchFeatureIndex;
import tools.dscode.control.protocol.Workspace;

import java.io.IOException;
import java.io.InputStream;
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
        return run(project, tags, name, null, null, false, false, out, err);
    }

    /**
     * @param runId when set, glue comes only from that workspace's {@code pkb_glue}
     *              (blank means no consumer patterns; core steps still match)
     * @param maxSteps step budget; null keeps {@link #STEP_CAP}
     * @param allSteps when true, there is no step cap ({@code --all} wins over {@code maxSteps})
     * @param noop when true, empty consumer step definitions are listed as {@code noop}
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
        return run(project, tags, name, runId, maxSteps, allSteps, false, out, err);
    }

    public static int run(
            Path project,
            String tags,
            String name,
            String runId,
            Integer maxSteps,
            boolean allSteps,
            boolean noop,
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
        List<Finding> noops = noop ? emptyGlue(project, runId, out) : List.of();
        findings.addAll(noops);
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
                    + " noop=" + noops.size()
                    + " elapsedMs=" + elapsed
                    + " capped=" + budget.capped
                    + " skipped=" + skipped);
        }
        return undefined + ambiguous + unresolved + noops.size() == 0 ? 0 : 1;
    }

    /**
     * Empty consumer step bodies. Framework classes are skipped. A method whose
     * class file cannot be read is not listed. {@code pkb_noop_glue=fail} is
     * the execute-step failure switch; this list is the same detection.
     */
    static List<Finding> emptyGlue(Path project, String runId, PrintStream out) {
        List<Finding> found = new ArrayList<>();
        if (project == null) return found;
        List<Path> roots = classRoots(project);
        List<URL> urls = new ArrayList<>();
        Set<String> urlSeen = new LinkedHashSet<>();
        for (Path root : roots) addUrl(urls, urlSeen, root);
        for (Path entry : testClasspathEntries(project)) addUrl(urls, urlSeen, entry);
        if (urls.isEmpty()) return found;
        List<String> prefixes = gluePrefixes(project, runId);
        if (prefixes != null && prefixes.isEmpty()) return found;
        ClassLoader parent = WorkbenchValidate.class.getClassLoader();
        try (URLClassLoader loader = new GlueClassLoader(urls.toArray(URL[]::new), parent)) {
            for (Path root : roots) {
                if (root == null || !Files.isDirectory(root)) continue;
                try (Stream<Path> walk = Files.walk(root)) {
                    for (Path path : walk.filter(file -> file.getFileName().toString().endsWith(".class")).toList()) {
                        String relative = root.relativize(path).toString().replace('\\', '/');
                        if (!relative.endsWith(".class") || relative.indexOf('$') >= 0) continue;
                        String className = relative.substring(0, relative.length() - ".class".length()).replace('/', '.');
                        if (className.startsWith("tools.dscode.") || !packageAllowed(className, prefixes)) continue;
                        Class<?> type;
                        try {
                            type = Class.forName(className, false, loader);
                        } catch (Throwable ignored) {
                            continue;
                        }
                        Method[] methods;
                        try {
                            methods = type.getDeclaredMethods();
                        } catch (Throwable ignored) {
                            continue;
                        }
                        for (Method method : methods) {
                            if (!consumerEmpty(method)) continue;
                            String pattern = stepPattern(method);
                            if (pattern == null) continue;
                            String where = className + "#" + method.getName();
                            found.add(new Finding(
                                    "noop",
                                    where,
                                    pattern + " noop:true empty consumer glue. Set pkb_noop_glue=fail to fail the step."
                            ));
                        }
                    }
                } catch (IOException ignored) {
                    // A class tree that cannot be read is not a match.
                }
            }
        } catch (IOException ignored) {
            return found;
        }
        return found;
    }

    private static List<String> gluePrefixes(Path project, String runId) {
        if (runId == null || runId.isBlank()) return null;
        Path workspace = Workspace.directory(project, runId.trim()).toAbsolutePath().normalize();
        String packages = "";
        if (Workspace.exists(workspace)) {
            String stored = Workspace.readRunVars(workspace).get("pkb_glue");
            packages = stored == null ? "" : stored;
        }
        if (packages.isBlank()) return List.of();
        List<String> prefixes = new ArrayList<>();
        for (String raw : packages.split(",")) {
            String pkg = raw.trim();
            if (!pkg.isEmpty()) prefixes.add(pkg);
        }
        return prefixes;
    }

    private static String stepPattern(Method method) {
        StringBuilder joined = new StringBuilder();
        try {
            for (Annotation annotation : method.getDeclaredAnnotations()) {
                collectPattern(joined, annotation);
            }
        } catch (Throwable ignored) {
            return null;
        }
        return joined.isEmpty() ? null : joined.toString();
    }

    private static void collectPattern(StringBuilder joined, Annotation annotation) {
        String name = annotation.annotationType().getName();
        if (!isLanguageStepAnnotation(name)) return;
        try {
            Object value = annotation.annotationType().getMethod("value").invoke(annotation);
            if (value instanceof String pattern && !pattern.isBlank()) {
                if (!joined.isEmpty()) joined.append(" | ");
                joined.append(pattern);
            } else if (value instanceof Object[] nested) {
                for (Object item : nested) {
                    if (item instanceof Annotation inner) collectPattern(joined, inner);
                }
            }
        } catch (ReflectiveOperationException ignored) {
            // An annotation without a string value is not a step pattern.
        }
    }

    /** Same rule as the worker's {@code NoopGlue}. Reflection keeps the launcher off that module. */
    public static boolean consumerEmpty(Method method) {
        if (method == null) return false;
        String className = method.getDeclaringClass().getName();
        if (className.startsWith("tools.dscode.") || "tools.dscode".equals(className)) return false;
        try {
            Class<?> type = Class.forName("tools.dscode.control.bridge.NoopGlue");
            Method check = type.getMethod("consumerEmpty", Method.class);
            return Boolean.TRUE.equals(check.invoke(null, method));
        } catch (ReflectiveOperationException failure) {
            return bytecodeEmpty(method);
        }
    }

    private static boolean bytecodeEmpty(Method method) {
        String resource = method.getDeclaringClass().getName().replace('.', '/') + ".class";
        ClassLoader loader = method.getDeclaringClass().getClassLoader();
        InputStream in = loader == null
                ? ClassLoader.getSystemResourceAsStream(resource)
                : loader.getResourceAsStream(resource);
        if (in == null) return false;
        try (in) {
            return Boolean.TRUE.equals(codeReturnsOnly(in.readAllBytes(), method.getName()));
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    /** True when the named method's code is only a return. False when real code or the method is missing. */
    static Boolean codeReturnsOnly(byte[] bytes, String methodName) {
        try {
            Cursor cursor = new Cursor(bytes);
            if (cursor.u4() != 0xCAFEBABE) return Boolean.FALSE;
            cursor.u2();
            cursor.u2();
            int count = cursor.u2();
            String[] utf8 = new String[count];
            for (int i = 1; i < count; i++) {
                int tag = cursor.u1();
                switch (tag) {
                    case 1 -> {
                        int length = cursor.u2();
                        utf8[i] = new String(cursor.bytes(length), StandardCharsets.UTF_8);
                    }
                    case 7, 8, 16, 19, 20 -> cursor.skip(2);
                    case 15 -> cursor.skip(3);
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> cursor.skip(4);
                    case 5, 6 -> {
                        cursor.skip(8);
                        i++;
                    }
                    default -> {
                        return Boolean.FALSE;
                    }
                }
            }
            cursor.u2();
            cursor.u2();
            cursor.u2();
            int interfaces = cursor.u2();
            cursor.skip(interfaces * 2);
            int fields = cursor.u2();
            for (int i = 0; i < fields; i++) skipMember(cursor);
            int methods = cursor.u2();
            Boolean empty = null;
            for (int i = 0; i < methods; i++) {
                cursor.u2();
                int nameIndex = cursor.u2();
                cursor.u2();
                int attributes = cursor.u2();
                String name = nameIndex > 0 && nameIndex < utf8.length ? utf8[nameIndex] : null;
                boolean match = methodName.equals(name);
                for (int a = 0; a < attributes; a++) {
                    int attrName = cursor.u2();
                    int length = cursor.u4();
                    byte[] body = cursor.bytes(length);
                    if (!match) continue;
                    String attr = attrName > 0 && attrName < utf8.length ? utf8[attrName] : "";
                    if (!"Code".equals(attr) || body.length < 8) continue;
                    Cursor code = new Cursor(body);
                    code.u2();
                    code.u2();
                    int codeLength = code.u4();
                    if (codeLength < 0 || codeLength > body.length - 8) continue;
                    byte[] opcodes = code.bytes(codeLength);
                    boolean returns = opcodes.length == 1 && (opcodes[0] & 0xFF) == 0xB1;
                    empty = returns;
                }
            }
            return Boolean.TRUE.equals(empty);
        } catch (RuntimeException failure) {
            return Boolean.FALSE;
        }
    }

    private static void skipMember(Cursor cursor) {
        cursor.u2();
        cursor.u2();
        cursor.u2();
        int attributes = cursor.u2();
        for (int i = 0; i < attributes; i++) {
            cursor.u2();
            int length = cursor.u4();
            cursor.skip(length);
        }
    }

    private static final class Cursor {
        private final byte[] data;
        private int at;

        private Cursor(byte[] data) {
            this.data = data;
        }

        private int u1() {
            if (at >= data.length) throw new IllegalArgumentException("class file truncated");
            return data[at++] & 0xFF;
        }

        private int u2() {
            return (u1() << 8) | u1();
        }

        private int u4() {
            return (u1() << 24) | (u1() << 16) | (u1() << 8) | u1();
        }

        private void skip(int n) {
            if (n < 0 || at + n > data.length) throw new IllegalArgumentException("class file truncated");
            at += n;
        }

        private byte[] bytes(int n) {
            if (n < 0 || at + n > data.length) throw new IllegalArgumentException("class file truncated");
            byte[] slice = new byte[n];
            System.arraycopy(data, at, slice, 0, n);
            at += n;
            return slice;
        }
    }

    private static List<StepDef> glueFor(Path project, String runId, PrintStream out) {
        if (runId == null || runId.isBlank()) return compiledGlue(project, null, out);
        Path workspace = Workspace.directory(project, runId.trim()).toAbsolutePath().normalize();
        if (out != null) out.println("workspace=" + workspace);
        String packages = "";
        if (Workspace.exists(workspace)) {
            String stored = Workspace.readRunVars(workspace).get("pkb_glue");
            packages = stored == null ? "" : stored;
        }
        return compiledGlue(project, packages, out);
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
        try {
            for (Method method : type.getDeclaredMethods()) {
                for (Annotation annotation : method.getDeclaredAnnotations()) {
                    collectStep(patterns, className, method.getName(), annotation);
                }
            }
        } catch (Throwable ignored) {
            // A parameter or superclass the loader cannot see drops this class.
            return List.of();
        }
        return patterns;
    }

    /**
     * Language step annotations live under {@code io.cucumber.java.<lang>}.
     * Hooks on {@code io.cucumber.java} itself are not step patterns.
     * A repeatable container ({@code @Given} twice) exposes the nested annotations.
     */
    private static void collectStep(List<StepDef> patterns, String className, String method, Annotation annotation) {
        String name = annotation.annotationType().getName();
        if (!isLanguageStepAnnotation(name)) return;
        try {
            Object value = annotation.annotationType().getMethod("value").invoke(annotation);
            if (value instanceof String pattern && !pattern.isBlank()) {
                patterns.add(new StepDef(className, method, pattern));
                return;
            }
            if (value instanceof Object[] nested) {
                for (Object item : nested) {
                    if (item instanceof Annotation inner) collectStep(patterns, className, method, inner);
                }
            }
        } catch (ReflectiveOperationException ignored) {
            // An annotation without a string value is not a step pattern.
        }
    }

    private static boolean isLanguageStepAnnotation(String name) {
        String prefix = "io.cucumber.java.";
        if (name == null || !name.startsWith(prefix)) return false;
        String rest = name.substring(prefix.length());
        return rest.indexOf('.') >= 0 || rest.indexOf('$') >= 0;
    }

    /**
     * Blank packages contribute no consumer definitions. A null package list is every
     * compiled test and main class. Subpackages are included. A commented-out
     * annotation is not on the class file, so it is not a definition. The loader
     * sees the resolved test classpath, so a superclass or parameter type that
     * lives only in a dependency jar still loads.
     */
    private static List<StepDef> compiledGlue(Path project, String packages, PrintStream out) {
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
        Set<String> urlSeen = new LinkedHashSet<>();
        for (Path root : roots) addUrl(urls, urlSeen, root);
        for (Path entry : testClasspathEntries(project)) addUrl(urls, urlSeen, entry);
        if (urls.isEmpty()) return List.of();
        List<StepDef> defs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<String> warned = new LinkedHashSet<>();
        ClassLoader parent = WorkbenchValidate.class.getClassLoader();
        try (URLClassLoader loader = new GlueClassLoader(urls.toArray(URL[]::new), parent)) {
            for (Path root : roots) {
                if (root == null || !Files.isDirectory(root)) continue;
                try (Stream<Path> walk = Files.walk(root)) {
                    for (Path path : walk.filter(file -> file.getFileName().toString().endsWith(".class")).toList()) {
                        String relative = root.relativize(path).toString().replace('\\', '/');
                        if (!relative.endsWith(".class") || relative.indexOf('$') >= 0) continue;
                        String className = relative.substring(0, relative.length() - ".class".length()).replace('/', '.');
                        if (!packageAllowed(className, prefixes) || !seen.add(className)) continue;
                        warnIfStale(project, className, path, out, warned);
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

    private static void addUrl(List<URL> urls, Set<String> seen, Path path) {
        if (path == null || !Files.exists(path) || !seen.add(path.toAbsolutePath().normalize().toString())) return;
        try {
            urls.add(path.toAbsolutePath().normalize().toUri().toURL());
        } catch (IOException ignored) {
            // A root that cannot be opened contributes no classes.
        }
    }

    /**
     * Parent-first, except when the parent can see the class file and still cannot
     * link it. The validate JVM already has {@code target/test-classes} on its
     * classpath, so a superclass that lives only in a resolved dependency jar
     * fails there. Define that class here, where the jar is on the URL list.
     */
    private static final class GlueClassLoader extends URLClassLoader {
        private GlueClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            try {
                return super.loadClass(name, resolve);
            } catch (NoClassDefFoundError missing) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> local = findLoadedClass(name);
                    if (local == null) {
                        try {
                            local = findClass(name);
                        } catch (ClassNotFoundException notLocal) {
                            throw missing;
                        }
                    }
                    if (resolve) resolveClass(local);
                    return local;
                }
            }
        }
    }

    private static List<Path> testClasspathEntries(Path project) {
        List<Path> entries = new ArrayList<>();
        try {
            Path state = tools.dscode.control.protocol.PickleballLocalLayout.workbenchStateRoot(project);
            addClasspathFile(entries, tools.dscode.control.protocol.speed.ClasspathCache.file(state));
            addClasspathFile(entries, state.resolve("classpath.txt"));
        } catch (RuntimeException ignored) {
            // A project without workbench state still validates its own class roots.
        }
        addClasspathFile(entries, project.resolve(".pickleball").resolve("harness-classpath.txt"));
        return entries;
    }

    private static void addClasspathFile(List<Path> entries, Path file) {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) continue;
                String[] parts = line.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator));
                for (String part : parts) {
                    String trimmed = part.trim();
                    if (trimmed.isEmpty()) continue;
                    Path path = Path.of(trimmed);
                    if (Files.exists(path)) entries.add(path.toAbsolutePath().normalize());
                }
            }
        } catch (IOException ignored) {
            // A classpath file that cannot be read is not a match.
        }
    }

    private static void warnIfStale(Path project, String className, Path classFile, PrintStream out, Set<String> warned) {
        if (out == null || project == null || className == null) return;
        String relative = className.replace('.', '/') + ".java";
        for (String root : List.of("src/test/java", "src/main/java")) {
            Path source = project.resolve(root).resolve(relative);
            if (!Files.isRegularFile(source)) continue;
            try {
                if (Files.getLastModifiedTime(source).toMillis() <= Files.getLastModifiedTime(classFile).toMillis()) {
                    return;
                }
            } catch (IOException failure) {
                return;
            }
            String shown = root + "/" + relative;
            if (warned.add(shown)) {
                out.println("WARN: compiled glue is older than its source: " + shown);
            }
            return;
        }
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

    /**
     * Literal text a step must start with. Empty means the pattern is always tested.
     * A brace quantifier, {@code ?}, {@code *}, or an alternation drops the quantified
     * text. Anything this scan is not sure about is left out, so a matcher-valid step
     * is never skipped.
     */
    static String literalPrefix(String pattern) {
        if (pattern == null || pattern.isEmpty()) return "";
        if (pattern.charAt(0) == '^') {
            if (hasUnescapedAlternation(pattern)) return "";
            return regexPrefix(pattern, 1);
        }
        if (isRegexPattern(pattern)) return "";
        return cucumberPrefix(pattern);
    }

    /** Same split {@code ExpressionFactory} uses: {@code ^} or {@code $} or {@code /.../}. */
    private static boolean isRegexPattern(String pattern) {
        char first = pattern.charAt(0);
        char last = pattern.charAt(pattern.length() - 1);
        if (first == '^' || last == '$') return true;
        return pattern.length() >= 2 && first == '/' && last == '/';
    }

    private static boolean hasUnescapedAlternation(String pattern) {
        boolean escaped = false;
        for (int i = 0; i < pattern.length(); i++) {
            char current = pattern.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == '|') return true;
        }
        return false;
    }

    private static String regexPrefix(String pattern, int index) {
        StringBuilder prefix = new StringBuilder();
        while (index < pattern.length()) {
            char current = pattern.charAt(index);
            if (current == '\\') {
                if (index + 1 >= pattern.length()) break;
                char escaped = pattern.charAt(++index);
                if (isRegexClassEscape(escaped)) break;
                prefix.append(switch (escaped) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    default -> escaped;
                });
                index++;
                continue;
            }
            if (current == '|') return "";
            if (current == '?' || current == '*') {
                if (!prefix.isEmpty()) prefix.setLength(prefix.length() - 1);
                break;
            }
            if (current == '{') {
                if (isBraceQuantifier(pattern, index) && !prefix.isEmpty()) {
                    prefix.setLength(prefix.length() - 1);
                }
                break;
            }
            if ("()[]+^$.".indexOf(current) >= 0) break;
            prefix.append(current);
            index++;
        }
        return prefix.toString();
    }

    private static String cucumberPrefix(String pattern) {
        StringBuilder prefix = new StringBuilder();
        int index = 0;
        while (index < pattern.length()) {
            char current = pattern.charAt(index);
            if (current == '\\') {
                if (index + 1 >= pattern.length()) break;
                prefix.append(pattern.charAt(++index));
                index++;
                continue;
            }
            if (current == '/') {
                while (!prefix.isEmpty() && !Character.isWhitespace(prefix.charAt(prefix.length() - 1))) {
                    prefix.setLength(prefix.length() - 1);
                }
                break;
            }
            if (current == '(' || current == '{' || current == ')' || current == '}') break;
            prefix.append(current);
            index++;
        }
        return prefix.toString();
    }

    private static boolean isRegexClassEscape(char escaped) {
        return escaped == 'd' || escaped == 'D' || escaped == 's' || escaped == 'S'
                || escaped == 'w' || escaped == 'W' || escaped == 'b' || escaped == 'B'
                || escaped == 'A' || escaped == 'z' || escaped == 'Z' || escaped == 'G';
    }

    /** {@code {n}}, {@code {n,}}, {@code {n,m}}, or {@code {,m}}. Not a Cucumber {@code {int}}. */
    private static boolean isBraceQuantifier(String pattern, int open) {
        int index = open + 1;
        int length = pattern.length();
        if (index >= length) return false;
        boolean digits = false;
        if (Character.isDigit(pattern.charAt(index))) {
            digits = true;
            while (index < length && Character.isDigit(pattern.charAt(index))) index++;
        }
        if (index < length && pattern.charAt(index) == ',') {
            index++;
            while (index < length && Character.isDigit(pattern.charAt(index))) index++;
            return index < length && pattern.charAt(index) == '}' && (digits || index > open + 2);
        }
        return digits && index < length && pattern.charAt(index) == '}';
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
