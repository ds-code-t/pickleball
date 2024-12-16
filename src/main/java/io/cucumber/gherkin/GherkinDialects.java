package io.cucumber.gherkin;

import java.util.LinkedHashMap;
import java.util.Map;

import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;

final class GherkinDialects {
    static final Map<String, GherkinDialect> DIALECTS;

    static {
        Map<String, GherkinDialect> dialects = new LinkedHashMap<>();


        dialects.put("en", new GherkinDialect(
                "en",
                "English",
                "English",
                unmodifiableList(asList("Feature", "Business Need", "Ability")),
                unmodifiableList(asList("Rule")),
                unmodifiableList(asList("Example", "Scenario")),
                unmodifiableList(asList("Scenario Outline", "Scenario Template")),
                unmodifiableList(asList("Background")),
                unmodifiableList(asList("Examples", "Scenarios")),
                unmodifiableList(asList("* ", "Given ")),
                unmodifiableList(asList("* ", "When ")),
                unmodifiableList(asList("* ", "Then ")),
                unmodifiableList(asList("* ", "And ")),
                unmodifiableList(asList("* ", "But ")),
                unmodifiableList(asList("* ", "If "))

        ));


        DIALECTS = unmodifiableMap(dialects);
    }
}
