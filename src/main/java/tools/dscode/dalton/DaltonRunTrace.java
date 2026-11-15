package tools.dscode.dalton;

import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.PickleStepTestStep;
import io.cucumber.plugin.event.TestCase;
import io.cucumber.plugin.event.TestCaseFinished;
import io.cucumber.plugin.event.TestStepFinished;
import io.cucumber.plugin.event.TestStepStarted;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Scenario and step lines for a warm {@code dalton run}. The worker prints
 * these after the exit code. A normal engine run is unchanged unless
 * {@code pkb_dalton_run_trace=true}.
 */
public final class DaltonRunTrace implements ConcurrentEventListener {
    public static final String ENABLED = "pkb_dalton_run_trace";
    public static final String FILE = "pkb_dalton_run_trace_file";

    public DaltonRunTrace() {
    }

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        publisher.registerHandlerFor(TestCaseFinished.class, event -> {
            append("scenario " + event.getResult().getStatus() + " " + event.getTestCase().getName());
            DaltonExecution.finishScenario(String.valueOf(event.getResult().getStatus()));
        });
        publisher.registerHandlerFor(TestStepStarted.class, event -> {
            if (event.getTestStep() instanceof PickleStepTestStep step && step.getStep() != null) {
                DaltonExecution.beforeStep(
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
                if (DaltonRunContext.current() != null) {
                    DaltonExecution.afterStep(String.valueOf(event.getResult().getStatus()));
                } else {
                    DaltonExecution.finishUnopenedStep(
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

    static void append(String line) {
        write(line);
    }

    private static void write(String line) {
        String path = System.getProperty(FILE);
        if (path == null || path.isBlank() || line == null) return;
        try {
            Path file = Path.of(path);
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            synchronized (DaltonRunTrace.class) {
                Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Exception ignored) {
            // A trace file must not change the scenario result.
        }
    }
}
