package tools.ds.modkit.misc;

import io.cucumber.java.en.When;

public class DummySteps {



    @When("^(?:IF:|ELSE-IF:|ELSE:) ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*)(?: ELSE-IF: ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*))?(?: ELSE-IF: ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*))?(?: ELSE-IF: ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*))?(?: ELSE-IF: ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*))?(?: ELSE-IF: ((?:(?!THEN:).)*) (?:THEN:)?.*)$")
    public void dummyNOElseIF() {

    }

    @When("^(?:IF:|ELSE-IF:|ELSE:) ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*)(?: ELSE-IF: ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*))?(?: ELSE-IF: ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*))?(?: ELSE-IF: ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*))?(?: ELSE-IF: ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*))?(?: ELSE-IF: ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*))?(?: ELSE: (.*))?$")
    public void dummyIF() {
        System.out.println("@@dummyIF");
    }

    @When("^((?:(?!(?:IF:|ELSE-IF:|ELSE:):).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*)(?: ELSE-IF: ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*))?(?: ELSE-IF: ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*))?(?: ELSE-IF: ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*))?(?: ELSE-IF: ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*))?(?: ELSE-IF: ((?:(?!THEN:).)*) THEN: ((?:(?!(?:ELSE:|ELSE-IF:)).)*))?(?: ELSE: (.*))?$")
    public void dummyNoIF() {
        System.out.println("@@dummyNoIF");
    }

    @When("^(?:(?:IF:|ELSE-IF:|ELSE:|THEN:)(.*))+$")
    public void dummyOnlyIF(String a) {
        System.out.println("@@dummyOnlyIF");
    }

}
