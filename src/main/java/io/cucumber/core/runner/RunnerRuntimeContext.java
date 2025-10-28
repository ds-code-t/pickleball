package io.cucumber.core.runner;

import io.cucumber.core.filter.Filters;
import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.order.PickleOrder;
import io.cucumber.core.runtime.FeatureSupplier;
import io.cucumber.core.options.RuntimeOptions;

import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toList;

/**
 * Per-key context holding the trio (Runner, RuntimeOptions, Runtime) and
 * providing handy instance methods to load features, collect/filter pickles,
 * and create TestCases (via the woven RunnerExtras ITD).
 */
public final class RunnerRuntimeContext {
    public final Runner runner;
    public final RuntimeOptions runtimeOptions;
    public final io.cucumber.core.runtime.Runtime runtime;

    public RunnerRuntimeContext(Runner runner,
                                RuntimeOptions runtimeOptions,
                                io.cucumber.core.runtime.Runtime runtime) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.runtimeOptions = Objects.requireNonNull(runtimeOptions, "runtimeOptions");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /* ─────────────────────────── Features / Pickles ─────────────────────────── */

    /** Load features using this context's RuntimeOptions (same as Cucumber Runtime would). */
    public List<Feature> loadFeatures() {
        FeatureSupplier featureSupplier = (FeatureSupplier) readPrivate(runtime, "featureSupplier");
        return featureSupplier.get();
    }

    /** Filter + order pickles using this context’s RuntimeOptions. */
    public List<Pickle> collectPickles() {
        List<Feature> features = loadFeatures();
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

    /** All pickles whose name equals (case-sensitive) the given name. */
    public List<Pickle> picklesByName(String name) {
        return collectPickles().stream()
                .filter(p -> Objects.equals(p.getName(), name))
                .toList();
    }

    /** First pickle matching a Java regex against the name. */
    public Optional<Pickle> firstPickleMatchingNameRegex(String regex) {
        return collectPickles().stream()
                .filter(p -> p.getName() != null && p.getName().matches(regex))
                .findFirst();
    }

    /** First pickle containing ALL of the given tags (e.g., {"@smoke","@fast"}). */
    public Optional<Pickle> firstPickleWithAllTags(String... tags) {
        return collectPickles().stream()
                .filter(p -> p.getTags().containsAll(List.of(tags)))
                .findFirst();
    }

    /** First pickle (after Cucumber’s ordering) – useful when limit/tag filters already applied by options. */
    public Optional<Pickle> firstPickle() {
        return collectPickles().stream().findFirst();
    }

    /** Pickles sorted by (feature URI, line). */
    public List<Pickle> picklesSorted() {
        return collectPickles().stream()
                .sorted(Comparator
                        .comparing((Pickle p) -> p.getUri().toString())
                        .thenComparing(p -> p.getLocation().getLine()))
                .toList();
    }

    /* ───────────────────────────── TestCase helpers ─────────────────────────── */

    /**
     * Create a TestCase for a given Pickle via the woven ITD on Runner.
     * Note: cast goes through Object so javac doesn't reject (Runner is final pre-weave).
     */
    public TestCase createTestCase(Pickle pickle) {
        return ((RunnerExtras) (Object) runner).createTestCase(pickle);
    }

    /** Create TestCases for all collected pickles (order & filters from RuntimeOptions apply). */
    public List<TestCase> createTestCases() {
        return collectPickles().stream()
                .map(this::createTestCase)
                .toList();
    }

    /** Try to create a TestCase by exact name match (first match). */
    public Optional<TestCase> createTestCaseByName(String name) {
        return picklesByName(name).stream().findFirst().map(this::createTestCase);
    }

    /** Try to create a TestCase for the first pickle that has all given tags. */
    public Optional<TestCase> createTestCaseWithAllTags(String... tags) {
        return firstPickleWithAllTags(tags).map(this::createTestCase);
    }

    /* ─────────────────────────── internals / reflection ─────────────────────── */

    private static Object readPrivate(Object target, String fieldName) {
        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to read private field '" + fieldName + "' from " + target, e);
        }
    }
}
