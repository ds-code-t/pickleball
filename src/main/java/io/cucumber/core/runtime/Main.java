//package io.cucumber.core.runtime;
//
//import io.cucumber.core.gherkin.Feature;
//import io.cucumber.core.gherkin.Pickle;
//import io.cucumber.core.runtime.Runtime.Arguments;
//
//import java.util.HashMap;
//import java.util.List;
//import java.util.Map;
//
//import static io.cucumber.core.runtime.GlobalCache.getFeatures;
//
//public class Main {
//    public static void runScenarios(Map<String, Object> map) {
//        String featurePaths = map.getOrDefault("features", "src/test/resources/features").toString();
//        List<Feature> features = getFeatures(featurePaths);
//        System.out.println("@@featurePaths: " + features.size());
//
//
//        // Create arguments from command-like inputs
//        String tags = map.containsKey("tags") ? map.get("tags").toString() : null;
//        String namePattern = map.containsKey("namePattern") ? map.get("namePattern").toString() : null;
//        String order = map.getOrDefault("order", "random").toString();
//        Object limitVal = map.getOrDefault("limit", 0);
//        int limit = limitVal instanceof Integer ? (int) limitVal : Integer.parseInt(limitVal.toString());
//        long randomSeed = 12345L;
//
//        Arguments arguments = Runtime.createFilterArguments(tags, namePattern, order, limit, randomSeed);
//
//        // Use the filter function
//        List<Pickle> filteredPickles = Runtime.filterPicklesFromFeatures(
//                features,
//                arguments.filter,
//                arguments.pickleOrder,
//                arguments.limit
//        );
//
//        // Output filtered pickles
//        filteredPickles.forEach(System.out::println);
//    }
//
//    public static void main(String[] args) {
//        runScenarios(new HashMap<>(Map.of("tags", "@zzs")));
//    }
//}
