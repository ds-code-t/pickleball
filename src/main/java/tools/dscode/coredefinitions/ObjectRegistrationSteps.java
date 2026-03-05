package tools.dscode.coredefinitions;


import io.cucumber.core.runner.StepExtension;
import io.cucumber.core.stepexpression.DocStringArgument;
import io.cucumber.java.en.Given;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.chromium.ChromiumDriver;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;

import java.util.Map;
import java.util.Objects;

import static io.cucumber.core.runner.CurrentScenarioState.getScenarioObject;
import static io.cucumber.core.runner.CurrentScenarioState.registerScenarioObject;
import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.domoperations.SeleniumUtils.ensureDevToolsPort;
import static tools.dscode.common.mappings.BracketLiteralMasker.getAndResolveKeyWithMasking;
import static tools.dscode.common.mappings.NodeMap.MAPPER;
import static tools.dscode.common.variables.RunVars.resolveVarOrDefault;

public class ObjectRegistrationSteps {




    private static Object getObjectFromRegistryOrDefault(String objectName, String defaultObjectName) {
        Object returnObject = getScenarioObject(objectName);
        if (returnObject != null) return  returnObject;
        returnObject = returnStepParameter(objectName);
        if (returnObject != null) return  returnObject;
        return returnStepParameter(defaultObjectName);
    }


    public static Object returnStepParameter(String stepText) {
        return returnStepParameter(stepText, null);
    }


    public static Object returnStepParameter(String stepText, String key) {
        StepExtension currentStep = getRunningStep();

        // Decide which cache key to use:
        // - if key is null/blank => cache by stepText
        // - else => cache by key
        String cacheKey = (key == null || key.isBlank()) ? stepText : key;

        Object existing = getScenarioObject(cacheKey);
        if (existing != null) return existing;

        StepExtension modifiedStep = currentStep.modifyStepExtension(stepText);
        modifiedStep.argument = currentStep.argument;

        Object returnValue = modifiedStep.runAndGetReturnValue();
        registerScenarioObject(cacheKey, returnValue);
        return returnValue;
    }



    public static JavascriptExecutor getJavascriptExecutor() {
        return getDefaultDriver();
    }

    public static ChromiumDriver getDefaultDriver() {
        String browserName = resolveVarOrDefault("BROWSER", "BROWSER").toString();
        return getDriver(browserName);
    }

    public static ChromiumDriver getDriver(String browserName) {
        WebDriver webDriver = getChromiumDriver(browserName);
        getRunningStep().webDriverUsed = webDriver;
        return (ChromiumDriver) webDriver;
    }

    private static ChromiumDriver getChromiumDriver(String browserName) {
        return (ChromiumDriver) getObjectFromRegistryOrDefault(browserName,"CHROME");
    }



    //    @Given("(?i)^@chrome$")
    @Given("(?i)^BROWSER$")
    @Given("(?i)^CHROME$")
    public ChromeDriver getChrome() throws Exception {
        StepExtension currentStep = getRunningStep();
        String json = !(currentStep.argument instanceof DocStringArgument) ? getAndResolveKeyWithMasking("configs.chrome") : currentStep.argument.getValue().toString();
        if (Objects.isNull(json)) throw new RuntimeException("Chrome Driver Configuration not found");
        Map<String, Object> map = MAPPER.readValue(json, Map.class);
        ChromeOptions options = new ChromeOptions();
        map.forEach(options::setCapability);
        if (getCurrentScenarioState().debugBrowser) {
            ensureDevToolsPort(options, "chrome");
        }
        ChromeDriver chromeDriver = new ChromeDriver(options);
        registerScenarioObject("browser", chromeDriver);
        return chromeDriver;
    }

    @Given("(?i)^EDGE$")
    public EdgeDriver getEdge() throws Exception {
        StepExtension currentStep = getRunningStep();
        String json = !(currentStep.argument instanceof DocStringArgument) ? (String) currentStep.getStepParsingMap().getAndResolve("configs.edge") : currentStep.argument.getValue().toString();
        if (Objects.isNull(json)) throw new RuntimeException("Edge Driver Configuration not found");
        Map<String, Object> map = MAPPER.readValue(json, Map.class);
        EdgeOptions options = new EdgeOptions();
        map.forEach(options::setCapability);
        if (getCurrentScenarioState().debugBrowser) {
            ensureDevToolsPort(options, "edge");
        }
        EdgeDriver edgeDriver = new EdgeDriver(options);
        registerScenarioObject("browser", edgeDriver);
        return edgeDriver;
    }

}
