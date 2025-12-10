package tools.dscode.misc;

import io.cucumber.java.en.When;
import tools.dscode.common.IgnoredSteps;

import static tools.dscode.common.util.DebugUtils.printDebug;

public class DummySteps extends IgnoredSteps {

    @When("^(?:IF:|ELSE-IF:|ELSE:) ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*)(?: ELSE-IF: ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*))?(?: ELSE-IF: ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*))?(?: ELSE-IF: ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*))?(?: ELSE-IF: ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*))?(?: ELSE-IF: ((?:(?!THEN:).)*) (?:THEN:)?.*)$")
    public void dummyNOElseIF() {

    }

    @When("^(?:IF:|ELSE-IF:|ELSE:) ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*)(?: ELSE-IF: ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*))?(?: ELSE-IF: ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*))?(?: ELSE-IF: ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*))?(?: ELSE-IF: ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*))?(?: ELSE-IF: ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*))?(?: ELSE: (.*))?$")
    public void dummyIF() {

    }

    @When("^((?:(?!(?:IF:|ELSE-IF:|ELSE:):).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*)(?: ELSE-IF: ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*))?(?: ELSE-IF: ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*))?(?: ELSE-IF: ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*))?(?: ELSE-IF: ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*))?(?: ELSE-IF: ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*))?(?: ELSE: (.*))?$")
    public void dummyNoIF() {

    }

    @When("^(?:(?:IF:|ELSE-IF:|ELSE:|THEN:)(.*))+$")
    public void dummyOnlyIF(String a) {

    }

}
