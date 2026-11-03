package com.example.pickleball;

import io.cucumber.java.en.Given;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import tools.dscode.common.composite.ElementOperations;
import tools.dscode.common.seleniumextensions.ElementWrapper;
import tools.dscode.coredefinitions.BrowserSteps;

/**
 * Stale recovery for the element snapshot. The same steps run with
 * {@code pkb_composite} on or off. Lookup goes through {@link ElementOperations}
 * so the run's backend is the one that finds the node.
 */
public final class ElementSnapshotStaleSteps {

    @Given("^the live element refreshes after it is re-rendered$")
    public void liveElementRefreshesAfterItIsReRendered() {
        WebDriver driver = BrowserSteps.getCurrentDriver();
        WebElement live = ElementOperations.findElement(driver, By.id("live"));
        ElementWrapper wrapper = ElementWrapper.forResolvedElement(driver, live);
        if (!wrapper.snapshot().text().contains("Before")) {
            throw new AssertionError("snapshot before re-render was: " + wrapper.snapshot().text());
        }
        if (!"#live".equals(wrapper.snapshot().stableSelector())) {
            throw new AssertionError("stable selector was: " + wrapper.snapshot().stableSelector());
        }
        WebElement button = ElementOperations.findElement(driver, By.id("replace"));
        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", button);
        String text;
        try {
            text = wrapper.getText();
        } catch (RuntimeException staleOrRefresh) {
            wrapper.refreshSnapshot();
            text = wrapper.snapshot().text();
        }
        if (text == null || !text.contains("After")) {
            wrapper.refreshSnapshot();
            text = wrapper.snapshot().text();
        }
        if (text == null || !text.contains("After")) {
            throw new AssertionError("refreshed text was: " + text
                    + " snapshot=" + wrapper.snapshot().text()
                    + " selector=" + wrapper.snapshot().stableSelector());
        }
        if (!wrapper.snapshot().text().contains("After")) {
            throw new AssertionError("snapshot text was not refreshed: " + wrapper.snapshot().text());
        }
        if (wrapper.snapshot().siblingIndex() != 3) {
            throw new AssertionError("siblingIndex was " + wrapper.snapshot().siblingIndex());
        }
        if (wrapper.snapshot().sameTagIndex() != 1) {
            throw new AssertionError("sameTagIndex was " + wrapper.snapshot().sameTagIndex());
        }
    }
}
