package tools.dscode.common.browseroperations;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.Alert;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoAlertPresentException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.edge.EdgeOptions;
import tools.dscode.common.domoperations.MaskQuiet;
import tools.dscode.common.driver.DriverConstruction;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Accept and dismiss stay on the production path while a JavaScript dialog is open.
 * Reverting the default capability, the mask skip, or the CDP fallback fails these tests.
 */
class JavaScriptDialogTest {
    @Test
    void chromeAndEdgeLeaveTheDialogOpenUnlessTheProjectSetsTheCapability() {
        ObjectNode empty = JsonNodeFactory.instance.objectNode();
        assertTrue(prompt(DriverConstruction.buildChromeOptions(empty, empty, false)).contains("ignore"));
        assertTrue(prompt(DriverConstruction.buildEdgeOptions(empty, empty, false)).contains("ignore"));

        ObjectNode driver = JsonNodeFactory.instance.objectNode();
        driver.putObject("capabilities").put(DriverConstruction.UNHANDLED_PROMPT, "dismiss");
        ChromeOptions chrome = DriverConstruction.buildChromeOptions(driver, empty, false);
        EdgeOptions edge = DriverConstruction.buildEdgeOptions(driver, empty, false);
        assertTrue(prompt(chrome).contains("dismiss"), prompt(chrome));
        assertTrue(prompt(edge).contains("dismiss"), prompt(edge));
    }

    @Test
    void maskCheckDoesNotRunAScriptWhileADialogIsOpen() {
        AtomicInteger scripts = new AtomicInteger();
        WebDriver driver = driver(true, scripts, new ArrayList<>());
        WebElement element = (WebElement) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{WebElement.class},
                (proxy, method, args) -> null);
        MaskQuiet.Wait wait = MaskQuiet.await(driver, element, 1_000);
        assertEquals(0, wait.waitedMs());
        assertEquals(0, scripts.get());
    }

    @Test
    void maskCheckStillRunsWhenNoDialogIsOpen() {
        AtomicInteger scripts = new AtomicInteger();
        WebDriver driver = driver(false, scripts, new ArrayList<>());
        WebElement element = (WebElement) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{WebElement.class},
                (proxy, method, args) -> null);
        MaskQuiet.await(driver, element, 1_000);
        assertEquals(1, scripts.get());
    }

    @Test
    void acceptUsesTheSeleniumAlertAndDoesNotCallTheDriverConnection() {
        List<String> cdp = new ArrayList<>();
        AtomicInteger accepted = new AtomicInteger();
        WebDriver driver = driver(true, new AtomicInteger(), cdp, accepted, false);
        BrowserAlerts.accept(driver);
        assertEquals(1, accepted.get());
        assertTrue(cdp.isEmpty(), cdp.toString());
    }

    @Test
    void acceptAndDismissFallBackToHandleJavaScriptDialogWhenSeleniumCannotSeeTheAlert() {
        List<String> cdp = new ArrayList<>();
        WebDriver driver = driver(false, new AtomicInteger(), cdp, new AtomicInteger(), true);
        BrowserAlerts.accept(driver);
        BrowserAlerts.dismiss(driver);
        assertEquals(List.of("Page.handleJavaScriptDialog:true", "Page.handleJavaScriptDialog:false"), cdp);
    }

    @Test
    void aMissingDialogStillThrowsWhenTheDriverConnectionCannotHandleIt() {
        WebDriver driver = driver(false, new AtomicInteger(), new ArrayList<>(), new AtomicInteger(), false);
        assertThrows(NoAlertPresentException.class, () -> BrowserAlerts.accept(driver));
    }

    private static String prompt(Capabilities options) {
        Object value = options.getCapability(DriverConstruction.UNHANDLED_PROMPT);
        return value == null ? "" : String.valueOf(value).toLowerCase(Locale.ROOT);
    }

    private WebDriver driver(boolean dialogOpen, AtomicInteger scripts, List<String> cdp) {
        return driver(dialogOpen, scripts, cdp, new AtomicInteger(), false);
    }

    private WebDriver driver(
            boolean dialogOpen,
            AtomicInteger scripts,
            List<String> cdp,
            AtomicInteger accepted,
            boolean cdpSucceeds
    ) {
        return (WebDriver) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{DialogDriver.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "switchTo" -> locator(dialogOpen, accepted);
                    case "executeScript" -> {
                        scripts.incrementAndGet();
                        yield Boolean.FALSE;
                    }
                    case "executeCdpCommand" -> {
                        cdp.add(args[0] + ":" + ((Map<?, ?>) args[1]).get("accept"));
                        if (!cdpSucceeds) throw new UnsupportedOperationException("cdp closed");
                        yield Map.of();
                    }
                    case "manage" -> java.lang.reflect.Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[]{WebDriver.Options.class},
                            (options, called, optionArgs) -> {
                                if ("timeouts".equals(called.getName())) {
                                    return java.lang.reflect.Proxy.newProxyInstance(
                                            getClass().getClassLoader(),
                                            new Class<?>[]{WebDriver.Timeouts.class},
                                            (timeouts, timeoutCall, timeoutArgs) -> timeouts);
                                }
                                return null;
                            });
                    case "toString" -> "chrome";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> null;
                });
    }

    private Object locator(boolean dialogOpen, AtomicInteger accepted) {
        Alert alert = (Alert) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Alert.class},
                (proxy, method, args) -> {
                    if ("accept".equals(method.getName()) || "dismiss".equals(method.getName())) {
                        accepted.incrementAndGet();
                    }
                    return null;
                });
        return java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{WebDriver.TargetLocator.class},
                (proxy, method, args) -> {
                    if (!"alert".equals(method.getName())) return null;
                    if (!dialogOpen) throw new NoAlertPresentException("no alert");
                    return alert;
                });
    }

    /** Selenium Chrome and Edge expose this method. The proxy must too, or the fallback cannot be invoked. */
    private interface DialogDriver extends WebDriver, JavascriptExecutor {
        Map<String, Object> executeCdpCommand(String command, Map<String, Object> params);
    }
}
