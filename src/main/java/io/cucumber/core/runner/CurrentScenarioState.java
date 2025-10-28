package io.cucumber.core.runner;


import io.cucumber.gherkin.GherkinDialect;
import io.cucumber.gherkin.GherkinDialects;

import static tools.dscode.common.util.Reflect.getProperty;
import static tools.dscode.registry.GlobalRegistry.localOrGlobalOf;

public class CurrentScenarioState {

    public static io.cucumber.core.runner.TestCase getTestCase() {
        return localOrGlobalOf(io.cucumber.core.runner.TestCase.class);
    }

    public static io.cucumber.core.gherkin.Pickle getGherkinMessagesPickle() {
        return (io.cucumber.core.gherkin.Pickle) getProperty(getTestCase(), "pickle");
    }

    public static String getLanguage() {
        return getGherkinMessagesPickle().getLanguage();
    }

    public static GherkinDialect getGherkinDialect() {
        return GherkinDialects.getDialect(getLanguage())
                .orElse(GherkinDialects.getDialect("en").get());
    }

    public static String getGivenKeyword() {
        return getGherkinDialect().getGivenKeywords().getFirst();
    }
}
