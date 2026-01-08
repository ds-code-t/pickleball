package tools.dscode.common.browseroperations;

import org.openqa.selenium.Alert;
import org.openqa.selenium.NoAlertPresentException;
import org.openqa.selenium.WebDriver;

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
     *
     * @throws NoAlertPresentException if no alert is present
     */
    public static void accept(WebDriver driver) {
        getAlert(driver).accept();
    }

    /**
     * Dismisses the currently displayed alert.
     *
     * @throws NoAlertPresentException if no alert is present
     */
    public static void dismiss(WebDriver driver) {
        getAlert(driver).dismiss();
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

    /**
     * Checks whether an alert is currently present.
     */
    public static boolean isPresent(WebDriver driver) {

        try {
            driver.switchTo().alert();

            return true;
        } catch (NoAlertPresentException e) {

            return false;
        }
    }
}
