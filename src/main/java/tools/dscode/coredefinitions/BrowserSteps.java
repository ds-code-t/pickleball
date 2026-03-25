package tools.dscode.coredefinitions;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cucumber.java.en.Given;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.remote.RemoteWebDriver;
import tools.dscode.common.driver.DriverWrapper;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.mappings.ParsingMap.getFromRunningParsingMapCaseInsensitiveOrDefault;
import static tools.dscode.common.variables.RunVars.VAR_PREFIX;
import static tools.dscode.coredefinitions.ObjectRegistrationSteps.getObjectFromRegistryOrDefault;

public class BrowserSteps {

    public static JavascriptExecutor getJavascriptExecutor() {
        return (JavascriptExecutor) getDefaultDriver();
    }

    public static WebDriver getDefaultDriver() {
        String browserName = String.valueOf(getFromRunningParsingMapCaseInsensitiveOrDefault(VAR_PREFIX + "BROWSER", "BROWSER"));
        return getDriver(browserName);
    }

    public static DriverWrapper getDefaultDriverWrapper() {
        String browserName = String.valueOf(getFromRunningParsingMapCaseInsensitiveOrDefault(VAR_PREFIX + "BROWSER", "BROWSER"));
        return getDriverWrapper(browserName);
    }

    public static WebDriver getDriver(String browserName) {
        Object created = getObjectFromRegistryOrDefault(browserName, "CHROME");
        WebDriver webDriver = unwrapWebDriver(created);
        getRunningStep().webDriverUsed = webDriver;
        return webDriver;
    }

    public static DriverWrapper getDriverWrapper(String browserName) {
        Object created = getObjectFromRegistryOrDefault(browserName, "CHROME");
        if (created instanceof DriverWrapper wrapper) {
            getRunningStep().webDriverUsed = wrapper.getDriver();
            return wrapper;
        }

        throw new RuntimeException(
                "Registered object '" + browserName + "' was " +
                        (created == null ? "null" : created.getClass().getName()) +
                        " instead of " + DriverWrapper.class.getName()
        );
    }

    public static WebDriver unwrapWebDriver(Object value) {
        if (value instanceof DriverWrapper wrapper) {
            return wrapper.getDriver();
        }
        if (value instanceof WebDriver webDriver) {
            return webDriver;
        }
        throw new RuntimeException(
                "Registered object was " +
                        (value == null ? "null" : value.getClass().getName()) +
                        " instead of a WebDriver or DriverWrapper"
        );
    }

    @Given("(?i)^CREATE_DRIVER$")
    public DriverWrapper createDriver(ObjectNode configuration) throws Exception {
        return DriverWrapper.createDriver(configuration);
    }

    @Given("(?i)^CREATE_LOCAL_DRIVER$")
    public DriverWrapper createLocalDriver(ObjectNode configuration) throws Exception {
        return DriverWrapper.createLocalDriver(configuration);
    }

    @Given("(?i)^CREATE_REMOTE_DRIVER$")
    public DriverWrapper createRemoteDriver(ObjectNode configuration) throws Exception {
        return DriverWrapper.createRemoteDriver(configuration);
    }
}