package tools.dscode.engine.adapter;

import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.ExecutionRequest;
import tools.dscode.engine.EngineOptions;
import tools.dscode.engine.PickleballEngine;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.Status;
import tools.dscode.engine.source.FileScenarioSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * JUnit entry used only when {@code pkb_engine=native}.
 * The Cucumber delegate remains the path for every other run.
 */
public final class NativeSuiteExecution {
    private NativeSuiteExecution() {
    }

    public static void execute(ExecutionRequest request) {
        EngineExecutionListener listener = request.getEngineExecutionListener();
        TestDescriptor root = request.getRootTestDescriptor();
        listener.executionStarted(root);
        Throwable failure = null;
        try {
            String features = request.getConfigurationParameters()
                    .get("cucumber.features")
                    .orElse("")
                    .trim();
            if (features.isBlank()) {
                throw new IllegalStateException("pkb_engine=native requires cucumber.features. The Cucumber adapter remains the default.");
            }
            List<RunResult> results = new ArrayList<>();
            for (String token : features.split(",")) {
                String path = token.trim();
                if (path.isEmpty()) continue;
                results.add(PickleballEngine.run(new FileScenarioSource(Path.of(path)), EngineOptions.of()));
            }
            if (results.isEmpty()) {
                throw new IllegalStateException("pkb_engine=native did not find a feature path.");
            }
            for (RunResult result : results) {
                if (result.status() != Status.PASSED && result.status() != Status.SKIPPED) {
                    failure = new IllegalStateException(result.status() + " " + result.message());
                    break;
                }
            }
        } catch (Throwable error) {
            failure = error;
        }
        TestExecutionResult outcome = failure == null
                ? TestExecutionResult.successful()
                : TestExecutionResult.failed(failure);
        finish(listener, root, outcome);
    }

    private static void finish(EngineExecutionListener listener, TestDescriptor node, TestExecutionResult outcome) {
        for (TestDescriptor child : node.getChildren()) {
            listener.executionStarted(child);
            finish(listener, child, outcome);
        }
        listener.executionFinished(node, outcome);
    }
}
