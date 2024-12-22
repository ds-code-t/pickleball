package io.cucumber.core.gherkin.messages;

import io.cucumber.messages.types.Examples;
import io.cucumber.plugin.event.Location;
import io.cucumber.plugin.event.Node;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public final class GherkinMessagesExamples implements Node.Examples {

    private final io.cucumber.messages.types.Examples examples;

    public List<GherkinMessagesExample> getChildren() {
        return children.stream().map(e -> (GherkinMessagesExample) e).toList();
    }

    public GherkinMessagesExample getExample(int lineNum) {
        return (GherkinMessagesExample) children.stream().filter(example -> example.getLocation().getLine() == lineNum ).findFirst().orElse(null);
    }

    public io.cucumber.messages.types.Examples getExamples() {
        return examples;
    }


    private final List<Example> children;
    private final Location location;
    private final Node parent;

    public GherkinMessagesExamples(Node parent, io.cucumber.messages.types.Examples examples, int examplesIndex) {
        this.parent = parent;
        this.examples = examples;
        this.location = GherkinMessagesLocation.from(examples.getLocation());
        AtomicInteger row = new AtomicInteger(1);
        this.children = examples.getTableBody().stream()
                .map(tableRow -> new GherkinMessagesExample(this, tableRow, examplesIndex, row.getAndIncrement()))
                .collect(Collectors.toList());
    }

    @Override
    public Collection<Example> elements() {
        return children;
    }

    @Override
    public Location getLocation() {
        return location;
    }

    @Override
    public Optional<String> getKeyword() {
        return Optional.of(examples.getKeyword());
    }

    @Override
    public Optional<String> getName() {
        String name = examples.getName();
        return name.isEmpty() ? Optional.empty() : Optional.of(name);
    }

    @Override
    public Optional<Node> getParent() {
        return Optional.of(parent);
    }

}
