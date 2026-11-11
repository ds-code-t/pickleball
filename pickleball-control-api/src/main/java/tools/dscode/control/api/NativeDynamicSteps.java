package tools.dscode.control.api;

import tools.dscode.engine.PickleballEngine;
import tools.dscode.engine.context.ExecutionContext;
import tools.dscode.engine.model.EngineScenario;
import tools.dscode.engine.model.Status;
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
        try (ExecutionContext context = ExecutionContext.open(EngineScenario.of("dynamic", List.of()))) {
            context.resource("glue", glue);
            String doc = argument == null || argument.isBlank() ? null : argument;
            StepResult result = PickleballEngine.runStep(body, doc, context);
            return map(result);
        }
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
