package io.cucumber.core.runner;

import io.cucumber.core.eventbus.EventBus;
import io.cucumber.core.options.CommandlineOptionsParser;
import io.cucumber.core.options.RuntimeOptions;
import io.cucumber.core.runtime.*;
import io.cucumber.core.runtime.Runtime;

import java.time.Clock;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import static java.lang.System.out;

/**
 * Global, thread-safe registry of Cucumber Runners and their supporting runtime components.
 */
public final class RunnerRuntimeRegistry {

    private RunnerRuntimeRegistry() {}

    public static final ConcurrentHashMap<String, RunnerRuntimeContext> CONTEXTS = new ConcurrentHashMap<>();

    public static String normalizeArgs(String... cliArgs) {
        if (cliArgs == null) return "";
        return String.join(" ",
                Arrays.stream(cliArgs)
                        .map(s -> s == null ? "" : s.trim())
                        .filter(s -> !s.isEmpty())
                        .toList()
        );
    }

    public static RunnerRuntimeContext getOrInit(String... cliArgs) {
        String key = normalizeArgs(cliArgs);
        return CONTEXTS.computeIfAbsent(key, k -> buildContext(cliArgs));
    }

    public static void remove(String... cliArgs) {
        CONTEXTS.remove(normalizeArgs(cliArgs));
    }

    public static void reset() {
        CONTEXTS.clear();
    }

    private static RunnerRuntimeContext buildContext(String... cliArgs) {
        CommandlineOptionsParser parser = new CommandlineOptionsParser(out);
        RuntimeOptions runtimeOptions =
                parser.parse(cliArgs == null ? new String[0] : cliArgs).build();

        ObjectFactoryServiceLoader objectFactoryLoader =
                new ObjectFactoryServiceLoader(SingletonRunnerSupplier.class::getClassLoader, runtimeOptions);
        ObjectFactorySupplier objectFactory =
                new SingletonObjectFactorySupplier(objectFactoryLoader);

        BackendServiceLoader backendSupplier =
                new BackendServiceLoader(RunnerRuntimeRegistry.class::getClassLoader, objectFactory);

        EventBus bus = new TimeServiceEventBus(Clock.systemUTC(), UUID::randomUUID);

        SingletonRunnerSupplier runnerSupplier =
                new SingletonRunnerSupplier(runtimeOptions, bus, backendSupplier, objectFactory);
        Runner runner = runnerSupplier.get();

        Runtime runtime = Runtime.builder()
                .withRuntimeOptions(runtimeOptions)
                .withClassLoader(() -> Thread.currentThread().getContextClassLoader())
                .build();

        return new RunnerRuntimeContext(runner, runtimeOptions, runtime);
    }
}
