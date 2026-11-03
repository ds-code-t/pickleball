package tools.dscode.testengine.parity;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.EngineFilter;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import tools.dscode.testengine.DynamicSuiteEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Normal launch of the launch-parity scenario. The engine constructs the runner.
 * The step writes the resolved profile. This is not a dry-run.
 */
public final class LaunchObservationMain {
    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: LaunchObservationMain <output-file>");
            System.exit(2);
        }
        Path out = Path.of(args[0]);
        System.setProperty("junit.platform.discovery.includeEngines", DynamicSuiteEngine.ENGINE_ID);
        System.setProperty("cucumber.execution.parallel.enabled", "false");
        List<String> finished = new ArrayList<>();
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectClasspathResource("launch-parity"))
                .filters(EngineFilter.includeEngines(DynamicSuiteEngine.ENGINE_ID))
                .build();
        LauncherFactory.create().execute(request, new TestExecutionListener() {
            @Override
            public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult result) {
                if (testIdentifier.isTest()) {
                    finished.add(testIdentifier.getDisplayName() + " " + result.getStatus()
                            + (result.getThrowable().map(error -> " " + error).orElse("")));
                }
            }
        });
        if (!Files.isRegularFile(out)) {
            System.err.println("parity output missing: " + out);
            System.err.println(finished);
            System.exit(1);
        }
        boolean failed = finished.stream().anyMatch(line -> !line.contains("SUCCESSFUL"));
        if (finished.isEmpty() || failed) {
            System.err.println("parity scenario did not pass: " + finished);
            System.exit(1);
        }
    }
}
