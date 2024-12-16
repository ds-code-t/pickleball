package io.pickleball.cucumberutilities;


import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.gherkin.Step;
import io.cucumber.core.gherkin.messages.GherkinMessagesPickle;
import io.cucumber.core.gherkin.messages.GherkinMessagesStep;
import io.cucumber.gherkin.GherkinDialectProvider;
import io.cucumber.plugin.event.Location;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FeatureTemplateProcessorTest {

    public static void main(String[] args) {
        testDynamicComponentRetrieval();
    }

    public static void testDynamicComponentRetrieval() {
        // Step 1: Define the feature template
        String featureTemplate = """
        Feature: Dynamic Feature Test
        
          Scenario: Test scenario with dynamic step
            Given I have {itemCount} items in my basket
            When I add {additionalCount} more items
            Then I should have {totalCount} items in my basket
        """;

        // Step 2: Define the placeholders to substitute
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("itemCount", "3");
        placeholders.put("additionalCount", "2");
        placeholders.put("totalCount", "5");

        // Step 3: Populate the feature template
        String populatedFeature = FeatureTemplateProcessor.populateTemplate(featureTemplate, placeholders);

        // Step 4: Parse the feature and extract components
        URI dummyUri = URI.create("file://test.feature");
        Feature feature = FeatureTemplateProcessor.parseFeatureFromText(populatedFeature, dummyUri);

        // Debugging output with improved clarity
        System.out.println("==== Debugging Output ====");
        System.out.println("Feature Name: " + feature.getName().orElse("Unnamed Feature"));

        // Extract Pickles
        List<Pickle> pickles = feature.getPickles();
        for (Pickle pickle : pickles) {
            System.out.println("Pickle Name: " + pickle.getName());

            // Extract Steps from Pickle
            List<Step> steps = pickle.getSteps();
            for (Step step : steps) {
                System.out.println("  Step Text: " + step.getText());

                // Check if the step is a GherkinMessagesStep
                if (step instanceof GherkinMessagesStep) {
                    GherkinMessagesStep gherkinStep = (GherkinMessagesStep) step;
                    System.out.println("    Step Keyword: " + gherkinStep.getKeyword());
                    Location location = gherkinStep.getLocation();
                    if (location != null) {
                        System.out.println("    Step Location: Line " + location.getLine() + ", Column " + location.getColumn());
                    }
                }
            }
        }
    }

}
