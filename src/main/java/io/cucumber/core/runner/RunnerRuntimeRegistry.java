package io.cucumber.core.runner;

import io.cucumber.core.eventbus.EventBus;
import io.cucumber.core.options.CommandlineOptionsParser;
import io.cucumber.core.options.RuntimeOptions;
import io.cucumber.core.runtime.BackendServiceLoader;
import io.cucumber.core.runtime.ObjectFactoryServiceLoader;
import io.cucumber.core.runtime.ObjectFactorySupplier;
import io.cucumber.core.runtime.Runtime;
import io.cucumber.core.runtime.SingletonObjectFactorySupplier;
import io.cucumber.core.runtime.SingletonRunnerSupplier;
import io.cucumber.core.runtime.TimeServiceEventBus;

import java.time.Clock;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

import static java.lang.System.out;

/**
 * Global, thread-safe registry of (Runner, RuntimeOptions, Runtime) contexts,
 * keyed by a normalized CLI-args string. Computes on demand and reuses thereafter.
 */
public final class RunnerRuntimeRegistry {

    private RunnerRuntimeRegistry() {}

    /** Public, thread-safe map of contexts keyed by normalized args. */
    public static final ConcurrentHashMap<String, RunnerRuntimeContext> CONTEXTS = new ConcurrentHashMap<>();

    /** Normalize CLI args: trim, drop empties, join with single spaces (order preserved). */
    public static String normalizeArgs(String... cliArgs) {
        if (cliArgs == null) return "";
        return String.join(" ",
                Arrays.stream(cliArgs)
                        .map(s -> s == null ? "" : s.trim())
                        .filter(s -> !s.isEmpty())
                        .toList()
        );
    }

    /** Get existing or atomically create a new context for these args. */
    public static RunnerRuntimeContext getOrInit(String... cliArgs) {
        String key = normalizeArgs(cliArgs);
        return CONTEXTS.computeIfAbsent(key, k -> buildContext(cliArgs));
    }

    /** Convenience accessors. */
    public static Runner getRunner(String... cliArgs) {
        return getOrInit(cliArgs).runner;
    }

    public static RuntimeOptions getRuntimeOptions(String... cliArgs) {
        return getOrInit(cliArgs).runtimeOptions;
    }

    public static io.cucumber.core.runtime.Runtime getRuntime(String... cliArgs) {
        return getOrInit(cliArgs).runtime;
    }

    /** Remove a single cached context. */
    public static void remove(String... cliArgs) {
        CONTEXTS.remove(normalizeArgs(cliArgs));
    }

    /** Clear the entire cache. */
    public static void reset() {
        CONTEXTS.clear();
    }

    /* ───────────────────────── internal construction ───────────────────────── */

    private static RunnerRuntimeContext buildContext(String... cliArgs) {
        // 1) Parse CLI → RuntimeOptions
        CommandlineOptionsParser parser = new CommandlineOptionsParser(out);
        RuntimeOptions runtimeOptions = parser.parse(cliArgs == null ? new String[0] : cliArgs).build();

        // 2) ObjectFactory / Backend
        ObjectFactoryServiceLoader objFactoryLoader =
                new ObjectFactoryServiceLoader(SingletonRunnerSupplier.class::getClassLoader, runtimeOptions);
        ObjectFactorySupplier objectFactory = new SingletonObjectFactorySupplier(objFactoryLoader);

        BackendServiceLoader backendSupplier =
                new BackendServiceLoader(RunnerRuntimeRegistry.class::getClassLoader, objectFactory);

        // 3) Bus
        EventBus bus = new TimeServiceEventBus(Clock.systemUTC(), UUID::randomUUID);

        // 4) Runner (via standard supplier)
        SingletonRunnerSupplier runnerSupplier =
                new SingletonRunnerSupplier(runtimeOptions, bus, backendSupplier, objectFactory);
        Runner runner = runnerSupplier.get();

        // 5) Runtime (independent, but configured with same options)
        io.cucumber.core.runtime.Runtime runtime = io.cucumber.core.runtime.Runtime.builder()
                .withRuntimeOptions(runtimeOptions)
                .withClassLoader(() -> Thread.currentThread().getContextClassLoader())
                .build();

        return new RunnerRuntimeContext(runner, runtimeOptions, runtime);
    }
}
