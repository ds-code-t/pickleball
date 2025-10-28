package io.cucumber.core.runner;

import io.cucumber.core.eventbus.EventBus;
import io.cucumber.core.filter.Filters;
import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.options.RuntimeOptions;
import io.cucumber.core.order.PickleOrder;
import io.cucumber.core.runtime.CucumberExecutionContext;
import io.cucumber.core.runtime.FeatureSupplier;
import io.cucumber.plugin.event.TestCase;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static tools.dscode.common.util.Reflect.getProperty;

/**
 * Rich functional API attached to a particular Runner / RuntimeOptions / Runtime trio.
 * All helpers reuse the cached environment stored in RunnerRuntimeRegistry.
 */
public final class RunnerRuntimeContext {

    public final Runner runner;
    public final RuntimeOptions runtimeOptions;
    public final io.cucumber.core.runtime.Runtime runtime;

    RunnerRuntimeContext(
            Runner runner,
            RuntimeOptions runtimeOptions,
            io.cucumber.core.runtime.Runtime runtime
    ) {
        this.runner = runner;
        this.runtimeOptions = runtimeOptions;
        this.runtime = runtime;
    }

    // ───────────────────────── Features ─────────────────────────

    public List<Feature> loadFeatures() {
        FeatureSupplier fs = (FeatureSupplier) getProperty(runtime, "featureSupplier");
        return fs.get();
    }

    // ───────────────────────── Pickles ─────────────────────────

    public List<Pickle> collectPickles() {
        List<Feature> features = loadFeatures();
        Predicate<Pickle> filter = new Filters(runtimeOptions);
        int limit = runtimeOptions.getLimitCount();
        PickleOrder order = runtimeOptions.getPickleOrder();

        return features.stream()
                .flatMap(f -> f.getPickles().stream())
                .filter(filter)
                .collect(Collectors.collectingAndThen(
                        Collectors.toList(),
                        list -> order.orderPickles(list).stream()
                ))
                .limit(limit > 0 ? limit : Integer.MAX_VALUE)
                .toList();
    }

    // ───────────────────────── Woven Runner helpers ─────────────────────────
    // REMINDER: Runner implements RunnerExtras via AspectJ ITD.
    // We must cast through Object for javac, final class.

    private RunnerExtras extras() {
        return (RunnerExtras) (Object) runner;
    }

    public PickleStepTestStep createStepFromText(Pickle pickle, String stepText) {
        return extras().createStepForText(pickle, stepText);
    }

    public TestCase createTestCase(Pickle pickle) {
        return extras().createTestCase(pickle);
    }

    // Shorthand: get first matching pickle
    public Pickle firstPickle() {
        return collectPickles().stream().findFirst().orElse(null);
    }
}
