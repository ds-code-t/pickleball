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
        RunnerRuntimeContext ctx = CONTEXTS.computeIfAbsent(key, k -> buildContext(cliArgs));
        return ctx;
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

        // Choose one loader that can see BOTH: consumer test classes and your dep jar.
        ClassLoader fallback = RunnerRuntimeRegistry.class.getClassLoader();
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        final ClassLoader effectiveCl = (tccl != null) ? tccl : fallback;

        // Make sure everyone downstream sees the same loader
        if (tccl == null) {
            Thread.currentThread().setContextClassLoader(effectiveCl);
        }
        java.util.function.Supplier<ClassLoader> clSupplier = () -> effectiveCl;

        // Build with the SAME loader everywhere
        ObjectFactoryServiceLoader objectFactoryLoader =
                new ObjectFactoryServiceLoader(clSupplier, runtimeOptions);
        ObjectFactorySupplier objectFactory =
                new SingletonObjectFactorySupplier(objectFactoryLoader);

        BackendServiceLoader backendSupplier =
                new BackendServiceLoader(clSupplier, objectFactory);

        EventBus bus = new TimeServiceEventBus(Clock.systemUTC(), UUID::randomUUID);

        SingletonRunnerSupplier runnerSupplier =
                new SingletonRunnerSupplier(runtimeOptions, bus, backendSupplier, objectFactory);
        Runner runner = runnerSupplier.get();

        Runtime runtime = Runtime.builder()
                .withRuntimeOptions(runtimeOptions)
                .withClassLoader(clSupplier)  // <-- same CL again
                .build();

        // Optional probe to confirm visibility
        try {
            effectiveCl.loadClass("tools.dscode.coredefinitions.GeneralSteps");
            out.println("@@ probe: coredefinitions visible to loader");
        } catch (ClassNotFoundException e) {
            out.println("@@ probe: coredefinitions NOT visible to loader -> " + e);
        }


        return new RunnerRuntimeContext(runner, runtimeOptions, runtime);
    }

}
