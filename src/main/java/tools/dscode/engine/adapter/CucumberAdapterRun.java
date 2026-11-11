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
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.ScenarioResult;
import tools.dscode.engine.model.Status;
import tools.dscode.engine.model.StepResult;

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
                        publisher.registerHandlerFor(TestCaseStarted.class, event ->
                                append("CASE\t" + escape(event.getTestCase().getName())));
                        publisher.registerHandlerFor(TestStepFinished.class, event -> {
                            String text = stepText(event.getTestStep());
                            if (text == null) return;
                            append("STEP\t" + event.getResult().getStatus().name() + "\t" + escape(text));
                        });
                        publisher.registerHandlerFor(TestCaseFinished.class, event ->
                                append("END\t" + event.getResult().getStatus().name()));
                    })
                    .build();
            runtime.run();
            return parse(RECORD.toString());
        }
    }

    private static void append(String line) {
        RECORD.append(line).append('\n');
    }

    private static RunResult parse(String record) {
        List<ScenarioResult> scenarios = new ArrayList<>();
        String name = "";
        Status status = Status.PASSED;
        List<StepResult> steps = new ArrayList<>();
        boolean open = false;
        for (String line : record.split("\n", -1)) {
            if (line.isEmpty()) continue;
            int tab = line.indexOf('\t');
            String kind = tab < 0 ? line : line.substring(0, tab);
            String rest = tab < 0 ? "" : line.substring(tab + 1);
            if ("CASE".equals(kind)) {
                if (open) scenarios.add(new ScenarioResult(status, name, steps, ""));
                name = unescape(rest);
                status = Status.PASSED;
                steps = new ArrayList<>();
                open = true;
            } else if ("STEP".equals(kind) && open) {
                int split = rest.indexOf('\t');
                String stepStatus = split < 0 ? rest : rest.substring(0, split);
                String text = split < 0 ? "" : unescape(rest.substring(split + 1));
                steps.add(new StepResult(map(stepStatus), "", text, "", 0, ""));
            } else if ("END".equals(kind) && open) {
                status = map(rest);
                scenarios.add(new ScenarioResult(status, name, steps, ""));
                open = false;
                steps = new ArrayList<>();
            }
        }
        if (open) scenarios.add(new ScenarioResult(status, name, steps, ""));
        return RunResult.of(scenarios);
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
