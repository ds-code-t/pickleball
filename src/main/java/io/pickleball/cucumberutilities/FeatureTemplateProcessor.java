package io.pickleball.cucumberutilities;

import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.gherkin.messages.GherkinMessagesFeatureParser;
import io.cucumber.core.gherkin.messages.GherkinMessagesPickle;
import io.cucumber.core.gherkin.messages.GherkinMessagesStep;
import io.cucumber.core.gherkin.messages.GherkinMessagesDataTableArgument;
import io.cucumber.gherkin.GherkinDialect;
import io.cucumber.core.gherkin.messages.CucumberQuery;
import io.cucumber.messages.types.PickleStep;
import io.cucumber.messages.types.PickleStepArgument;
import io.cucumber.messages.types.PickleTable;
import io.cucumber.messages.types.PickleTableCell;
import io.cucumber.messages.types.PickleTableRow;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class FeatureTemplateProcessor {

    public static String populateTemplate(String template, java.util.Map<String, String> values) {
        for (var entry : values.entrySet()) {
            template = template.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return template;
    }

    public static Feature parseFeatureFromText(String featureSource, URI uri) {
        GherkinMessagesFeatureParser parser = new GherkinMessagesFeatureParser();
        Optional<Feature> featureOptional = parser.parse(uri, featureSource, UUID::randomUUID);
        return featureOptional.orElseThrow(() -> new RuntimeException("Failed to parse the feature source."));
    }

    public static GherkinMessagesPickle createPickleFromFeature(
            String featureSource,
            URI uri,
            GherkinDialect dialect) {

        Feature feature = parseFeatureFromText(featureSource, uri);
        List<io.cucumber.core.gherkin.Pickle> pickles = feature.getPickles();
        if (pickles.isEmpty()) {
            throw new RuntimeException("No pickles found in the feature.");
        }

        // Access the underlying Pickle object from GherkinMessagesPickle
        if (!(pickles.get(0) instanceof GherkinMessagesPickle)) {
            throw new IllegalArgumentException("Expected a GherkinMessagesPickle, but found: " + pickles.get(0).getClass());
        }

        GherkinMessagesPickle gherkinPickle = (GherkinMessagesPickle) pickles.get(0);
        io.cucumber.messages.types.Pickle rawPickle = gherkinPickle.getPickle(); // Use the appropriate accessor method

        return new GherkinMessagesPickle(rawPickle, uri, dialect, new CucumberQuery());
    }


    public static GherkinMessagesDataTableArgument createDataTableFromRaw(
            List<List<String>> rawTable) {

        List<PickleTableRow> rows = rawTable.stream()
                .map(row -> new PickleTableRow(row.stream()
                        .map(PickleTableCell::new)
                        .collect(Collectors.toList())))
                .collect(Collectors.toList());

        PickleTable table = new PickleTable(rows);
        return new GherkinMessagesDataTableArgument(table, 1);
    }

    public static GherkinMessagesStep createStepFromText(
            String stepText,
            String previousKeyword,

            io.cucumber.plugin.event.Location location,
            GherkinDialect dialect,
            String keyword) {

        PickleStepArgument stepArgument = null; // Replace as needed
        PickleStep pickleStep = new PickleStep(
                stepArgument,
                List.of("some-node-id"),
                UUID.randomUUID().toString(),
                null,
                stepText
        );

        return new GherkinMessagesStep(pickleStep, dialect, previousKeyword, location, keyword);
    }

    public static GherkinMessagesPickle generatePickleFromTemplate(
            String featureTemplate,
            java.util.Map<String, String> placeholders,
            URI uri,
            GherkinDialect dialect) {
        String populatedFeature = populateTemplate(featureTemplate, placeholders);
        return createPickleFromFeature(populatedFeature, uri, dialect);
    }
}
