package tools.dscode.engine.adapter.cucumber;

import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.PickleStepTestStep;
import io.cucumber.plugin.event.TestCase;
import io.cucumber.plugin.event.TestCaseFinished;
import io.cucumber.plugin.event.TestStepFinished;
import io.cucumber.plugin.event.TestStepStarted;
import tools.dscode.store.StoreConfig;
import tools.dscode.store.StoreExecution;
import tools.dscode.store.StoreRunContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Records scenario and step executions when Store is on, and writes the warm
 * {@code store run} outcome lines when {@code pkb_store_run_trace_file} is set.
 * Store off does not register this listener. An explicit falsy {@code pkb_store_track}
 * still writes nothing.
 */
public final class StoreRunTracePlugin implements ConcurrentEventListener {
    public static final String ENABLED = "pkb_store_run_trace";
    public static final String FILE = "pkb_store_run_trace_file";

    public StoreRunTracePlugin() {
    }

    /** Appends this recorder to a cucumber plugin list when Store is on. Store off leaves the list unchanged. */
    public static String withPlugin(String existing) {
        try {
            if (!StoreConfig.enabled()) return existing;
        } catch (RuntimeException failure) {
            return existing;
        }
        String name = StoreRunTracePlugin.class.getName();
        if (existing == null || existing.isBlank()) return name;
        for (String part : existing.split(",")) {
            String token = part.trim();
            if (name.equals(token) || token.startsWith(name + ":")) return existing;
        }
        return existing + "," + name;
    }

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        publisher.registerHandlerFor(TestCaseFinished.class, event -> {
            append("scenario " + event.getResult().getStatus() + " " + event.getTestCase().getName());
            StoreExecution.finishScenario(String.valueOf(event.getResult().getStatus()));
        });
        publisher.registerHandlerFor(TestStepStarted.class, event -> {
            if (event.getTestStep() instanceof PickleStepTestStep step && step.getStep() != null) {
                StoreExecution.beforeStep(
                        null,
                        event.getTestCase().getName(),
                        uri(event.getTestCase()),
                        line(event.getTestCase()),
                        step.getStep().getText(),
                        lineOf(step.getStep()));
            }
        });
        publisher.registerHandlerFor(TestStepFinished.class, event -> {
            if (event.getTestStep() instanceof PickleStepTestStep step && step.getStep() != null) {
                append("step " + event.getResult().getStatus() + " " + step.getStep().getText());
                if (event.getResult().getError() != null) {
                    StoreExecution.noteFailure(event.getResult().getError());
                }
                if (StoreRunContext.current() != null) {
                    StoreExecution.afterStep(String.valueOf(event.getResult().getStatus()));
                } else {
                    StoreExecution.finishUnopenedStep(
                            null,
                            event.getTestCase().getName(),
                            uri(event.getTestCase()),
                            line(event.getTestCase()),
                            step.getStep().getText(),
                            lineOf(step.getStep()),
                            String.valueOf(event.getResult().getStatus()));
                }
            }
        });
    }

    private static String uri(TestCase testCase) {
        if (testCase == null || testCase.getUri() == null) return "";
        return testCase.getUri().toString();
    }

    private static int line(TestCase testCase) {
        if (testCase == null) return 0;
        return lineOf(testCase);
    }

    private static int lineOf(Object target) {
        if (target == null) return 0;
        try {
            Object line = target.getClass().getMethod("getLine").invoke(target);
            if (line instanceof Number number && number.intValue() > 0) return number.intValue();
        } catch (ReflectiveOperationException ignored) {
            // Step and test-case types differ by engine.
        }
        try {
            Object location = target.getClass().getMethod("getLocation").invoke(target);
            if (location != null) {
                Object line = location.getClass().getMethod("getLine").invoke(location);
                if (line instanceof Number number) return number.intValue();
            }
        } catch (ReflectiveOperationException ignored) {
            return 0;
        }
        return 0;
    }

    public static void append(String line) {
        write(line);
    }

    private static void write(String line) {
        String path = System.getProperty(FILE);
        if (path == null || path.isBlank() || line == null) return;
        try {
            Path file = Path.of(path);
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            synchronized (StoreRunTracePlugin.class) {
                Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Exception ignored) {
            // A trace file must not change the scenario result.
        }
    }
}
