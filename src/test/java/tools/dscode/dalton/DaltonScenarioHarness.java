package tools.dscode.dalton;

import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.GlobalState;
import io.cucumber.core.runtime.FeaturePathLoadCache;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.EngineFilter;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import tools.dscode.testengine.DynamicSuiteEngine;
import tools.dscode.testengine.PickleballRunner;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs features through the consumer JUnit engine ({@code DynamicSuiteEngine}),
 * the same entry a consumer {@code mvn test} uses. Does not change the Dalton
 * flag or reset {@link DaltonRuntime}, so a caller can hydrate once and then
 * edit the database without a second hydrate overwriting that edit.
 */
public final class DaltonScenarioHarness {
    private DaltonScenarioHarness() {
    }

    public record Outcome(List<String> tests, List<String> events, List<String> reached) {
        public String report() {
            return "tests=" + tests + " events=" + events + " reached=" + reached;
        }
    }

    public static Outcome execute(Path features, String glue, String tag) {
        String location = "wd-file:" + features.toAbsolutePath().normalize();
        System.setProperty("pkb_features", location);
        System.setProperty("cucumber.features", location);
        System.setProperty("pkb_glue", glue);
        System.setProperty("cucumber.glue", glue);
        System.setProperty("pkb_tags", tag);
        System.setProperty("cucumber.filter.tags", tag);
        System.setProperty("pkb_loglevel", "error");
        System.setProperty("cucumber.execution.parallel.enabled", "false");
        System.setProperty("cucumber.plugin", DaltonRunProbe.class.getName());
        System.setProperty("junit.platform.discovery.includeEngines", DynamicSuiteEngine.ENGINE_ID);
        System.clearProperty("pkb_name");
        System.clearProperty("cucumber.filter.name");
        System.clearProperty("pkb_example");
        DaltonRunProbe.reset();
        FeaturePathLoadCache.clear();
        release();
        releaseStickyGlobalRunner();

        List<String> tests = new ArrayList<>();
        try {
            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(DiscoverySelectors.selectDirectory(features.toString()))
                    .filters(EngineFilter.includeEngines(DynamicSuiteEngine.ENGINE_ID))
                    .configurationParameter("cucumber.plugin", DaltonRunProbe.class.getName())
                    .configurationParameter("cucumber.glue", glue)
                    .configurationParameter("cucumber.filter.tags", tag)
                    .configurationParameter("cucumber.execution.parallel.enabled", "false")
                    .build();
            LauncherFactory.create().execute(request, new TestExecutionListener() {
                @Override
                public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
                    if (!identifier.isTest()) return;
                    String message = result.getThrowable().map(error -> {
                        StringBuilder text = new StringBuilder();
                        Throwable current = error;
                        while (current != null) {
                            text.append(current.getClass().getSimpleName())
                                    .append(": ")
                                    .append(current.getMessage())
                                    .append(" || ");
                            current = current.getCause();
                        }
                        return text.toString();
                    }).orElse("");
                    tests.add(result.getStatus() + " " + identifier.getDisplayName() + " " + message);
                }
            });
            return new Outcome(List.copyOf(tests), List.copyOf(DaltonRunProbe.EVENTS), List.copyOf(DaltonRunProbe.REACHED));
        } finally {
            CurrentScenarioState.currentScenarioState.remove();
        }
    }

    public static void release() {
        try {
            Field field = PickleballRunner.class.getDeclaredField("INSTANCE");
            field.setAccessible(true);
            field.set(null, null);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * The first scenario in this JVM keeps {@link GlobalState#globalRunner}.
     * Nested steps publish on that runner's bus, so a later in-process run
     * would miss them while its own plugin bus still sees the root step.
     * A warm {@code dalton run} is a fresh JVM and does not have that split.
     */
    private static void releaseStickyGlobalRunner() {
        synchronized (GlobalState.class) {
            GlobalState.globalRunner = null;
            try {
                Field glue = GlobalState.class.getDeclaredField("globalCachingGlue");
                glue.setAccessible(true);
                glue.set(null, null);
                Field prepared = GlobalState.class.getDeclaredField("gluePrepared");
                prepared.setAccessible(true);
                prepared.setBoolean(null, false);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }
}
