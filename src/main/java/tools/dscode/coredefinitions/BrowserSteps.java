package tools.dscode.coredefinitions;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cucumber.java.en.Given;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.remote.LocalFileDetector;
import org.openqa.selenium.remote.RemoteWebDriver;
import tools.dscode.common.driver.DriverConstruction;

import java.time.Duration;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.variables.RunVars.resolveFromVarsOrDefault;
import static tools.dscode.coredefinitions.ObjectRegistrationSteps.constructObjectFromParsingMap;

public class BrowserSteps {

    public static JavascriptExecutor getJavascriptExecutor() {
        return (JavascriptExecutor) getDefaultDriver();
    }

    public static RemoteWebDriver getDefaultDriver() {
        String browserName = String.valueOf(resolveFromVarsOrDefault( "BROWSER", "CHROME"));
        return getDriver(browserName);
    }

    public static RemoteWebDriver getDriver(String browserName) {
        Object created = constructObjectFromParsingMap(browserName);
        RemoteWebDriver webDriver = (RemoteWebDriver) created;
        getRunningStep().webDriverUsed = webDriver;
        return webDriver;
    }


    @Given(ObjectRegistrationSteps.objCreation + "CREATE_DRIVER$")
    public RemoteWebDriver createDriver(ObjectNode configuration) throws Exception {
        return DriverConstruction.createDriver(configuration);
    }

    @Given(ObjectRegistrationSteps.objCreation + "CREATE_LOCAL_DRIVER$")
    public RemoteWebDriver createLocalDriver(ObjectNode configuration) throws Exception {
        return DriverConstruction.createLocalDriver(configuration);
    }

    @Given(ObjectRegistrationSteps.objCreation + "CREATE_REMOTE_DRIVER$")
    public RemoteWebDriver createRemoteDriver(ObjectNode configuration) throws Exception {
        return DriverConstruction.createRemoteDriver(configuration);
    }

    @Given(ObjectRegistrationSteps.objAction + "NAVIGATE: (.*)$")
    public Object navigate(String address, Object value) {
        RemoteWebDriver driver = (RemoteWebDriver) value;
        driver.navigate().to(address);
        return value;
    }

    @Given(ObjectRegistrationSteps.objAction + "MAXIMIZE$")
    public Object maximize(Object value) {
        RemoteWebDriver driver = (RemoteWebDriver) value;
        try {
            driver.manage().window().maximize();
        }
        catch (Exception e) {
            System.out.println("Failed to maximize window: " + e.getMessage());
        }
        return value;
    }

    @Given(ObjectRegistrationSteps.objAction + "MINIMIZE$")
    public Object minimize(Object value) {
        RemoteWebDriver driver = (RemoteWebDriver) value;
        try {
            driver.manage().window().minimize();
        } catch (Exception e) {
            System.out.println("Failed to minimize window: " + e.getMessage());
        }
        return value;
    }

    @Given(ObjectRegistrationSteps.objAction + "FULLSCREEN$")
    public Object fullscreen(Object value) {
        RemoteWebDriver driver = (RemoteWebDriver) value;
        driver.manage().window().fullscreen();
        return value;
    }

    @Given(ObjectRegistrationSteps.objAction + "CLEAR_COOKIES$")
    public Object clearCookies(Object value) {
        RemoteWebDriver driver = (RemoteWebDriver) value;
        driver.manage().deleteAllCookies();
        return value;
    }

    @Given(ObjectRegistrationSteps.objAction + "IMPLICIT_WAIT: (\\d+)$")
    public Object implicitWait(long millis, Object value) {
        RemoteWebDriver driver = (RemoteWebDriver) value;
        driver.manage().timeouts().implicitlyWait(Duration.ofMillis(millis));
        return value;
    }

    @Given(ObjectRegistrationSteps.objAction + "PAGE_LOAD_TIMEOUT: (\\d+)$")
    public Object pageLoadTimeout(long millis, Object value) {
        RemoteWebDriver driver = (RemoteWebDriver) value;
        driver.manage().timeouts().pageLoadTimeout(Duration.ofMillis(millis));
        return value;
    }

    @Given(ObjectRegistrationSteps.objAction + "SCRIPT_TIMEOUT: (\\d+)$")
    public Object scriptTimeout(long millis, Object value) {
        RemoteWebDriver driver = (RemoteWebDriver) value;
        driver.manage().timeouts().scriptTimeout(Duration.ofMillis(millis));
        return value;
    }

    @Given(ObjectRegistrationSteps.objAction + "ENABLE_LOCAL_FILE_DETECTOR$")
    public Object enableLocalFileDetector(Object value) {
        RemoteWebDriver driver = (RemoteWebDriver) value;
        driver.setFileDetector(new LocalFileDetector());
        return value;
    }

    @Given(ObjectRegistrationSteps.objAction + "SET_WINDOW_SIZE: (\\d+)x(\\d+)$")
    public Object setWindowSize(int width, int height, Object value) {
        RemoteWebDriver driver = (RemoteWebDriver) value;
        driver.manage().window().setSize(new Dimension(width, height));
        return value;
    }

    @Given(ObjectRegistrationSteps.objAction + "REFRESH$")
    public Object refresh(Object value) {
        RemoteWebDriver driver = (RemoteWebDriver) value;
        driver.navigate().refresh();
        return value;
    }

    @Given(ObjectRegistrationSteps.objAction + "BACK$")
    public Object back(Object value) {
        RemoteWebDriver driver = (RemoteWebDriver) value;
        driver.navigate().back();
        return value;
    }

    @Given(ObjectRegistrationSteps.objAction + "FORWARD$")
    public Object forward(Object value) {
        RemoteWebDriver driver = (RemoteWebDriver) value;
        driver.navigate().forward();
        return value;
    }

    @Given(ObjectRegistrationSteps.objAction + "QUIT_LOCAL_DRIVER$")
    public Object quitLocalDriver(Object value) {
        RemoteWebDriver driver = (RemoteWebDriver) value;
        driver.quit();
        return value;
    }
}