package tools.dscode.control.override;

import tools.dscode.testengine.DynamicSuiteBootstrap;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StepOverrideCompiler {
    public static final String CLASS_NAME_TOKEN = "{{CLASS_NAME}}";
    private static final int MAX_GENERATED_HANDLERS = 256;
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;");

    private StepOverrideCompiler() {
    }

    public static boolean compilerAvailable() {
        return ToolProvider.getSystemJavaCompiler() != null;
    }

    public static StepOverrideRule compile(
            String scenarioId,
            String ruleId,
            StepOverridePatternType patternType,
            String pattern,
            String sourceTemplate
    ) {
        return compileWith(
                ToolProvider.getSystemJavaCompiler(),
                scenarioId, ruleId, patternType, pattern, sourceTemplate
        );
    }

    static StepOverrideRule compileWith(
            JavaCompiler compiler,
            String scenarioId,
            String ruleId,
            StepOverridePatternType patternType,
            String pattern,
            String sourceTemplate
    ) {
        if (compiler == null) {
            throw new CompilerUnavailableException(
                    "Step Override Java compilation requires a JDK with javax.tools.JavaCompiler."
            );
        }
        if (sourceTemplate == null || !sourceTemplate.contains(CLASS_NAME_TOKEN)) {
            throw new IllegalArgumentException(
                    "Step Override source must contain the " + CLASS_NAME_TOKEN + " class-name token."
            );
        }

        int sequence = SEQUENCE.incrementAndGet();
        if (sequence > MAX_GENERATED_HANDLERS) {
            throw new IllegalStateException(
                    "This worker has reached the Step Override generated-handler limit of "
                            + MAX_GENERATED_HANDLERS + ". Restart the worker for a clean override runtime."
            );
        }

        String className = "PkbStepOverride_" + safeIdentifier(ruleId) + "_" + sequence;
        String source = sourceTemplate.replace(CLASS_NAME_TOKEN, className);
        String packageName = packageName(source);
        String fqcn = packageName.isBlank() ? className : packageName + "." + className;

        Path live = liveRoot();
        Path sourceRoot = live.resolve("generated-java");
        Path classesRoot = live.resolve("generated-classes");
        Path sourceFile = sourceRoot.resolve(className + ".java");

        try {
            Files.createDirectories(sourceRoot);
            Files.createDirectories(classesRoot);
            Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not write generated Step Override source.", failure);
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files =
                     compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units =
                    files.getJavaFileObjectsFromPaths(List.of(sourceFile));
            List<String> options = List.of(
                    "--release", "21",
                    "-classpath", System.getProperty("java.class.path"),
                    "-d", classesRoot.toString()
            );
            Boolean successful = compiler.getTask(
                    null, files, diagnostics, options, null, units
            ).call();
            if (!Boolean.TRUE.equals(successful)) {
                throw new CompilationException(diagnosticText(diagnostics));
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Could not close Step Override compiler resources.", failure);
        }

        try {
            URLClassLoader loader = new URLClassLoader(
                    new URL[]{classesRoot.toUri().toURL()},
                    Thread.currentThread().getContextClassLoader()
            );
            Class<?> generated = Class.forName(fqcn, true, loader);
            Object instance = generated.getDeclaredConstructor().newInstance();
            if (!(instance instanceof StepOverrideHandler handler)) {
                loader.close();
                throw new IllegalArgumentException(
                        "Generated Step Override class must implement "
                                + StepOverrideHandler.class.getName() + "."
                );
            }

            StepOverrideRule rule = new StepOverrideRule(
                    ruleId,
                    patternType,
                    pattern,
                    new LoadedHandler(handler, loader, fqcn)
            );
            StepOverrideRegistry.register(scenarioId, rule);
            return rule;
        } catch (ReflectiveOperationException | IOException failure) {
            throw new IllegalStateException(
                    "Could not load generated Step Override class " + fqcn + ".",
                    failure
            );
        }
    }

    public static String handlerClassName(StepOverrideRule rule) {
        if (rule != null && rule.handler() instanceof LoadedHandler loaded) {
            return loaded.className;
        }
        return rule == null ? null : rule.handler().getClass().getName();
    }

    private static Path liveRoot() {
        String liveClasses = System.getProperty(
                DynamicSuiteBootstrap.WORKBENCH_TEST_OUTPUT_ROOT_PROPERTY
        );
        if (liveClasses != null && !liveClasses.isBlank()) {
            Path classes = Path.of(liveClasses).toAbsolutePath().normalize();
            Path parent = classes.getParent();
            if (parent != null) return parent;
        }
        return Path.of(
                System.getProperty("java.io.tmpdir"),
                "pickleball-step-overrides",
                Long.toString(ProcessHandle.current().pid())
        );
    }

    private static String packageName(String source) {
        Matcher matcher = PACKAGE_PATTERN.matcher(source);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String safeIdentifier(String value) {
        String normalized = value == null ? "rule" : value.replaceAll("[^A-Za-z0-9_$]", "_");
        if (normalized.isBlank()) normalized = "rule";
        if (!Character.isJavaIdentifierStart(normalized.charAt(0))) normalized = "_" + normalized;
        return normalized;
    }

    private static String diagnosticText(DiagnosticCollector<JavaFileObject> diagnostics) {
        StringBuilder text = new StringBuilder("Step Override Java compilation failed.");
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            text.append(System.lineSeparator())
                    .append("line ").append(diagnostic.getLineNumber())
                    .append(": ").append(diagnostic.getMessage(Locale.ROOT));
        }
        return text.toString();
    }

    private static final class LoadedHandler implements StepOverrideHandler, AutoCloseable {
        private final StepOverrideHandler delegate;
        private final URLClassLoader loader;
        private final String className;

        private LoadedHandler(
                StepOverrideHandler delegate,
                URLClassLoader loader,
                String className
        ) {
            this.delegate = delegate;
            this.loader = loader;
            this.className = className;
        }

        @Override
        public Object execute(StepOverrideContext context) throws Exception {
            return delegate.execute(context);
        }

        @Override
        public void close() throws IOException {
            loader.close();
        }
    }

    public static final class CompilerUnavailableException extends IllegalStateException {
        public CompilerUnavailableException(String message) {
            super(message);
        }
    }

    public static final class CompilationException extends IllegalArgumentException {
        public CompilationException(String message) {
            super(message);
        }
    }
}
