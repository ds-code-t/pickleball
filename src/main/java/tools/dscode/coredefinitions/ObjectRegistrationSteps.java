package tools.dscode.coredefinitions;

import io.cucumber.core.runner.StepExtension;
import io.cucumber.java.en.Given;
import org.openqa.selenium.remote.RemoteWebDriver;

import static io.cucumber.core.runner.CurrentScenarioState.getScenarioObject;
import static io.cucumber.core.runner.CurrentScenarioState.registerScenarioObject;
import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.util.GeneralUtils.isUsableValue;

public class ObjectRegistrationSteps {

    public static final String objRegistration = "(?i)^_";

    public static Object getObjectFromRegistryOrDefault(String objectName, String defaultObjectName) {
        String keyString = isUsableValue(objectName) ?  objectName: defaultObjectName;
        return returnStepParameter(keyString);
    }

    public static Object returnStepParameter(String keyString) {
        if (keyString == null || keyString.isBlank()) {
            throw new IllegalArgumentException("Object registration key cannot be null or blank");
        }
        Object returnObject = getScenarioObject(keyString);
        if (returnObject != null) return returnObject;
        StepExtension currentStep = getRunningStep();
        int suffixIndex = keyString.indexOf("::");
        String stepText = suffixIndex >= 0 ? keyString.substring(0, suffixIndex) : keyString;
        StepExtension modifiedStep = currentStep.modifyStepExtension("_" + stepText);
        modifiedStep.argument = currentStep.argument;
        Object returnValue = modifiedStep.runAndGetReturnValue();
        registerScenarioObject(keyString, returnValue);
        return returnValue;
    }

    @Given("^GET:(.*)$")
    public Object getObjectFromRegistration(String objectName) {
        return returnStepParameter(objectName);
    }

}