package io.cucumber.query;

import io.cucumber.messages.types.Pickle;

import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * Reduces the lineage of a Gherkin document element in descending order.
 *
 * @param <T> type to which the lineage is reduced.
 */
class LineageReducerDescending<T> implements LineageReducer<T> {

    private final Supplier<? extends Collector<T>> collectorSupplier;

    LineageReducerDescending(Supplier<? extends Collector<T>> collectorSupplier) {
        this.collectorSupplier = requireNonNull(collectorSupplier);
    }

    @Override
    public T reduce(Lineage lineage) {
        Collector<T> collector = collectorSupplier.get();
        reduceAddLineage(collector, lineage);
        return collector.finish();
    }

    @Override
    public T reduce(Lineage lineage, Pickle pickle) {
        Collector<T> collector = collectorSupplier.get();
        reduceAddLineage(collector, lineage);
        collector.add(pickle);
        return collector.finish();
    }

    private static <T> void reduceAddLineage(Collector<T> collector, Lineage lineage) {
        collector.add(lineage.document());
        lineage.feature().ifPresent(collector::add);
        lineage.rule().ifPresent(collector::add);
        lineage.scenario().ifPresent(collector::add);
        lineage.examples().ifPresent(examples -> collector.add(examples, lineage.examplesIndex().orElse(0)));
        lineage.example().ifPresent(example -> collector.add(example, lineage.exampleIndex().orElse(0)));
    }
}
