//package io.pickleball.cucumberutilities;
//
//import io.cucumber.core.gherkin.messages.GherkinMessagesExamples;
//import io.cucumber.plugin.event.Node;
//
//import java.net.URI;
//import java.nio.file.Paths;
//import java.util.List;
//
//import static io.pickleball.cucumberutilities.FeatureFileUtilities.getComponentByLine;
//import static io.pickleball.cucumberutilities.FeatureFileUtilities.parseFeature;
//
//public class FeatureFileUtilitiesTest {
//
//    public static void main(String[] args) {
//        try {
//            URI featureUri = Paths.get("src/test/resources/features/debugindep1.feature").toUri();
//
//
//
//            // Get all scenarios
//            List<io.cucumber.messages.types.Scenario> scenarios = FeatureFileUtilities.getAllScenarios(featureUri);
//            scenarios.forEach(scenario -> System.out.println("Scenario: " + scenario.getName() + scenario.getLocation().toString()));
//
//            io.cucumber.core.gherkin.messages.GherkinMessagesFeature feature = parseFeature(featureUri);
//            feature.getChildren().forEach(c -> System.out.println("@@@child:: getLine: "+ c.getLocation().getLine() + "  , getKeyword: " + c.getKeyword() + c.toString() + " " + c.getName() + "  , " + c.getClass()));
//
//            Node n1 = getComponentByLine(featureUri, 74);
//            Node n2 = getComponentByLine(featureUri, 80);
//            Node n3 = getComponentByLine(featureUri, 82);
//
//            if(n3 instanceof GherkinMessagesExamples)
//            {
//
//            }
//
////            // Get a specific scenario by line number
////            int lineNumber = 5; // Replace with a valid line number
////            io.cucumber.messages.types.Scenario scenario = FeatureFileUtilities.getScenarioByLine(featureUri, lineNumber);
////            System.out.println("Scenario at line " + lineNumber + ": " + scenario.getName());
////
////            Object obj = FeatureFileUtilities.getScenarioByLine(featureUri, 55);
////            System.out.println("@@@@1 ");
////
////            System.out.println("Object at line " + lineNumber + ": " + obj.getClass() + "  : " + obj  );
////            System.out.println("@@@@2 ");
////
////            // Get scenarios by tag
////            String tag = "@tagss";
////            List<io.cucumber.messages.types.Scenario> taggedScenarios = FeatureFileUtilities.getScenariosByTag(featureUri, tag);
////            taggedScenarios.forEach(s -> System.out.println("Scenario with tag " + tag + ": " + s.getName()));
//
//        } catch (Exception e) {
//            System.err.println("Error: " + e.getMessage());
//        }
//    }
//
//
//
//}
