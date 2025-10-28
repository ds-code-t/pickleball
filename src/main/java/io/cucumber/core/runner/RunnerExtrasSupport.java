package io.cucumber.core.runner;

import io.cucumber.core.eventbus.EventBus;
import io.cucumber.core.filter.Filters;
import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.options.CommandlineOptionsParser;
import io.cucumber.core.options.RuntimeOptions;
import io.cucumber.core.order.PickleOrder;
import io.cucumber.core.runtime.BackendServiceLoader;
import io.cucumber.core.runtime.CucumberExecutionContext;
import io.cucumber.core.runtime.FeatureSupplier;
import io.cucumber.core.runtime.ObjectFactoryServiceLoader;
import io.cucumber.core.runtime.ObjectFactorySupplier;
import io.cucumber.core.runtime.Runtime;
import io.cucumber.core.runtime.SingletonObjectFactorySupplier;
import io.cucumber.core.runtime.SingletonRunnerSupplier;
import io.cucumber.core.runtime.TimeServiceEventBus;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import static java.lang.System.out;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toList;

/**
 * Thread-safe utilities for creating/reusing Runner instances keyed by normalized CLI args.
 * Also exposes helpers to load Features, collect Pickles, create TestCases, and create matched steps.
 *
 * NOTE: Runner implements {@link RunnerExtras} via AspectJ ITD; call instance methods via
 * ((RunnerExtras) (Object) runner) to satisfy javac (Runner is final; weaving happens after compilation).
 */
public final class RunnerExtrasSupport {

    private RunnerExtrasSupport() {}

    /** Public map, as requested. Key = normalized args; Value = Runner + its RuntimeOptions. */
    public static final ConcurrentHashMap<String, RunnerContext> RUNNERS = new ConcurrentHashMap<>();

    /** Holds a Runner and the RuntimeOptions used to create it. */
    public static final class RunnerContext {
        public final Runner runner;
        public final RuntimeOptions runtimeOptions;
        RunnerContext(Runner runner, RuntimeOptions runtimeOptions) {
            this.runner = runner;
            this.runtimeOptions = runtimeOptions;
        }
    }

    /* ────────────────────────────────────────── Keying / Normalization ────────────────────────────────────────── */

    /** Simple normalization: trim each arg, drop empties, join with a single space. */
    public static String normalizeArgs(String... cliArgs) {
        return String.join(" ",
                Arrays.stream(cliArgs)
                        .map(s -> s == null ? "" : s.trim())
                        .filter(s -> !s.isEmpty())
                        .toList()
        );
    }

    /* ────────────────────────────────────────── Runner lifecycle ─────────────────────────────────────────────── */

    /** Returns existing Runner for these args or creates one atomically if absent. */
    public static Runner getOrInitRunner(String... cliArgs) {
        String key = normalizeArgs(cliArgs);
        return RUNNERS.computeIfAbsent(key, k -> buildContext(cliArgs)).runner;
    }

    /** Returns the RuntimeOptions tied to the Runner for these args. */
    public static RuntimeOptions getRuntimeOptions(String... cliArgs) {
        String key = normalizeArgs(cliArgs);
        return RUNNERS.computeIfAbsent(key, k -> buildContext(cliArgs)).runtimeOptions;
    }

    /** Remove a cached Runner by args (useful when you want to rebuild). */
    public static void removeRunner(String... cliArgs) {
        RUNNERS.remove(normalizeArgs(cliArgs));
    }

    /** Clear all cached Runners. */
    public static void resetAll() {
        RUNNERS.clear();
    }

    private static RunnerContext buildContext(String... cliArgs) {
        CommandlineOptionsParser parser = new CommandlineOptionsParser(out);
        RuntimeOptions runtimeOptions = parser.parse(cliArgs).build();

        ObjectFactoryServiceLoader objFactoryLoader =
                new ObjectFactoryServiceLoader(SingletonRunnerSupplier.class::getClassLoader, runtimeOptions);
        ObjectFactorySupplier objectFactory = new SingletonObjectFactorySupplier(objFactoryLoader);

        BackendServiceLoader backendSupplier =
                new BackendServiceLoader(RunnerExtrasSupport.class::getClassLoader, objectFactory);

        EventBus bus = new TimeServiceEventBus(Clock.systemUTC(), UUID::randomUUID);
        SingletonRunnerSupplier runnerSupplier =
                new SingletonRunnerSupplier(runtimeOptions, bus, backendSupplier, objectFactory);

        Runner runner = runnerSupplier.get();
        return new RunnerContext(runner, runtimeOptions);
    }

    /* ────────────────────────────────────────── Feature / Pickle helpers ─────────────────────────────────────── */

    /** Load features using the RuntimeOptions for these args (same behavior as Runtime would). */
    public static List<Feature> loadFeatures(String... cliArgs) {
        RuntimeOptions runtimeOptions = getRuntimeOptions(cliArgs);

        Runtime runtime = Runtime.builder()
                .withRuntimeOptions(runtimeOptions)
                .withClassLoader(() -> Thread.currentThread().getContextClassLoader())
                .build();

        FeatureSupplier featureSupplier = (FeatureSupplier)
                tools.dscode.common.util.Reflect.getProperty(runtime, "featureSupplier");

        return featureSupplier.get();
    }

    /** Filter and order pickles according to current RuntimeOptions for these args. */
    public static List<Pickle> collectPickles(List<Feature> features, String... cliArgs) {
        RuntimeOptions runtimeOptions = getRuntimeOptions(cliArgs);
        Predicate<Pickle> filter = new Filters(runtimeOptions);
        int limit = runtimeOptions.getLimitCount();
        PickleOrder order = runtimeOptions.getPickleOrder();

        return features.stream()
                .flatMap(f -> f.getPickles().stream())
                .filter(filter)
                .collect(collectingAndThen(toList(), list -> order.orderPickles(list).stream()))
                .limit(limit > 0 ? limit : Integer.MAX_VALUE)
                .toList();
    }

    /* ────────────────────────────────────────── TestCase / Step helpers ──────────────────────────────────────── */

    /** Create a TestCase via the woven ITD on Runner (requires your Runner_AddCreateTestCase aspect). */
    public static TestCase createTestCase(Pickle pickle, String... cliArgs) {
        Runner runner = getOrInitRunner(cliArgs);
        return ((RunnerExtras) (Object) runner).createTestCase(pickle);
    }

    /**
     * Create a fully matched PickleStepTestStep for the given pickle and step text,
     * via the woven helper on Runner (Runner_AddCreateStepForText aspect).
     */
    public static PickleStepTestStep createStepFromText(Pickle pickle, String stepText, String... cliArgs) {
        Runner runner = getOrInitRunner(cliArgs);
        return ((RunnerExtras) (Object) runner).createStepForText(pickle, stepText);
    }


}
