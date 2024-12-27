package io.pickleball.cucumberutilities;

import io.cucumber.core.gherkin.Step;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;

public class PlaceHolderObjects {

    public static io.cucumber.core.runner.PickleStepDefinitionMatch createDummyPickleStepDefinitionMatch(Step step) {
        try {
            return new io.cucumber.core.runner.PickleStepDefinitionMatch(
                    Collections.emptyList(), // No arguments
                    dummyStepDefinition,     // Dummy step definition
                    new URI(step.getId()),
                    step
            );
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

    }

    public static io.cucumber.core.backend.StepDefinition dummyStepDefinition = new io.cucumber.core.backend.StepDefinition() {
        @Override
        public String getPattern() {
            return "";
        }

        //            @Override
        public List<String> matchedArguments(String dynamicStep) {
            return Collections.emptyList();
        }

        @Override
        public void execute(Object[] args) {
        }

        @Override
        public List<io.cucumber.core.backend.ParameterInfo> parameterInfos() {
            return List.of();
        }

        @Override
        public boolean isDefinedAt(StackTraceElement stackTraceElement) {
            return false;
        }

        @Override
        public String getLocation() {
            return "dummy location";
        }
    };

}
