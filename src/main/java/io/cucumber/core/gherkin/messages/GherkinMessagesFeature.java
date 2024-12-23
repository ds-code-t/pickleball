package io.cucumber.core.gherkin.messages;

import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.messages.types.Comment;
import io.cucumber.messages.types.Envelope;
import io.cucumber.messages.types.FeatureChild;
import io.cucumber.messages.types.GherkinDocument;
import io.cucumber.plugin.event.Location;
import io.cucumber.plugin.event.Node;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

public final class GherkinMessagesFeature implements Feature {

    private final io.cucumber.messages.types.Feature feature;
    private final URI uri;
    private final List<Pickle> pickles;
    private final List<Envelope> envelopes;
    private final String gherkinSource;
    private final List<Node> children;


    public GherkinMessagesFeature(
            io.cucumber.messages.types.Feature feature,
            URI uri,
            String gherkinSource,
            List<Pickle> pickles,
            List<Envelope> envelopes
    ) {
        this.feature = requireNonNull(feature);
        this.uri = requireNonNull(uri);
        this.gherkinSource = requireNonNull(gherkinSource);
        this.pickles = requireNonNull(pickles);
        this.envelopes = requireNonNull(envelopes);
        this.children = feature.getChildren().stream()
                .filter(this::hasRuleOrScenario)
                .map(this::mapRuleOrScenario)
                .collect(Collectors.toList());
//           this.envelopes.forEach(e -> System.out.println("\n\n=========\n@@envelop:: " + e + "\n---\n"));


        System.out.println("@@getDescriptionLinesWithLineNumbers:: " + getDescriptionLinesWithLineNumbers());
        System.out.println("========= ");
    }


    public TreeMap<Integer, String> getDescriptionLinesWithLineNumbers() {
        Optional<GherkinDocument> gherkinDocumentOpt = envelopes.stream()
                .map(Envelope::getGherkinDocument)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();

        if (gherkinDocumentOpt.isEmpty()) {
            throw new IllegalStateException("No GherkinDocument found in the envelopes.");
        }

        GherkinDocument gherkinDocument = gherkinDocumentOpt.get();
        io.cucumber.messages.types.Feature feature = gherkinDocument.getFeature().orElseThrow(
                () -> new IllegalStateException("Feature not found in GherkinDocument"));

        TreeMap<Integer, String> descriptionLines = new TreeMap<>();

        // Collect feature-level description
        if (!feature.getDescription().isEmpty()) {
            int currentLine = (int) (feature.getLocation().getLine() + 1);
            for (String line : feature.getDescription().split("\n")) {
                descriptionLines.put(currentLine++, line.trim());
            }
        }

        // Collect scenario-level descriptions
        feature.getChildren().forEach(child -> {
            if (child.getScenario().isPresent()) {
                io.cucumber.messages.types.Scenario scenario = child.getScenario().get();
                System.out.println("@@scenario.getDescription()L:: " + scenario.getDescription());
                if (!scenario.getDescription().isEmpty()) {
                    int currentLine = (int) (scenario.getLocation().getLine() + 1);
                    for (String line : scenario.getDescription().split("\n")) {
                        descriptionLines.put(currentLine++, line.trim());
                    }
                }
            }
        });

        return descriptionLines;
    }








    public String getScenarioMetadataByLineNumber(int lineNumber) {
        Optional<GherkinDocument> gherkinDocumentOpt = envelopes.stream()
                .map(Envelope::getGherkinDocument)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();

        if (gherkinDocumentOpt.isEmpty()) {
            throw new IllegalStateException("No GherkinDocument found in the envelopes.");
        }

        GherkinDocument gherkinDocument = gherkinDocumentOpt.get();
        io.cucumber.messages.types.Feature feature = gherkinDocument.getFeature().orElseThrow(
                () -> new IllegalStateException("Feature not found in GherkinDocument"));

        StringBuilder metadata = new StringBuilder();

        // Add feature-level comments and description
        metadata.append("Feature: ").append(feature.getName()).append("\n");
        metadata.append( feature.getDescription()).append("\n");

        feature.getChildren().forEach(child -> {
            if (child.getScenario().isPresent()) {
                io.cucumber.messages.types.Scenario scenario = child.getScenario().get();

                // Match by line number
                if (scenario.getLocation().getLine() == lineNumber) {
                    metadata.append("Scenario: ").append(scenario.getName()).append("\n");
                    metadata.append( scenario.getDescription()).append("\n");
                    // Append associated comments
                    List<Comment> comments = gherkinDocument.getComments();
                    comments.stream()
                            .filter(comment -> comment.getLocation().getLine() < lineNumber)
                            .forEach(comment -> metadata.append("Comment: ").append(comment.getText()).append("\n"));
                }
            }
        });

        return metadata.toString().trim();
    }




    private Node mapRuleOrScenario(FeatureChild featureChild) {
        if (featureChild.getRule().isPresent()) {
            return new GherkinMessagesRule(this, featureChild.getRule().get());
        }

        io.cucumber.messages.types.Scenario scenario = featureChild.getScenario().get();
        if (!scenario.getExamples().isEmpty()) {
            return new GherkinMessagesScenarioOutline(this, scenario);
        }
        return new GherkinMessagesScenario(this, scenario);
    }

    private boolean hasRuleOrScenario(FeatureChild featureChild) {
        return featureChild.getRule().isPresent() || featureChild.getScenario().isPresent();
    }

    @Override
    public Collection<Node> elements() {
        return children;
    }

    @Override
    public Location getLocation() {
        return GherkinMessagesLocation.from(feature.getLocation());
    }

    @Override
    public Optional<String> getKeyword() {
        return Optional.of(feature.getKeyword());
    }

    @Override
    public Optional<String> getName() {
        String name = feature.getName();
        return name.isEmpty() ? Optional.empty() : Optional.of(name);
    }

    @Override
    public Optional<Node> getParent() {
        return Optional.empty();
    }

    @Override
    public Pickle getPickleAt(Node node) {
        Location location = node.getLocation();
        return pickles.stream()
                .filter(pickle -> pickle.getLocation().equals(location))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("No pickle in " + uri + " at " + location));
    }

    @Override
    public List<Pickle> getPickles() {
        return pickles;
    }

    @Override
    public URI getUri() {
        return uri;
    }

    @Override
    public String getSource() {
        return gherkinSource;
    }

    @Override
    public Iterable<?> getParseEvents() {
        return envelopes;
    }

    @Override
    public int hashCode() {
        return Objects.hash(uri);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        GherkinMessagesFeature that = (GherkinMessagesFeature) o;
        return uri.equals(that.uri);
    }

    //pmod
    public List<Node> getChildren() {
        return children;
    }

}
