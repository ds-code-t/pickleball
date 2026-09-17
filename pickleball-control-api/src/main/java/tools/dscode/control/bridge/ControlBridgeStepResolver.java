package tools.dscode.control.bridge;

import io.cucumber.core.runner.CucumberStepInvoker;
import tools.dscode.control.api.DynamicControl;
import tools.dscode.control.api.DynamicStepSpec;
import tools.dscode.control.override.StepOverrideCompiler;
import tools.dscode.control.override.StepOverrideRegistry;
import tools.dscode.control.override.StepOverrideRule;
import tools.dscode.control.protocol.ControlBridgeStepResolution;

import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Resolves one Gherkin step to a Java definition, Pickleball dynamic step, or
 * Step Override without executing it.
 */
final class ControlBridgeStepResolver {
    private static final String FRAMEWORK_PACKAGE = "tools.dscode";
    private static final List<String> SOURCE_ROOTS = List.of("src/test/java", "src/main/java");

    private ControlBridgeStepResolver() {
    }

    static ControlBridgeStepResolution resolve(String text, String argument) {
        try {
            DynamicStepSpec matching = DynamicControl.matchingStep(text, argument);
            String stepText = matching.text();
            Optional<StepOverrideRegistry.Match> override = StepOverrideRegistry.match(stepText);
            if (override.isPresent()) {
                return fromOverride(override.get());
            }
            Optional<CucumberStepInvoker.GlueDefinition> glue =
                    CucumberStepInvoker.findGlueDefinition(stepText);
            if (glue.isPresent()) {
                return fromMethod(glue.get().method(), glue.get().pattern());
            }
            return ControlBridgeStepResolution.unmatched(
                    "No step definition or Step Override matched '" + stepText + "'."
            );
        } catch (Throwable failure) {
            return ControlBridgeStepResolution.unmatched(safeMessage(failure));
        }
    }

    static ControlBridgeStepResolution fromMethod(Method method, String pattern) {
        String className = method == null ? "" : method.getDeclaringClass().getName();
        String methodName = method == null ? "" : method.getName();
        boolean dynamic = isFrameworkClass(className);
        String sourcePath = dynamic || method == null
                ? ""
                : findSourcePath(method.getDeclaringClass());
        return new ControlBridgeStepResolution(
                dynamic ? ControlBridgeStepResolution.DYNAMIC : ControlBridgeStepResolution.CONSUMER_GLUE,
                pattern == null ? "" : pattern,
                className,
                methodName,
                sourcePath,
                snippet(method, pattern),
                dynamic ? "Pickleball framework/dynamic step." : ""
        );
    }

    static ControlBridgeStepResolution fromOverride(StepOverrideRegistry.Match match) {
        StepOverrideRule rule = match.rule();
        String className = Objects.toString(StepOverrideCompiler.handlerClassName(rule), "");
        return new ControlBridgeStepResolution(
                ControlBridgeStepResolution.OVERRIDE,
                rule.pattern(),
                className,
                "execute",
                "",
                "Step Override '" + rule.id() + "' " + rule.pattern(),
                "Matched Step Override '" + rule.id() + "'."
        );
    }

    static boolean isFrameworkClass(String className) {
        return className != null
                && (className.equals(FRAMEWORK_PACKAGE) || className.startsWith(FRAMEWORK_PACKAGE + "."));
    }

    static String snippet(Method method, String pattern) {
        String signature = "";
        if (method != null) {
            String params = Arrays.stream(method.getParameterTypes())
                    .map(Class::getSimpleName)
                    .collect(Collectors.joining(", "));
            signature = method.getDeclaringClass().getName() + "." + method.getName() + "(" + params + ")";
        }
        String trimmedPattern = pattern == null ? "" : pattern.trim();
        if (signature.isBlank()) return trimmedPattern;
        if (trimmedPattern.isBlank()) return signature;
        return signature + "  " + trimmedPattern;
    }

    static String findSourcePath(Class<?> type) {
        if (type == null) return "";
        String relative = javaRelativePath(type);
        Path userDir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (String root : SOURCE_ROOTS) {
            Path candidate = userDir.resolve(root).resolve(relative);
            if (Files.isRegularFile(candidate)) return candidate.toString();
        }
        Path fromCodeSource = fromCodeSource(type, relative);
        return fromCodeSource == null ? "" : fromCodeSource.toString();
    }

    static String javaRelativePath(Class<?> type) {
        String name = type.getName();
        int dollar = name.indexOf('$');
        if (dollar >= 0) name = name.substring(0, dollar);
        return name.replace('.', '/') + ".java";
    }

    private static Path fromCodeSource(Class<?> type, String relative) {
        try {
            var source = type.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) return null;
            URI locationUri = source.getLocation().toURI();
            if (!"file".equalsIgnoreCase(locationUri.getScheme())) return null;
            Path location = Path.of(locationUri).toAbsolutePath().normalize();
            if (!Files.isDirectory(location)) return null;
            Path mapped = mapClassesDirectoryToSources(location);
            if (mapped == null) return null;
            Path candidate = mapped.resolve(relative);
            return Files.isRegularFile(candidate) ? candidate : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Path mapClassesDirectoryToSources(Path classesDir) {
        String path = classesDir.toString().replace('\\', '/');
        if (path.endsWith("/target/test-classes")) {
            return classesDir.getParent().getParent().resolve("src/test/java");
        }
        if (path.endsWith("/target/classes")) {
            return classesDir.getParent().getParent().resolve("src/main/java");
        }
        if (path.endsWith("/build/classes/java/test")) {
            return classesDir.resolve("../../../../src/test/java").normalize();
        }
        if (path.endsWith("/build/classes/java/main")) {
            return classesDir.resolve("../../../../src/main/java").normalize();
        }
        Path cursor = classesDir;
        for (int i = 0; i < 8 && cursor != null; i++, cursor = cursor.getParent()) {
            Path test = cursor.resolve("src/test/java");
            if (Files.isDirectory(test)) return test;
            Path main = cursor.resolve("src/main/java");
            if (Files.isDirectory(main)) return main;
        }
        return null;
    }

    private static String safeMessage(Throwable failure) {
        if (failure == null) return "Step resolution failed.";
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getName() : message;
    }
}
