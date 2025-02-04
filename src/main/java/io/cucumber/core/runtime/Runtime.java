/*
 * This file incorporates work covered by the following copyright and permission notice:
 *
 * Copyright (c) Cucumber Ltd
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.cucumber.core.runtime;

import io.cucumber.core.eventbus.EventBus;
import io.cucumber.core.feature.FeatureParser;
import io.cucumber.core.filter.Filters;
import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.logging.Logger;
import io.cucumber.core.logging.LoggerFactory;
import io.cucumber.core.options.RuntimeOptions;
import io.cucumber.core.order.PickleOrder;
import io.cucumber.core.plugin.PluginFactory;
import io.cucumber.core.plugin.Plugins;
import io.cucumber.core.resource.ClassLoaders;
import io.cucumber.plugin.Plugin;

import java.time.Clock;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static io.cucumber.core.runtime.SynchronizedEventBus.synchronize;
import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toList;

/**
 * This is the main entry point for running Cucumber features from the CLI.
 */
public final class Runtime {

    private static final Logger log = LoggerFactory.getLogger(Runtime.class);

    private final ExitStatus exitStatus;

    private final Predicate<Pickle> filter;
    private final int limit;
    public final FeatureSupplier featureSupplier;
    private final ExecutorService executor;
    private final PickleOrder pickleOrder;
    public final CucumberExecutionContext context;


    private Runtime(
            final ExitStatus exitStatus,
            final CucumberExecutionContext context,
            final Predicate<Pickle> filter,
            final int limit,
            final FeatureSupplier featureSupplier,
            final ExecutorService executor,
            final PickleOrder pickleOrder
    ) {
        this.filter = filter;
        this.context = context;
        this.limit = limit;
        this.featureSupplier = featureSupplier;
        this.executor = executor;
        this.exitStatus = exitStatus;
        this.pickleOrder = pickleOrder;
    }

    public static Builder builder() {
        return new Builder();
    }

    public void run() {
        // Parse the features early. Don't proceed when there are lexer errors
        List<Feature> features = featureSupplier.get();
        context.runFeatures(() -> runFeatures(features));
    }

    private void runFeatures(List<Feature> features) {
        features.forEach(context::beforeFeature);
        List<Future<?>> executingPickles = features.stream()
                .flatMap(feature -> feature.getPickles().stream())
                .filter(filter)
                .collect(collectingAndThen(toList(),
                        list -> pickleOrder.orderPickles(list).stream()))
                .limit(limit > 0 ? limit : Integer.MAX_VALUE)
                .map(pickle -> executor.submit(executePickle(pickle)))
//                .map(this::executePickleBasedOnDecision)
                .collect(toList());

        executor.shutdown();

        for (Future<?> executingPickle : executingPickles) {
            try {
                executingPickle.get();
            } catch (ExecutionException e) {
                log.error(e, () -> "Exception while executing pickle");
            } catch (InterruptedException e) {
                log.debug(e, () -> "Interrupted while executing pickle");
                executor.shutdownNow();
            }
        }
    }

    private Runnable executePickle(Pickle pickle) {
        return () -> context.runTestCase(runner -> runner.runPickle(pickle));
    }

    public byte exitStatus() {
        return exitStatus.exitStatus();
    }

    public static class Builder {

        private EventBus eventBus;
        private Supplier<ClassLoader> classLoader = ClassLoaders::getDefaultClassLoader;
        private RuntimeOptions runtimeOptions = RuntimeOptions.defaultOptions();
        private BackendSupplier backendSupplier;
        private FeatureSupplier featureSupplier;
        private List<Plugin> additionalPlugins = emptyList();

        private Builder() {
        }

        public Builder withRuntimeOptions(final RuntimeOptions runtimeOptions) {
            this.runtimeOptions = runtimeOptions;
            return this;
        }

        public Builder withClassLoader(final Supplier<ClassLoader> classLoader) {
            this.classLoader = classLoader;
            return this;
        }

        public Builder withBackendSupplier(final BackendSupplier backendSupplier) {
            this.backendSupplier = backendSupplier;
            return this;
        }

        public Builder withFeatureSupplier(final FeatureSupplier featureSupplier) {
            this.featureSupplier = featureSupplier;
            return this;
        }

        public Builder withAdditionalPlugins(final Plugin... plugins) {
            this.additionalPlugins = Arrays.asList(plugins);
            return this;
        }

        public Builder withEventBus(final EventBus eventBus) {
            this.eventBus = eventBus;
            return this;
        }

        public Runtime build() {
            final ObjectFactoryServiceLoader objectFactoryServiceLoader = new ObjectFactoryServiceLoader(classLoader,
                    runtimeOptions);

            final ObjectFactorySupplier objectFactorySupplier = runtimeOptions.isMultiThreaded()
                    ? new ThreadLocalObjectFactorySupplier(objectFactoryServiceLoader)
                    : new SingletonObjectFactorySupplier(objectFactoryServiceLoader);

            final BackendSupplier backendSupplier = this.backendSupplier != null
                    ? this.backendSupplier
                    : new BackendServiceLoader(this.classLoader, objectFactorySupplier);

            final Plugins plugins = new Plugins(new PluginFactory(), runtimeOptions);
            for (final Plugin plugin : additionalPlugins) {
                plugins.addPlugin(plugin);
            }
            final ExitStatus exitStatus = new ExitStatus(runtimeOptions);
            plugins.addPlugin(exitStatus);

            if (this.eventBus == null) {
                final UuidGeneratorServiceLoader uuidGeneratorServiceLoader = new UuidGeneratorServiceLoader(
                        classLoader,
                        runtimeOptions);
                this.eventBus = new TimeServiceEventBus(Clock.systemUTC(),
                        uuidGeneratorServiceLoader.loadUuidGenerator());
            }
            final EventBus eventBus = synchronize(this.eventBus);

            if (runtimeOptions.isMultiThreaded()) {
                plugins.setSerialEventBusOnEventListenerPlugins(eventBus);
            } else {
                plugins.setEventBusOnEventListenerPlugins(eventBus);
            }

            final RunnerSupplier runnerSupplier = runtimeOptions.isMultiThreaded()
                    ? new ThreadLocalRunnerSupplier(runtimeOptions, eventBus, backendSupplier, objectFactorySupplier)
                    : new SingletonRunnerSupplier(runtimeOptions, eventBus, backendSupplier, objectFactorySupplier);

            final ExecutorService executor = runtimeOptions.isMultiThreaded()
                    ? Executors.newFixedThreadPool(runtimeOptions.getThreads(), new CucumberThreadFactory())
                    : new SameThreadExecutorService();

            final FeatureParser parser = new FeatureParser(eventBus::generateId);

            final FeatureSupplier featureSupplier = this.featureSupplier != null
                    ? this.featureSupplier
                    : new FeaturePathFeatureSupplier(classLoader, runtimeOptions, parser);

            final Predicate<Pickle> filter = new Filters(runtimeOptions);
            final int limit = runtimeOptions.getLimitCount();
            final PickleOrder pickleOrder = runtimeOptions.getPickleOrder();
            final CucumberExecutionContext context = new CucumberExecutionContext(eventBus, exitStatus, runnerSupplier);

            return new Runtime(exitStatus, context, filter, limit, featureSupplier, executor, pickleOrder);
        }

    }

    private static final class CucumberThreadFactory implements ThreadFactory {

        private static final AtomicInteger poolNumber = new AtomicInteger(1);
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;

        CucumberThreadFactory() {
            this.namePrefix = "cucumber-runner-" + poolNumber.getAndIncrement() + "-thread-";
        }

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, namePrefix + this.threadNumber.getAndIncrement());
        }

    }

    private static final class SameThreadExecutorService extends AbstractExecutorService {

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
            // no-op
        }

        @Override
        public List<Runnable> shutdownNow() {
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return true;
        }

        @Override
        public boolean isTerminated() {
            return true;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

    }

}
