package tools.dscode.coredefinitions;

import io.cucumber.core.runner.StepExtension;
import io.cucumber.core.stepexpression.DocStringArgument;
import io.cucumber.java.en.Given;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.MutableCapabilities;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;

import java.net.URL;
import java.util.Map;
import java.util.Objects;

import static io.cucumber.core.runner.CurrentScenarioState.registerScenarioObject;
import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.domoperations.SeleniumUtils.ensureDevToolsPort;
import static tools.dscode.common.mappings.BracketLiteralMasker.getAndResolveKeyWithMasking;
import static tools.dscode.common.mappings.NodeMap.MAPPER;
import static tools.dscode.common.variables.RunVars.resolveVarOrDefault;
import static tools.dscode.coredefinitions.ObjectRegistrationSteps.getObjectFromRegistryOrDefault;
import static tools.dscode.coredefinitions.ObjectRegistrationSteps.objRegistration;

public class BrowserSteps {

    public static JavascriptExecutor getJavascriptExecutor() {
        return getDefaultDriver();
    }

    public static RemoteWebDriver getDefaultDriver() {
        String browserName = resolveVarOrDefault("BROWSER", "BROWSER").toString();
        return getDriver(browserName);
    }

    public static RemoteWebDriver getDriver(String browserName) {
        WebDriver webDriver = getBrowserOrDefaultToChrome(browserName);
        getRunningStep().webDriverUsed = webDriver;
        return (RemoteWebDriver) webDriver;
    }

    private static RemoteWebDriver getBrowserOrDefaultToChrome(String browserName) {
        return (RemoteWebDriver) getObjectFromRegistryOrDefault(browserName, "CHROME");
    }

    @Given(objRegistration + "BROWSER$")
    public RemoteWebDriver getBrowser() throws Exception {
        return getLocalBrowser("chrome", "");
    }

    @Given(objRegistration + "(CHROME|EDGE)(.*)?$")
    public RemoteWebDriver getLocalBrowser(String browserName, String configFileSuffix) throws Exception {
        StepExtension currentStep = getRunningStep();
        configFileSuffix = configFileSuffix == null ? "" : configFileSuffix;
        String normalizedBrowserName = browserName.toLowerCase();
        String json = getConfigJson(currentStep, "configs." + normalizedBrowserName + configFileSuffix);
        if (Objects.isNull(json)) throw new RuntimeException(browserName + " Driver Configuration not found");
        Map<String, Object> map = MAPPER.readValue(json, Map.class);

        RemoteWebDriver driver;
        switch (normalizedBrowserName) {
            case "chrome":
                ChromeOptions chromeOptions = new ChromeOptions();
                map.forEach(chromeOptions::setCapability);
                if (getCurrentScenarioState().debugBrowser) ensureDevToolsPort(chromeOptions, "chrome");
                driver = new ChromeDriver(chromeOptions);
                break;
            case "edge":
                EdgeOptions edgeOptions = new EdgeOptions();
                map.forEach(edgeOptions::setCapability);
                if (getCurrentScenarioState().debugBrowser) ensureDevToolsPort(edgeOptions, "edge");
                driver = new EdgeDriver(edgeOptions);
                break;
            default:
                throw new RuntimeException("Unsupported local browser: " + browserName);
        }

        registerScenarioObject("browser", driver);
        return driver;
    }

    @Given(objRegistration + "SAUCE_(chrome|edge)(.*)?$")
    public RemoteWebDriver getSauceLabs(String browserName, String configFileSuffix) throws Exception {
        StepExtension currentStep = getRunningStep();
        configFileSuffix = configFileSuffix == null ? "" : configFileSuffix;
        String normalizedBrowserName = browserName.toLowerCase();
        String json = getConfigJson(currentStep, "configs." + normalizedBrowserName + configFileSuffix);
        if (Objects.isNull(json)) throw new RuntimeException("Sauce Labs Driver Configuration not found");
        Map<String, Object> map = MAPPER.readValue(json, Map.class);

        String remoteUrl = Objects.toString(map.remove("remoteUrl"), null);
        if (Objects.isNull(remoteUrl)) throw new RuntimeException("Sauce Labs remoteUrl not found");

        MutableCapabilities options = getRemoteOptions(normalizedBrowserName, map);
        RemoteWebDriver remoteWebDriver = new RemoteWebDriver(new URL(remoteUrl), options);
        registerScenarioObject("browser", remoteWebDriver);
        return remoteWebDriver;
    }

    @Given(objRegistration + "GRID_(chrome|edge)(.*)?$")
    @Given(objRegistration + "SELENIUM_GRID_(chrome|edge)(.*)?$")
    public RemoteWebDriver getSeleniumGrid(String browserName, String configFileSuffix) throws Exception {
        StepExtension currentStep = getRunningStep();
        configFileSuffix = configFileSuffix == null ? "" : configFileSuffix;
        String normalizedBrowserName = browserName.toLowerCase();
        String json = getConfigJson(currentStep, "configs." + normalizedBrowserName + configFileSuffix);
        if (Objects.isNull(json)) throw new RuntimeException("Selenium Grid Driver Configuration not found");
        Map<String, Object> map = MAPPER.readValue(json, Map.class);

        String remoteUrl = Objects.toString(map.remove("remoteUrl"), null);
        if (Objects.isNull(remoteUrl)) throw new RuntimeException("Selenium Grid remoteUrl not found");

        MutableCapabilities options = getRemoteOptions(normalizedBrowserName, map);
        RemoteWebDriver remoteWebDriver = new RemoteWebDriver(new URL(remoteUrl), options);
        registerScenarioObject("browser", remoteWebDriver);
        return remoteWebDriver;
    }

    private static String getConfigJson(StepExtension currentStep, String configKey) {
        return !(currentStep.argument instanceof DocStringArgument)
                ? getAndResolveKeyWithMasking(configKey)
                : currentStep.argument.getValue().toString();
    }

    private static MutableCapabilities getRemoteOptions(String browserName, Map<String, Object> map) {
        MutableCapabilities options;

        switch (browserName) {
            case "chrome":
                ChromeOptions chromeOptions = new ChromeOptions();
                map.forEach(chromeOptions::setCapability);
                if (getCurrentScenarioState().debugBrowser) ensureDevToolsPort(chromeOptions, "chrome");
                options = chromeOptions;
                break;
            case "edge":
                EdgeOptions edgeOptions = new EdgeOptions();
                map.forEach(edgeOptions::setCapability);
                if (getCurrentScenarioState().debugBrowser) ensureDevToolsPort(edgeOptions, "edge");
                options = edgeOptions;
                break;
            default:
                throw new RuntimeException("Unsupported remote browser: " + browserName);
        }

        return options;
    }
}