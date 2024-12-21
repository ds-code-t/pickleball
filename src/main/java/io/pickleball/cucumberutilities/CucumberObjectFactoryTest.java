package io.pickleball.cucumberutilities;

public class CucumberObjectFactoryTest {

    public static void main(String[] args) {
        // Test creating a PickleStepTestStep with step text
        io.cucumber.core.runner.PickleStepTestStep step1 = CucumberObjectFactory.createPickleStepTestStep("I perform an action");
        System.out.println("Step Text: " + step1.getStep().getText());

        // Test creating a PickleStepTestStep with a DataTableArgument
        String tableSource = "| Column1 | Column2 |\n| Value1 | Value2 |";
        io.cucumber.core.gherkin.messages.GherkinMessagesDataTableArgument dataTable =
                CucumberObjectFactory.createDataTableArgument(tableSource);
        io.cucumber.core.runner.PickleStepTestStep step2 =
                CucumberObjectFactory.createPickleStepTestStepWithArgs("I have a data table", dataTable, null, null, 0);
        System.out.println("Step Text: " + step2.getStep().getText());

        // Test creating a PickleStepTestStep with a DocStringArgument
        String docStringContent = "This is a docstring";
        io.cucumber.core.gherkin.messages.GherkinMessagesDocStringArgument docString =
                CucumberObjectFactory.createDocStringArgument(docStringContent);
        io.cucumber.core.runner.PickleStepTestStep step3 =
                CucumberObjectFactory.createPickleStepTestStepWithArgs("I have a docstring", null, docString, null, 0 );
        System.out.println("Step Text: " + step3.getStep().getText());
    }
}
