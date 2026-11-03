package com.example.pickleball;

import io.cucumber.java.en.Given;
import org.openqa.selenium.WebDriver;
import tools.dscode.common.composite.ElementOperations;
import tools.dscode.control.api.PageElementQuery;
import tools.dscode.coredefinitions.BrowserSteps;

/**
 * Counting goes through one {@link ElementOperations#describe} batch.
 * The same step runs with composite on or off.
 */
public final class DomBatchCountSteps {

    @Given("^the visible \"([^\"]*)\" ([A-Za-z]+) count is (\\d+) from one batch$")
    public void visibleCountIsOneBatch(String text, String category, int expected) {
        WebDriver driver = BrowserSteps.getCurrentDriver();
        ElementOperations.resetBatchCallsForTests();
        PageElementQuery.Hit hit = PageElementQuery.find(driver, category, text);
        if (hit.visibleCount() != expected || hit.rawMatchCount() < expected) {
            throw new AssertionError("error: visible count for the \"" + text + "\" " + category
                    + " was " + hit.visibleCount() + " raw " + hit.rawMatchCount()
                    + ". fix: the forms page has one visible Submit Form button.");
        }
        int batches = ElementOperations.batchCallsForTests();
        if (batches != 1) {
            throw new AssertionError("error: describe ran " + batches
                    + " times. fix: counting must be one ElementOperations.describe call.");
        }
    }
}
