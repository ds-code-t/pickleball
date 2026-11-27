package tools.dscode.engine.match;

import tools.dscode.control.override.StepOverrideRegistry;
import tools.dscode.control.override.StepOverrideRule;
import tools.dscode.engine.EngineLog;
import tools.dscode.engine.adapter.cucumber.CucumberStepRegistry;
import tools.dscode.engine.adapter.cucumber.CucumberTags;
import tools.dscode.engine.adapter.cucumber.GlueHooks;
import tools.dscode.engine.model.DocText;
import tools.dscode.engine.model.Table;
import tools.dscode.store.StoreConfig;
import tools.dscode.store.StoreDatabase;
import tools.dscode.store.StorePaths;
import tools.dscode.store.StoreStepCatalog;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the same step annotations Cucumber reads. Native matching uses
 * {@link PatternMatcher}. The Cucumber adapter keeps {@code ExpressionFactory}
 * in {@link CucumberStepRegistry}. Annotation values are not rewritten here.
 */
public final class StepRegistry {
    private static final String CUCUMBER_TABLE = "io.cucumber.datatable.DataTable";
    private static final String CUCUMBER_DOC = "io.cucumber.docstring.DocString";
    private static final String CUCUMBER_STEP_META = "io.cucumber.java.StepDefinitionAnnotation";

    private final List<GlueStep> steps = new ArrayList<>();
    private final List<Hook> hooks = new ArrayList<>();
    private Path logProject;
    private String preferredDefinition = "";
    private boolean nativeDecided;
    private boolean catalogLimited;
    private List<GlueStep> nativeSteps;

    public StepRegistry() {
        this(Locale.ENGLISH);
    }

    public StepRegistry(Locale locale) {
        Locale chosen = locale == null ? Locale.ENGLISH : locale;
        CucumberStepRegistry.attach(this, chosen);
    }

    public StepRegistry logTo(Path project) {
        this.logProject = project;
        return this;
    }

    /**
     * A run that named glue classes matches Java store rows only for the classes
     * it scanned. A native suite that does not call this still uses every enabled row.
     */
    public StepRegistry limitToScanned() {
        this.catalogLimited = true;
        this.nativeDecided = false;
        this.nativeSteps = null;
        return this;
    }

    /** When set, native match keeps only this {@code definition_id}. */
    public StepRegistry prefer(String definitionId) {
        this.preferredDefinition = definitionId == null ? "" : definitionId;
        return this;
    }

    public List<GlueStep> steps() {
        return List.copyOf(steps);
    }

    public StepRegistry scan(Class<?>... types) {
        catalogChanged();
        if (types == null) return this;
        for (Class<?> type : types) {
            if (type == null) continue;
            scanType(type);
            for (Class<?> nested : type.getDeclaredClasses()) {
                scan(nested);
            }
        }
        return this;
    }

    /** Declared methods, then superclasses, so a package-private hook is not skipped. */
    private void scanType(Class<?> type) {
        Class<?> cursor = type;
        while (cursor != null && cursor != Object.class) {
            for (Method method : cursor.getDeclaredMethods()) {
                if (method.isSynthetic() || method.isBridge()) continue;
                scanMethod(method);
            }
            cursor = cursor.getSuperclass();
        }
    }

    /** Loads every class in a package the same way a Cucumber glue path does. */
    public StepRegistry scanPackage(String packageName) {
        catalogChanged();
        if (packageName == null || packageName.isBlank()) return this;
        ClassLoader loader = glueLoader(packageName);
        String path = packageName.replace('.', '/');
        try {
            Enumeration<URL> resources = loader.getResources(path);
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                if ("file".equals(url.getProtocol())) {
                    scanDirectory(url, packageName, loader);
                } else if ("jar".equals(url.getProtocol())) {
                    scanJar(url, packageName, loader);
                }
            }
        } catch (Exception failure) {
            EngineLog.log(logProject, "registry", packageName, failure.toString());
        }
        return this;
    }

    private void scanDirectory(URL url, String packageName, ClassLoader loader) {
        Path dir;
        try {
            dir = Path.of(url.toURI());
        } catch (URISyntaxException failure) {
            return;
        }
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path classFile : files.filter(file -> file.getFileName().toString().endsWith(".class")).toList()) {
                String binary = classFile.getFileName().toString();
                binary = binary.substring(0, binary.length() - ".class".length());
                consider(packageName, binary, loader);
            }
        } catch (IOException failure) {
            EngineLog.log(logProject, "registry", packageName, failure.toString());
        }
    }

    /** A consumer classpath is a jar. {@code getResources} returns {@code jar:} URLs, not directories. */
    private void scanJar(URL url, String packageName, ClassLoader loader) {
        String spec = url.toString();
        int bang = spec.indexOf("!/");
        if (bang < 0) return;
        String filePart = spec.substring(0, bang);
        if (filePart.startsWith("jar:")) filePart = filePart.substring("jar:".length());
        Path jarPath;
        try {
            jarPath = Path.of(URI.create(filePart));
        } catch (IllegalArgumentException failure) {
            return;
        }
        String prefix = spec.substring(bang + 2);
        if (!prefix.endsWith("/")) prefix = prefix + "/";
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith(prefix) || name.endsWith("/")) continue;
                if (name.indexOf('/', prefix.length()) >= 0) continue;
                if (!name.endsWith(".class")) continue;
                String binary = name.substring(prefix.length(), name.length() - ".class".length());
                consider(packageName, binary, loader);
            }
        } catch (IOException failure) {
            EngineLog.log(logProject, "registry", packageName, failure.toString());
        }
    }

    private void consider(String packageName, String binary, ClassLoader loader) {
        if (binary == null || binary.isBlank()) return;
        if (binary.contains("$") && binary.matches(".*\\$\\d+.*")) return;
        // JUnit fixtures in the same package are not glue. An explicit
        // scan() of the caller's class still loads that fixture.
        if (binary.endsWith("Test")) return;
        int nested = binary.indexOf('$');
        if (nested > 0 && binary.substring(0, nested).endsWith("Test")) return;
        String className = packageName + "." + binary;
        try {
            scan(Class.forName(className, true, loader));
        } catch (Throwable failure) {
            EngineLog.log(logProject, "registry", className, failure.toString());
        }
    }

    public GlueStep register(String keyword, String pattern, Method method) {
        GlueStep step = define(keyword, pattern, method, "");
        add(step);
        return step;
    }

    public StepMatch match(String text) {
        Optional<StepMatch> override = liveOverride(text);
        if (override.isPresent()) return override.get();
        return matchGlue(text);
    }

    /**
     * Native match. Store rows are used when Store is on and the catalog has
     * definitions. Scanned glue is the file-only path and the extra classes a
     * caller registered that are not already in the catalog.
     */
    public StepMatch matchNative(String text) {
        Optional<StepMatch> override = liveOverride(text);
        if (override.isPresent()) return override.get();
        List<GlueStep> rows = nativeCandidates();
        if (rows == null) return matchGlue(text);
        return matchSteps(text, rows);
    }

    public void addHook(String phase, int order, String tags, Method method) {
        hooks.add(new Hook(phase, order, tags, method));
    }

    /** Override rules registered for {@code scenarioId}, then glue. No live Cucumber scenario required. */
    public StepMatch match(String text, String scenarioId) {
        if (scenarioId != null && !scenarioId.isBlank()) {
            Optional<StepMatch> override = overrides(text, StepOverrideRegistry.rules(scenarioId));
            if (override.isPresent()) return override.get();
        }
        return matchGlue(text);
    }

    public StepMatch match(String text, List<StepOverrideRule> rules) {
        Optional<StepMatch> override = overrides(text, rules);
        if (override.isPresent()) return override.get();
        return matchGlue(text);
    }

    /** Names the executor a match will use. It does not run the step. */
    public static String dispatchStub(StepMatch match) {
        if (match == null || !match.matched()) return "stub:undefined";
        return "stub:" + match.kind().name().toLowerCase(Locale.ROOT);
    }

    private Optional<StepMatch> liveOverride(String text) {
        try {
            Optional<StepOverrideRegistry.Match> found = StepOverrideRegistry.match(text);
            if (found.isEmpty()) return Optional.empty();
            StepOverrideRegistry.Match match = found.get();
            return Optional.of(new StepMatch(
                    StepKind.OVERRIDE,
                    text,
                    null,
                    match.captures(),
                    match.rule().id()
            ));
        } catch (IllegalStateException failure) {
            if (failure instanceof StepOverrideRegistry.AmbiguousStepOverrideException) throw failure;
            return Optional.empty();
        }
    }

    private Optional<StepMatch> overrides(String text, List<StepOverrideRule> rules) {
        if (rules == null || rules.isEmpty()) return Optional.empty();
        List<OverrideHit> hits = new ArrayList<>();
        String body = text == null ? "" : text;
        for (StepOverrideRule rule : rules) {
            if (rule == null) continue;
            Matcher matcher = Pattern.compile(rule.pattern()).matcher(body);
            if (!matcher.matches()) continue;
            List<String> groups = new ArrayList<>();
            for (int index = 1; index <= matcher.groupCount(); index++) {
                groups.add(matcher.group(index));
            }
            hits.add(new OverrideHit(rule, List.copyOf(groups)));
        }
        if (hits.isEmpty()) return Optional.empty();
        hits.sort(Comparator.comparing(hit -> hit.rule().id()));
        if (hits.size() > 1) {
            throw new AmbiguousMatchException(body, hits.stream().map(hit -> hit.rule().id()).toList());
        }
        OverrideHit hit = hits.getFirst();
        return Optional.of(new StepMatch(StepKind.OVERRIDE, body, null, hit.captures(), hit.rule().id()));
    }

    private record OverrideHit(StepOverrideRule rule, List<String> captures) {
    }

    private StepMatch matchGlue(String text) {
        return matchSteps(text, steps);
    }

    private StepMatch matchSteps(String text, List<GlueStep> candidates) {
        String body = text == null ? "" : text;
        List<GlueStep> hits = new ArrayList<>();
        List<List<String>> captures = new ArrayList<>();
        for (GlueStep step : candidates) {
            if (!preferredDefinition.isBlank() && !preferredDefinition.equals(step.definitionId())) continue;
            List<String> arguments = PatternMatcher.match(
                    step.pattern(), body, step.parameterTypes(), step.expressionTypes().toArray(Type[]::new));
            if (arguments == null) continue;
            hits.add(step);
            captures.add(arguments);
        }
        if (hits.isEmpty()) {
            EngineLog.log(logProject, "match", "undefined", body);
            return StepMatch.undefined(body);
        }
        if (hits.size() > 1) {
            throw new AmbiguousMatchException(body, hits.stream().map(GlueStep::location).toList());
        }
        GlueStep step = hits.getFirst();
        EngineLog.log(logProject, "match", step.location(), step.kind() + " " + body);
        return new StepMatch(step.kind(), body, step, captures.getFirst(), "");
    }

    private List<GlueStep> nativeCandidates() {
        if (nativeDecided) return nativeSteps;
        nativeDecided = true;
        nativeSteps = loadNative();
        return nativeSteps;
    }

    /** Null means the scanned glue is the catalog. */
    private List<GlueStep> loadNative() {
        if (logProject == null || !StoreConfig.enabled(logProject) || !StorePaths.ready(logProject)) return null;
        try (StoreDatabase database = StoreDatabase.open(logProject)) {
            List<StoreStepCatalog.MatchRow> rows = StoreStepCatalog.matchRows(database.connection());
            if (rows.isEmpty()) return null;
            java.util.Set<String> scanned = scannedClasses();
            java.util.Set<String> storedClasses = new java.util.HashSet<>();
            List<GlueStep> candidates = new ArrayList<>();
            for (StoreStepCatalog.MatchRow row : rows) {
                if (row.enabled() != 1) continue;
                Method method = bind(row);
                String host = row.host() == null || row.host().isBlank() ? "java" : row.host();
                boolean javaHost = "java".equalsIgnoreCase(host);
                if (javaHost && method == null) continue;
                if (catalogLimited && javaHost && !scannedClass(scanned, method, row.sourcePath())) continue;
                if (method != null) storedClasses.add(method.getDeclaringClass().getName());
                String source = className(row.sourcePath());
                if (source != null) storedClasses.add(source);
                String pattern = row.pattern();
                if (javaHost && method != null) {
                    String live = reflectedRegex(method, pattern);
                    if (live != null) pattern = live;
                }
                candidates.add(define("Given", pattern, method, row.parameterTypes(),
                        row.id(), host, row.contentHash()));
            }
            for (GlueStep step : steps) {
                Method method = step.method();
                if (method != null && storedClasses.contains(method.getDeclaringClass().getName())) continue;
                candidates.add(step);
            }
            return candidates;
        } catch (RuntimeException | java.sql.SQLException failure) {
            EngineLog.log(logProject, "match", "store", failure.toString());
            return null;
        }
    }

    private void catalogChanged() {
        nativeDecided = false;
        nativeSteps = null;
    }

    private java.util.Set<String> scannedClasses() {
        java.util.Set<String> scanned = new java.util.HashSet<>();
        for (GlueStep step : steps) {
            if (step.method() != null) scanned.add(step.method().getDeclaringClass().getName());
        }
        for (Hook hook : hooks) {
            if (hook.method() != null) scanned.add(hook.method().getDeclaringClass().getName());
        }
        return scanned;
    }

    private static boolean scannedClass(java.util.Set<String> scanned, Method method, String sourcePath) {
        if (method != null && scanned.contains(method.getDeclaringClass().getName())) return true;
        String source = className(sourcePath);
        return source != null && scanned.contains(source);
    }

    /**
     * Warm runs load consumer glue from {@code .pickleball/spine/classes}.
     * That directory stays off the application classpath so the next scenario
     * can replace the loader and see a recompiled class.
     */
    private static ClassLoader glueLoader(String className) {
        if (tools.dscode.engine.runtime.WarmFlags.enabled()) {
            Path project = ForeignStepHosts.project();
            if (project == null) project = tools.dscode.engine.EngineConfig.project();
            return JavaStepHost.selectLoader(className, project);
        }
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = StepRegistry.class.getClassLoader();
        return loader;
    }

    private static Method bind(StoreStepCatalog.MatchRow row) {
        String className = className(row.sourcePath());
        if (className == null || row.methodName() == null || row.methodName().isBlank()) return null;
        ClassLoader loader = glueLoader(className);
        Class<?> type;
        try {
            type = Class.forName(className, true, loader);
        } catch (Throwable failure) {
            return null;
        }
        List<Method> named = new ArrayList<>();
        Class<?> cursor = type;
        while (cursor != null && cursor != Object.class) {
            for (Method method : cursor.getDeclaredMethods()) {
                if (method.getName().equals(row.methodName()) && !method.isSynthetic() && !method.isBridge()) {
                    named.add(method);
                }
            }
            cursor = cursor.getSuperclass();
        }
        if (named.isEmpty()) return null;
        if (named.size() == 1) return named.getFirst();
        for (Method method : named) {
            for (Annotation annotation : method.getAnnotations()) {
                for (Found found : valuesOf(annotation)) {
                    PatternMatcher.Translation translation = PatternMatcher.translation(found.pattern());
                    if (!translation.rejected() && translation.regex().equals(row.pattern())) return method;
                    if (found.pattern().equals(row.pattern())) return method;
                }
            }
        }
        return null;
    }

    /**
     * A catalog row scanned before a unicode escape was decoded still holds the letter
     * u and four hex digits. The reflected annotation is the character the compiler stored.
     * A Cucumber expression such as {int} is left to ingest.
     */
    private static String reflectedRegex(Method method, String stored) {
        String first = null;
        for (Annotation annotation : method.getAnnotations()) {
            for (Found found : valuesOf(annotation)) {
                String pattern = found.pattern();
                if (pattern == null) continue;
                String trimmed = pattern.stripLeading();
                if (!(trimmed.startsWith("^") || trimmed.startsWith("(?"))) continue;
                if (cucumberParameter(trimmed)) continue;
                if (pattern.equals(stored)) return null;
                if (first == null) first = pattern;
            }
        }
        return first;
    }

    private static boolean cucumberParameter(String pattern) {
        for (int index = 0; index < pattern.length() - 1; index++) {
            if (pattern.charAt(index) == '{' && Character.isLetter(pattern.charAt(index + 1))) return true;
        }
        return false;
    }

    private static String className(String sourcePath) {
        if (sourcePath == null) return null;
        String norm = sourcePath.replace('\\', '/');
        String rel = null;
        int main = norm.indexOf("src/main/java/");
        int test = norm.indexOf("src/test/java/");
        if (main >= 0) rel = norm.substring(main + "src/main/java/".length());
        else if (test >= 0) rel = norm.substring(test + "src/test/java/".length());
        if (rel == null || !rel.endsWith(".java")) return null;
        return rel.substring(0, rel.length() - ".java".length()).replace('/', '.');
    }

    private void scanMethod(Method method) {
        CucumberStepRegistry.observe(this, method);
        for (Annotation annotation : method.getAnnotations()) {
            for (Found found : valuesOf(annotation)) {
                add(define(found.keyword(), found.pattern(), method, ""));
            }
        }
    }

    public List<Hook> hooks(String phase) {
        List<Hook> found = new ArrayList<>();
        for (Hook hook : hooks) {
            if (hook.phase().equals(phase)) found.add(hook);
        }
        found.sort(Comparator.comparingInt(Hook::order));
        if (phase != null && phase.startsWith("after")) Collections.reverse(found);
        return found;
    }

    /** Runs scanned Cucumber hooks on the same glue instance as the steps. */
    public void fire(String phase, List<String> tags, Map<Class<?>, Object> instances) {
        Map<Class<?>, Object> targets = instances == null ? new IdentityHashMap<>() : instances;
        for (Hook hook : hooks(phase)) {
            if (!hook.matches(tags)) continue;
            Method method = hook.method();
            Object target = null;
            if (!Modifier.isStatic(method.getModifiers())) {
                target = targets.computeIfAbsent(method.getDeclaringClass(), type -> {
                    try {
                        return type.getDeclaredConstructor().newInstance();
                    } catch (ReflectiveOperationException failure) {
                        throw new IllegalStateException(failure);
                    }
                });
            }
            method.setAccessible(true);
            try {
                method.invoke(target, GlueHooks.arguments(method));
            } catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException(cause);
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    public record Hook(String phase, int order, String tags, Method method) {
        public boolean matches(List<String> scenarioTags) {
            if (tags == null || tags.isBlank()) return true;
            return CucumberTags.evaluate(tags, scenarioTags);
        }
    }

    private void add(GlueStep step) {
        String key = step.location() + " " + step.pattern();
        for (GlueStep existing : steps) {
            if ((existing.location() + " " + existing.pattern()).equals(key)) return;
        }
        steps.add(step);
        EngineLog.log(logProject, "registry", step.location(), step.keyword() + " " + step.pattern());
    }

    private static GlueStep define(String keyword, String pattern, Method method, String parameterTypes) {
        return define(keyword, pattern, method, parameterTypes, "", "java", "");
    }

    private static GlueStep define(
            String keyword,
            String pattern,
            Method method,
            String parameterTypes,
            String definitionId,
            String host,
            String contentHash
    ) {
        Type[] types = method == null ? new Type[0] : method.getGenericParameterTypes();
        boolean table = false;
        boolean doc = false;
        List<Type> expressionTypes = new ArrayList<>();
        for (int index = 0; index < types.length; index++) {
            Class<?> raw = raw(types[index]);
            String rawName = raw == null ? "" : raw.getName();
            boolean argument = CUCUMBER_TABLE.equals(rawName) || CUCUMBER_DOC.equals(rawName)
                    || raw == Table.class || raw == DocText.class;
            if (argument && index == types.length - 1) {
                table = CUCUMBER_TABLE.equals(rawName) || raw == Table.class;
                doc = CUCUMBER_DOC.equals(rawName) || raw == DocText.class;
                continue;
            }
            expressionTypes.add(types[index]);
        }
        return new GlueStep(
                keyword, pattern, method, expressionTypes, table, doc, classify(pattern, method),
                parameterTypes, definitionId, host, contentHash);
    }

    static StepKind classify(String pattern, Method method) {
        String name = method == null ? "" : method.getName();
        String source = pattern == null ? "" : pattern;
        if (name.equals("executeDynamicStepBlockConditionals") || source.contains("IF:") || source.contains("ELSE-IF:") || source.contains("ELSE:")) {
            return StepKind.BLOCK_CONDITIONAL;
        }
        if (name.equals("executeDynamicStep") || source.startsWith("^,(?s)")) {
            return StepKind.DYNAMIC;
        }
        if (name.startsWith("runScenario") || name.startsWith("runComponent") || source.contains("SCENARIO:") || source.contains("COMPONENT") || source.startsWith("^RUN")) {
            return StepKind.RUN;
        }
        if (source.contains("SERVICE CALL") || source.startsWith("^CALL:") || name.toLowerCase(Locale.ROOT).contains("servicecall")) {
            return StepKind.SERVICE_CALL;
        }
        return StepKind.GLUE;
    }

    private static List<Found> valuesOf(Annotation annotation) {
        if (annotation == null) return List.of();
        Class<? extends Annotation> type = annotation.annotationType();
        try {
            if (stepAnnotation(type)) {
                Object value = type.getMethod("value").invoke(annotation);
                if (value instanceof String pattern) {
                    return List.of(new Found(type.getSimpleName(), pattern));
                }
            }
            Method value = type.getMethod("value");
            Object nested = value.invoke(annotation);
            if (nested instanceof Annotation[] annotations) {
                List<Found> found = new ArrayList<>();
                for (Annotation child : annotations) {
                    found.addAll(valuesOf(child));
                }
                return found;
            }
        } catch (ReflectiveOperationException ignored) {
            return List.of();
        }
        return List.of();
    }

    private static boolean stepAnnotation(Class<? extends Annotation> type) {
        if (type.getName().startsWith("io.cucumber.java.en.")) return true;
        for (Annotation meta : type.getAnnotations()) {
            if (CUCUMBER_STEP_META.equals(meta.annotationType().getName())) return true;
        }
        return false;
    }

    private static Class<?> raw(Type type) {
        if (type instanceof Class<?> clazz) return clazz;
        return Object.class;
    }

    private record Found(String keyword, String pattern) {
    }
}
