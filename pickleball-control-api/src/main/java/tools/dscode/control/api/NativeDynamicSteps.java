package tools.dscode.control.api;

import tools.dscode.engine.EngineLog;
import tools.dscode.engine.EngineOptions;
import tools.dscode.engine.PickleballEngine;
import tools.dscode.engine.context.ExecutionContext;
import tools.dscode.engine.model.EngineScenario;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.Status;
import tools.dscode.engine.match.StepKind;
import tools.dscode.engine.match.StepMatch;
import tools.dscode.engine.match.StepRegistry;
import tools.dscode.engine.model.StepResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs one step when no Cucumber scenario is on the thread.
 * A live scenario never reaches this class.
 */
public final class NativeDynamicSteps {
    private static final Pattern KEYWORD = Pattern.compile("^(?:Given|When|Then|And|But|\\*)\\s+(.*)$", Pattern.DOTALL);
    private static final CopyOnWriteArrayList<Class<?>> GLUE = new CopyOnWriteArrayList<>();

    private NativeDynamicSteps() {
    }

    /** Extra glue for a process that has no Cucumber suite. Class names in {@code pkb_engine_glue} are also scanned. */
    public static void useGlue(Class<?>... types) {
        if (types == null) return;
        for (Class<?> type : types) {
            if (type != null) GLUE.addIfAbsent(type);
        }
    }

    public static ControlCallResult<Object> execute(String text, String argument) {
        List<Class<?>> glue = new ArrayList<>(GLUE);
        String property = System.getProperty("pkb_engine_glue");
        if (property != null && !property.isBlank()) {
            for (String name : property.split(",")) {
                String trimmed = name.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    Class<?> type = Class.forName(trimmed);
                    if (!glue.contains(type)) glue.add(type);
                } catch (ClassNotFoundException failure) {
                    return ControlCallResult.failed(failure);
                }
            }
        }
        String body = strip(text);
        StepMatch match = registry(glue).match(body);
        if (match.kind() == StepKind.UNDEFINED) {
            return ControlCallResult.unavailable("'" + body + "' step is undefined");
        }
        try (ExecutionContext context = ExecutionContext.open(EngineScenario.of("dynamic", List.of()))) {
            context.resource("glue", glue);
            String doc = argument == null || argument.isBlank() ? null : argument;
            StepResult result = PickleballEngine.runStep(body, doc, context);
            return map(result);
        }
    }

    /** Runs a whole feature from text. No feature file and no Cucumber scenario. */
    public static ControlCallResult<Object> executeScenario(String feature, String scenarioName) {
        List<Class<?>> glue = new ArrayList<>(GLUE);
        String property = System.getProperty("pkb_engine_glue");
        if (property != null && !property.isBlank()) {
            for (String name : property.split(",")) {
                String trimmed = name.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    Class<?> type = Class.forName(trimmed);
                    if (!glue.contains(type)) glue.add(type);
                } catch (ClassNotFoundException failure) {
                    return ControlCallResult.failed(failure);
                }
            }
        }
        try {
            EngineOptions options = EngineOptions.of(glue.toArray(Class<?>[]::new));
            if (scenarioName != null && !scenarioName.isBlank()) options = options.scenario(scenarioName);
            RunResult result = PickleballEngine.run(feature == null ? "" : feature, options);
            EngineLog.log(options.project(), "control", "scenario", result.status().name());
            if (result.status() == Status.PASSED || result.status() == Status.SKIPPED) {
                return ControlCallResult.success(result);
            }
            String message = result.message() == null || result.message().isBlank()
                    ? result.status().name()
                    : result.message();
            if (result.status() == Status.UNDEFINED || result.status() == Status.PENDING) {
                return ControlCallResult.unavailable(message);
            }
            return ControlCallResult.failed(new IllegalStateException(message));
        } catch (RuntimeException failure) {
            return ControlCallResult.failed(failure);
        }
    }

    private static StepRegistry registry(List<Class<?>> glue) {
        StepRegistry created = new StepRegistry().scanPackage("tools.dscode.coredefinitions");
        if (glue != null && !glue.isEmpty()) created.scan(glue.toArray(Class<?>[]::new));
        return created;
    }

    private static String strip(String text) {
        String raw = text == null ? "" : text.strip();
        Matcher matcher = KEYWORD.matcher(raw);
        return matcher.matches() ? matcher.group(1) : raw;
    }

    private static ControlCallResult<Object> map(StepResult result) {
        if (result == null) return ControlCallResult.unavailable("Native engine returned no step result.");
        if (result.status() == Status.PASSED || result.status() == Status.SKIPPED) {
            String returned = result.returned();
            return ControlCallResult.success(returned == null || returned.isEmpty() ? null : returned);
        }
        if (result.status() == Status.UNDEFINED || result.status() == Status.PENDING) {
            String message = result.message() == null || result.message().isBlank() ? "undefined" : result.message();
            return ControlCallResult.unavailable(message);
        }
        String message = result.message() == null || result.message().isBlank() ? result.status().name() : result.message();
        return ControlCallResult.failed(new IllegalStateException(message));
    }
}
