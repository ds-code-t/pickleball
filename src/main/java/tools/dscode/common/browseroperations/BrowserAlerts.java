package tools.dscode.common.browseroperations;

import org.openqa.selenium.Alert;
import org.openqa.selenium.NoAlertPresentException;
import org.openqa.selenium.WebDriver;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Browser-level alert utilities.
 *
 * Handles JavaScript alerts, confirms, and prompts.
 * All methods are static and operate directly on a WebDriver instance.
 */
public final class BrowserAlerts {

    private BrowserAlerts() {
        // utility class
    }

    /**
     * Switches to the active alert.
     *
     * @throws NoAlertPresentException if no alert is present
     */
    public static Alert getAlert(WebDriver driver) {

        return driver.switchTo().alert();
    }

    /**
     * Accepts the currently displayed alert.
     * Selenium's alert is tried first. When that call fails while a dialog still
     * blocks the page, the driver's own connection runs {@code Page.handleJavaScriptDialog}.
     *
     * @throws NoAlertPresentException if no alert is present
     */
    public static void accept(WebDriver driver) {
        handle(driver, true);
    }

    /**
     * Dismisses the currently displayed alert.
     *
     * @throws NoAlertPresentException if no alert is present
     */
    public static void dismiss(WebDriver driver) {
        handle(driver, false);
    }

    /**
     * A JavaScript dialog is open. A null alert is not open.
     * Callers use this to avoid {@code Runtime.addBinding} and page scripts
     * while Chrome will not answer the page socket.
     */
    public static boolean isPresent(WebDriver driver) {
        if (driver == null) return false;
        try {
            return driver.switchTo().alert() != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void handle(WebDriver driver, boolean accept) {
        RuntimeException seleniumFailure = null;
        try {
            Alert alert = driver.switchTo().alert();
            if (alert == null) throw new NoAlertPresentException("no alert");
            if (accept) alert.accept();
            else alert.dismiss();
            return;
        } catch (RuntimeException failure) {
            seleniumFailure = failure;
        }
        if (handleJavaScriptDialog(driver, accept)) return;
        if (seleniumFailure instanceof NoAlertPresentException missing) throw missing;
        throw seleniumFailure;
    }

    /**
     * Chromedriver's connection, not Pickleball's page socket.
     * Returns false when this driver has no CDP command or the command fails.
     */
    static boolean handleJavaScriptDialog(WebDriver driver, boolean accept) {
        if (driver == null) return false;
        try {
            Method method = driver.getClass().getMethod("executeCdpCommand", String.class, Map.class);
            method.invoke(driver, "Page.handleJavaScriptDialog", Map.of("accept", accept));
            return true;
        } catch (ReflectiveOperationException failure) {
            return false;
        }
    }

    /**
     * Returns the text of the currently displayed alert.
     *
     * @throws NoAlertPresentException if no alert is present
     */
    public static String getText(WebDriver driver) {

        return getAlert(driver).getText();
    }

    /**
     * Sends text to a prompt-style alert.
     *
     * @throws NoAlertPresentException if no alert is present
     */
    public static void sendKeys(WebDriver driver, String value) {
        getAlert(driver).sendKeys(value);
    }

    /**
     * Accepts the alert and returns its text.
     *
     * Useful for confirm dialogs where text must be captured.
     *
     * @throws NoAlertPresentException if no alert is present
     */
    public static String acceptAndGetText(WebDriver driver) {
        Alert alert = getAlert(driver);
        String text = alert.getText();
        alert.accept();
        return text;
    }

    /**
     * Dismisses the alert and returns its text.
     *
     * @throws NoAlertPresentException if no alert is present
     */
    public static String dismissAndGetText(WebDriver driver) {
        Alert alert = getAlert(driver);
        String text = alert.getText();
        alert.dismiss();
        return text;
    }

}
