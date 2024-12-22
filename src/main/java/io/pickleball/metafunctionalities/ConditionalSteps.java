package io.pickleball.metafunctionalities;

import io.cucumber.core.runner.PickleStepTestStep;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import io.cucumber.java.en.Given;
import io.pickleball.annotations.Metastep;
import io.pickleball.customtypes.MetaStep;

import static io.pickleball.cacheandstate.PrimaryScenarioData.getCurrentStep;
import static io.pickleball.cucumberutilities.CucumberObjectFactory.createPickleStepTestStep;

public class ConditionalSteps {

    @Metastep
    @Given("the user is at coordinates {metaStep}")
    public void userAtCoordinates(MetaStep metaStep, DataTable dataTable, DocString docString) {
        PickleStepTestStep pickleStepTestStep = createPickleStepTestStep(metaStep.getStepText());
        getCurrentStep().addStepsToStack(pickleStepTestStep);
    }

}
