package tools.dscode.launcher;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.EngineFilter;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import tools.dscode.common.reporting.diagnostic.ConsumerMavenTestRunner;
import tools.dscode.store.StoreConfig;
import tools.dscode.engine.adapter.cucumber.StoreRunTracePlugin;
import tools.dscode.testengine.DynamicSuiteEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the consumer suite on an already-resolved classpath. No Maven and no
 * Gradle. The warm JVM calls this once per command inside a fresh class loader.
 */
public final class WorkbenchDirectRun {
    private WorkbenchDirectRun() {
    }

    public static void main(String[] args) {
        int exit = run(Path.of(""), args);
        if (exit != 0) {
            System.exit(exit);
        }
    }

    /** Applies {@code -D} arguments, then one engine launch. Returns the process exit. */
    public static int run(Path project, List<String> dashD) {
        int[] code = {1};
        isolate(dashD, () -> code[0] = launch(project));
        return code[0];
    }

    /**
     * Applies one run's {@code -D} properties and puts the previous {@code pkb_} and
     * {@code cucumber.} values back when {@code body} returns. An in-process warm
     * serve must not leave {@code pkb_run_id} for the next test in this JVM.
     */
    static void isolate(List<String> dashD, Runnable body) {
        Map<String, String> savedRun = prefixed("pkb_");
        Map<String, String> savedCucumber = prefixed("cucumber.");
        try {
            apply(dashD);
            if (body != null) body.run();
        } finally {
            replacePrefix("pkb_", savedRun);
            replacePrefix("cucumber.", savedCucumber);
        }
    }

    private static int launch(Path project) {
        Path root = project == null ? Path.of("").toAbsolutePath().normalize() : project.toAbsolutePath().normalize();
        Path tests = root.resolve("target").resolve("test-classes");
        if (!Files.isDirectory(tests)) {
            tests = root.resolve("build").resolve("classes").resolve("java").resolve("test");
        }
        try {
            LauncherDiscoveryRequestBuilder requestBuilder = LauncherDiscoveryRequestBuilder.request()
                    .selectors(DiscoverySelectors.selectDirectory(tests.toString()))
                    .filters(EngineFilter.includeEngines(DynamicSuiteEngine.ENGINE_ID));
            boolean trace = StoreConfig.truthy(System.getProperty(StoreRunTracePlugin.ENABLED));
            boolean record = false;
            try {
                record = StoreConfig.enabled() && !tools.dscode.engine.EngineConfig.nativeSelected();
            } catch (RuntimeException ignored) {
                record = false;
            }
            if (trace || record) {
                requestBuilder.configurationParameter(
                        io.cucumber.core.options.Constants.PLUGIN_PROPERTY_NAME,
                        StoreRunTracePlugin.withPlugin(null));
            }
            LauncherDiscoveryRequest request = requestBuilder.build();
            SummaryGeneratingListener summary = new SummaryGeneratingListener();
            LauncherFactory.create().execute(request, summary);
            long found = summary.getSummary().getTestsFoundCount();
            long started = summary.getSummary().getTestsStartedCount();
            long failed = summary.getSummary().getTestsFailedCount();
            // A tag or name that matches nothing is an empty plan. A native run
            // that leaves discovered scenarios unstarted is the same miss.
            if (found == 0 || started == 0) return 1;
            return failed == 0 ? 0 : 1;
        } catch (RuntimeException failure) {
            System.err.println("error: direct launch failed: " + failure.getMessage());
            System.err.println("fix: the wrapper path still runs this command.");
            return 1;
        }
    }

    public static int run(Path project, String[] args) {
        return run(project, args == null ? List.of() : List.of(args));
    }

    /** {@code -Dkey=value} tokens from a wrapper command. Other tokens are ignored. */
    public static List<String> dashD(List<String> command) {
        List<String> found = new ArrayList<>();
        if (command == null) return found;
        for (String token : command) {
            if (token != null && token.startsWith("-D") && token.length() > 2) found.add(token);
        }
        if (found.stream().noneMatch(token -> token.startsWith("-Djunit.platform.discovery.includeEngines="))) {
            found.add(ConsumerMavenTestRunner.PLATFORM_CUCUMBER_ENGINE);
        }
        return found;
    }

    private static void apply(List<String> dashD) {
        clearPrefix("pkb_");
        clearPrefix("cucumber.");
        for (String token : dashD) {
            if (token == null || !token.startsWith("-D") || token.length() < 3) continue;
            String body = token.substring(2);
            int eq = body.indexOf('=');
            String key = eq < 0 ? body : body.substring(0, eq);
            String value = eq < 0 ? "" : body.substring(eq + 1);
            if (key.isBlank()) continue;
            System.setProperty(key, value);
        }
    }

    private static void clearPrefix(String prefix) {
        for (Object key : List.copyOf(System.getProperties().keySet())) {
            if (key instanceof String name && name.startsWith(prefix)) System.clearProperty(name);
        }
    }

    private static Map<String, String> prefixed(String prefix) {
        Map<String, String> saved = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : System.getProperties().entrySet()) {
            if (entry.getKey() instanceof String name && name.startsWith(prefix) && entry.getValue() != null) {
                saved.put(name, entry.getValue().toString());
            }
        }
        return saved;
    }

    private static void replacePrefix(String prefix, Map<String, String> saved) {
        clearPrefix(prefix);
        if (saved == null) return;
        for (Map.Entry<String, String> entry : saved.entrySet()) {
            System.setProperty(entry.getKey(), entry.getValue());
        }
    }
}
