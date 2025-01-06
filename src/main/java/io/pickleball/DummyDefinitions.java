package io.pickleball;

import io.cucumber.java.en.When;

// regex for the IntelliK Cucumber plugin to match.  Step execution will be intercepted
public class DummyDefinitions {
    @When("^IF: ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*)(?: ELSE-IF: ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*))?(?: ELSE-IF: ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*))?(?: ELSE-IF: ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*))?(?: ELSE-IF: ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*))?(?: ELSE-IF: ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*))?(?: ELSE: (.*))?$")
    public void dummyIF() {
    }

}
