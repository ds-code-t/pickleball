package io.pickleball.executions;

import io.cucumber.core.filter.Filters;
import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.gherkin.messages.GherkinMessagesPickle;
import io.cucumber.core.options.CommandlineOptionsParser;
import io.cucumber.core.options.RuntimeOptions;
import io.cucumber.core.order.PickleOrder;
import io.pickleball.mapandStateutilities.LinkedMultiMap;
import io.cucumber.core.runner.TestCase;

import java.io.IOException;
import java.net.URI;
import java.util.List;

import java.util.function.Predicate;
import java.util.stream.Collectors;

import static io.pickleball.cacheandstate.GlobalCache.getFeaturePathFeatureSupplier;
//import static io.pickleball.cacheandstate.GlobalCache.getGlobalRunner;
import static io.pickleball.cacheandstate.PrimaryScenarioData.getRunner;
import static io.pickleball.cucumberutilities.SourceParser.getComponentScenarioWrapper;

public final class ComponentRuntime {

    public static  List<TestCase> createTestcases(String[] args,  LinkedMultiMap map) {
        RuntimeOptions runtimeOptions = ComponentRuntime.buildOptions(args);
        List<Feature> features = getFeaturePathFeatureSupplier().get(runtimeOptions);
        List<Pickle> pickles = filterPicklesFromFeatures(features, runtimeOptions);
        return pickles.stream().map(pickle -> getRunner().createTestCaseForPickle(pickle, map)).toList();
    }

    public static RuntimeOptions buildOptions(String[] argv) {
        CommandlineOptionsParser commandlineOptionsParser = new CommandlineOptionsParser(System.out);
        return commandlineOptionsParser.parse(argv).build();
    }

    public static Arguments createFilterArguments(RuntimeOptions runtimeOptions) {
        // Create filter from RuntimeOptions
        Predicate<Pickle> filter = new Filters(runtimeOptions);
        // Get PickleOrder from RuntimeOptions
        PickleOrder pickleOrder = runtimeOptions.getPickleOrder();
        // Get limit from RuntimeOptions
        int limit = runtimeOptions.getLimitCount();
        // Get feature paths from RuntimeOptions
        List<URI> featurePaths = runtimeOptions.getFeaturePaths();
        return new Arguments(filter, pickleOrder, limit, featurePaths);
    }

    public static List<Pickle> filterPicklesFromFeatures(
            List<Feature> features,
            RuntimeOptions runtimeOptions
    ) {
        Arguments args = createFilterArguments(runtimeOptions);
        Predicate<Pickle> filter = args.filter;
        PickleOrder pickleOrder = args.pickleOrder;
        int limit = args.limit;
        return features.stream()
                .flatMap(feature -> feature.getPickles().stream())
                .filter(filter)
                .collect(Collectors.collectingAndThen(
                        Collectors.toList(),
                        list -> pickleOrder.orderPickles(list).stream()
                ))
                .limit(limit > 0 ? limit : Integer.MAX_VALUE)
                .collect(Collectors.toList());
    }

    public static class Arguments {
        public final Predicate<Pickle> filter;
        public final PickleOrder pickleOrder;
        public final int limit;
        public final List<URI> featurePaths;

        public Arguments(Predicate<Pickle> filter, PickleOrder pickleOrder, int limit, List<URI> featurePaths) {
            this.filter = filter;
            this.pickleOrder = pickleOrder;
            this.limit = limit;
            this.featurePaths = featurePaths;
        }
    }
}