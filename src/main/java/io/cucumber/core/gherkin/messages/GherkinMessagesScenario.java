package io.cucumber.core.gherkin.messages;

import io.cucumber.messages.types.Examples;
import io.cucumber.plugin.event.Location;
import io.cucumber.plugin.event.Node;

import java.util.List;
import java.util.Optional;

public final class GherkinMessagesScenario implements Node.Scenario {

    private final Node parent;
    public final io.cucumber.messages.types.Scenario scenario;

    public GherkinMessagesScenario(Node parent, io.cucumber.messages.types.Scenario scenario) {
        this.parent = parent;
        this.scenario = scenario;
    }

    @Override
    public Optional<Node> getParent() {
        return Optional.of(parent);
    }

    @Override
    public Location getLocation() {
        return GherkinMessagesLocation.from(scenario.getLocation());
    }

    @Override
    public Optional<String> getKeyword() {
        return Optional.of(scenario.getKeyword());
    }

    @Override
    public Optional<String> getName() {
        String name = scenario.getName();
        return name.isEmpty() ? Optional.empty() : Optional.of(name);
    }





    // pmod
    public List<io.cucumber.messages.types.Examples> getExamples() {
        return scenario.getExamples();
    }

    public io.cucumber.messages.types.Scenario getWrappedScenario() {
        return scenario;
    }

   // pmode
    public io.cucumber.messages.types.Scenario getScenario() {
        return scenario;
    }

}
