package tools.dscode.engine.adapter;

import io.cucumber.core.feature.FeatureWithLines;
import io.cucumber.core.feature.GluePath;
import io.cucumber.core.options.RuntimeOptionsBuilder;
import io.cucumber.core.runtime.Runtime;
import io.cucumber.plugin.EventListener;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.PickleStepTestStep;
import io.cucumber.plugin.event.TestCaseFinished;
import io.cucumber.plugin.event.TestCaseStarted;
import io.cucumber.plugin.event.TestStep;
import io.cucumber.plugin.event.TestStepFinished;
import tools.dscode.common.reporting.logging.Level;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.ScenarioResult;
import tools.dscode.engine.model.Status;
import tools.dscode.engine.model.StepResult;
import tools.dscode.testengine.PickleballRunner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Runs one feature through Cucumber. Used only by the adapter and the parity harness. */
public final class CucumberAdapterRun {
    private static final StringBuilder RECORD = new StringBuilder();

    private CucumberAdapterRun() {
    }

    public static RunResult run(Path featureFile, String gluePackage) {
        synchronized (RECORD) {
            RECORD.setLength(0);
            Runtime runtime = Runtime.builder()
                    .withRuntimeOptions(new RuntimeOptionsBuilder()
                            .addFeature(FeatureWithLines.parse(featureFile.toAbsolutePath().toString()))
                            .addGlue(GluePath.parse(gluePackage))
                            .setNoSummary()
                            .build())
                    .withAdditionalPlugins((EventListener) publisher -> {
                        publisher.registerHandlerFor(TestCaseStarted.class, event -> {
                            append("CASE\t" + escape(event.getTestCase().getName()));
                            for (String text : plannedTexts(event.getTestCase())) {
                                if (!internal(text)) append("PLAN\t" + escape(text));
                            }
                        });
                        publisher.registerHandlerFor(TestStepFinished.class, event -> {
                            String text = stepText(event.getTestStep());
                            if (text == null || internal(text)) return;
                            append("STEP\t" + event.getResult().getStatus().name() + "\t" + escape(text));
                        });
                        publisher.registerHandlerFor(TestCaseFinished.class, event ->
                                append("END\t" + event.getResult().getStatus().name()));
                    })
                    .build();
            // A previous suite may leave PickleballRunner.LOG_LEVEL at ERROR.
            // Passed steps then set noLogging and emit no finish event, so the
            // harness would only see the internal root step. Restore after.
            Level previous = PickleballRunner.LOG_LEVEL;
            boolean quiet = previous != null && previous.ordinal() > Level.INFO.ordinal();
            if (quiet) {
                PickleballRunner.LOG_LEVEL = Level.INFO;
            }
            try {
                runtime.run();
                return parse(RECORD.toString());
            } finally {
                if (quiet) {
                    PickleballRunner.LOG_LEVEL = previous;
                }
            }
        }
    }

    private static List<String> plannedTexts(Object testCase) {
        List<String> texts = new ArrayList<>();
        try {
            Object value = testCase.getClass().getField("stepExtensions").get(testCase);
            if (value instanceof List<?> extensions) {
                for (Object extension : extensions) {
                    Object text = extension.getClass().getMethod("getUnmodifiedText").invoke(extension);
                    if (text != null && !text.toString().isBlank()) texts.add(text.toString());
                }
            }
        } catch (ReflectiveOperationException ignored) {
            texts.clear();
        }
        if (!texts.isEmpty()) return texts;
        if (testCase instanceof io.cucumber.plugin.event.TestCase cucumberCase) {
            for (TestStep step : cucumberCase.getTestSteps()) {
                String text = stepText(step);
                if (text != null) texts.add(text);
            }
        }
        return texts;
    }

    private static boolean internal(String text) {
        return text.contains("-ROOT-STEP-");
    }

    private static void append(String line) {
        RECORD.append(line).append('\n');
    }

    private static RunResult parse(String record) {
        List<ScenarioResult> scenarios = new ArrayList<>();
        String name = "";
        Status status = Status.PASSED;
        List<StepResult> planned = new ArrayList<>();
        List<StepResult> seen = new ArrayList<>();
        boolean open = false;
        for (String line : record.split("\n", -1)) {
            if (line.isEmpty()) continue;
            int tab = line.indexOf('\t');
            String kind = tab < 0 ? line : line.substring(0, tab);
            String rest = tab < 0 ? "" : line.substring(tab + 1);
            if ("CASE".equals(kind)) {
                if (open) scenarios.add(close(name, status, planned, seen));
                name = unescape(rest);
                status = Status.PASSED;
                planned = new ArrayList<>();
                seen = new ArrayList<>();
                open = true;
            } else if ("PLAN".equals(kind) && open) {
                planned.add(StepResult.of(Status.PENDING, unescape(rest), "planned"));
            } else if ("STEP".equals(kind) && open) {
                int split = rest.indexOf('\t');
                String stepStatus = split < 0 ? rest : rest.substring(0, split);
                String text = split < 0 ? "" : unescape(rest.substring(split + 1));
                seen.add(new StepResult(map(stepStatus), "", text, "", 0, ""));
            } else if ("END".equals(kind) && open) {
                status = map(rest);
                scenarios.add(close(name, status, planned, seen));
                open = false;
                planned = new ArrayList<>();
                seen = new ArrayList<>();
            }
        }
        if (open) scenarios.add(close(name, status, planned, seen));
        return RunResult.of(scenarios);
    }

    /**
     * Passed steps often emit no Cucumber finish event when the global log
     * level is above INFO. The runner also replaces the Cucumber step list
     * with an internal root step. Planned text comes from the original step
     * extensions. Finish events are used when they cover those steps.
     */
    private static ScenarioResult close(String name, Status status, List<StepResult> planned, List<StepResult> seen) {
        List<StepResult> steps;
        if (seen.size() >= planned.size()) {
            steps = seen;
        } else if (seen.isEmpty()) {
            Status fill = status == Status.PASSED ? Status.PASSED : status == Status.FAILED && planned.size() == 1 ? Status.FAILED : Status.SKIPPED;
            steps = new ArrayList<>();
            for (StepResult plan : planned) {
                steps.add(StepResult.of(fill, plan.text(), ""));
            }
        } else {
            steps = new ArrayList<>();
            int cursor = 0;
            for (StepResult plan : planned) {
                if (cursor < seen.size() && seen.get(cursor).text().equals(plan.text())) {
                    steps.add(seen.get(cursor));
                    cursor++;
                } else {
                    steps.add(StepResult.of(Status.PASSED, plan.text(), "gap"));
                }
            }
            while (cursor < seen.size()) {
                steps.add(seen.get(cursor));
                cursor++;
            }
            int failAt = -1;
            for (int index = 0; index < steps.size(); index++) {
                Status stepStatus = steps.get(index).status();
                if (!"gap".equals(steps.get(index).message())
                        && (stepStatus == Status.FAILED || stepStatus == Status.UNDEFINED || stepStatus == Status.AMBIGUOUS)) {
                    failAt = index;
                    break;
                }
            }
            if (failAt < 0 && status != Status.PASSED) {
                failAt = steps.size();
            }
            List<StepResult> filled = new ArrayList<>();
            for (int index = 0; index < steps.size(); index++) {
                StepResult step = steps.get(index);
                if (!"gap".equals(step.message())) {
                    filled.add(step);
                } else if (status == Status.PASSED) {
                    filled.add(StepResult.of(Status.PASSED, step.text(), ""));
                } else if (failAt < 0 || index < failAt) {
                    filled.add(StepResult.of(Status.PASSED, step.text(), ""));
                } else {
                    filled.add(StepResult.of(Status.SKIPPED, step.text(), ""));
                }
            }
            steps = filled;
        }
        return new ScenarioResult(status, name, steps, "");
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t");
    }

    private static String unescape(String value) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char one = value.charAt(index);
            if (one == '\\' && index + 1 < value.length()) {
                char next = value.charAt(++index);
                out.append(next == 'n' ? '\n' : next == 't' ? '\t' : next);
            } else {
                out.append(one);
            }
        }
        return out.toString();
    }

    private static String stepText(TestStep step) {
        if (step instanceof PickleStepTestStep pickle) return pickle.getStepText();
        try {
            Object value = step.getClass().getMethod("getStepText").invoke(step);
            return value == null ? null : value.toString();
        } catch (ReflectiveOperationException failure) {
            return null;
        }
    }

    private static Status map(String name) {
        try {
            return Status.valueOf(name);
        } catch (IllegalArgumentException failure) {
            return Status.FAILED;
        }
    }
}
