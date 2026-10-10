package com.example.pickleball;

import io.cucumber.core.runner.CurrentScenarioState;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.remote.RemoteWebDriver;
import tools.dscode.common.reporting.logging.Entry;
import tools.dscode.common.reporting.logging.LogForwarder;
import tools.dscode.common.seleniumextensions.AllContextsScan;
import tools.dscode.control.api.ControlCallResult;
import tools.dscode.control.api.ControlCallStatus;
import tools.dscode.control.api.DynamicControl;
import tools.dscode.coredefinitions.BrowserSteps;

import java.util.HashSet;
import java.util.Set;

import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LoadingContextChecks {

    private static final String FOREIGN =
            "Loading matched in another frame and cannot be clicked, entered, saved, read, or used as a context.";

    private static final String[] POSITIVE_CASES = {
            "busy",
            "progressbar",
            "data-loading",
            "data-state",
            "progress-empty",
            "progress-zero",
            "progress-partial",
            "meter-busy",
            "status-busy",
            "token-loading",
            "token-spinner",
            "token-both"
    };

    @Test
    void hiddenAndNonLoadingMarkersDoNotCount() {
        open("quiet");
        succeed(", ensure the Loading is not displayed");
    }

    @Test
    void eachBuiltinLoadingPatternCountsWhenDisplayed() {
        open("positive");
        for (String id : POSITIVE_CASES) {
            showOnly(id);
            succeed(", ensure the Loading is displayed");
        }
    }

    @Test
    void nestedFramesCountAndAButtonThereDoesNot() {
        open("frames");
        succeed(", ensure the Loading is displayed");
        expectFailure(", click the \"Inside Only\" Button", "No elements found");
        succeed(", click the \"Stay\" Button");
    }

    @Test
    void scanRestoresTheFrameItStartedIn() {
        open("frames");
        RemoteWebDriver driver = BrowserSteps.getCurrentDriver();
        driver.switchTo().frame("mid");
        try {
            succeed(", ensure the Loading is displayed");
            succeed(", ensure the \"Mid Only\" Button is displayed");
        } finally {
            driver.switchTo().defaultContent();
        }
    }

    @Test
    void crossOriginFrameIsSkippedAndTheTopDocumentIsRestored() {
        open("frames");
        succeed(", click the \"Stay\" Button");
    }

    @Test
    void anotherFrameCannotBeClickedAndTheTopButtonStillCan() {
        open("foreign");
        expectFailure(", click the Loading", FOREIGN);
        succeed(", click the \"Stay\" Button");
    }

    @Test
    void currentFrameShadowMatchCanBeClicked() {
        open("shadow");
        succeed(", click the Loading");
        succeed(", ensure the \"shadow-clicked\" Text is displayed");
    }

    @Test
    void currentFrameMatchCanBeClicked() {
        open("current");
        succeed(", click the Loading");
        succeed(", ensure the \"current-clicked\" Text is displayed");
    }

    @Test
    void inheritedFrameContextDoesNotHideLoadingOutsideIt() {
        open("panel");
        succeed(", in the \"Panel Frame\" IFrame, ensure the Loading is displayed");
    }

    @Test
    void depthCapWarnsOnceAndSkipsTheDeeperFrame() {
        open("depth-ok");
        assertWarning(", ensure the Loading is displayed", AllContextsScan.DEPTH_WARNING, false);
        open("depth-over");
        assertWarning(", ensure the Loading is not displayed", AllContextsScan.DEPTH_WARNING, true);
    }

    @Test
    void frameCapWarnsOnceAndStillUsesAMatchInsideTheCap() {
        open("frame-cap");
        assertWarning(", ensure the Loading is not displayed", AllContextsScan.FRAME_WARNING, true);
        open("frame-within");
        assertWarning(", ensure the Loading is displayed", AllContextsScan.FRAME_WARNING, true);
    }

    private static void showOnly(String id) {
        BrowserSteps.getCurrentDriver().executeScript(
                "document.querySelectorAll('[data-case]').forEach(function(el){ el.hidden = true; });"
                        + "document.getElementById(arguments[0]).hidden = false;",
                id
        );
    }

    private static void open(String fixture) {
        succeed("navigate to: URL.loading");
        BrowserSteps.getCurrentDriver().get("http://127.0.0.1:8765/loading.html#" + fixture);
    }

    private static void assertWarning(String step, String warning, boolean expected) {
        Set<String> before = entryIds();
        succeed(step);
        String log = newTexts(before);
        if (expected) {
            assertTrue(log.contains(warning), log);
        } else {
            assertFalse(log.contains(warning), log);
        }
    }

    private static void succeed(String step) {
        ControlCallResult<Object> result = DynamicControl.executeStep(step);
        assertTrue(result.successful(), () -> step + "\n" + detail(result));
        assertFalse(requireScenario().isScenarioFailed(), step);
    }

    private static void expectFailure(String step, String snippet) {
        ControlCallResult<Object> result = DynamicControl.executeStep(step);
        String text = detail(result);
        assertTrue(text.contains(snippet), step + "\n" + text);
        assertFalse(requireScenario().isScenarioFailed(), step + "\n" + text);
    }

    private static String detail(ControlCallResult<Object> result) {
        if (result.error() == null) {
            return String.valueOf(result.status());
        }
        return result.error().message() + "\n" + result.error().stackTrace();
    }

    private static CurrentScenarioState requireScenario() {
        CurrentScenarioState state = getCurrentScenarioState();
        assertNotNull(state);
        return state;
    }

    private static Set<String> entryIds() {
        Set<String> ids = new HashSet<>();
        collectIds(LogForwarder.closestEntryToScenario(), ids);
        return ids;
    }

    private static void collectIds(Entry entry, Set<String> ids) {
        if (entry == null || !ids.add(entry.id)) {
            return;
        }
        for (Entry child : entry.children) {
            collectIds(child, ids);
        }
    }

    private static String newTexts(Set<String> before) {
        StringBuilder joined = new StringBuilder();
        appendNew(LogForwarder.closestEntryToScenario(), before, joined, new HashSet<>());
        return joined.toString();
    }

    private static void appendNew(Entry entry, Set<String> before, StringBuilder joined, Set<String> seen) {
        if (entry == null || !seen.add(entry.id)) {
            return;
        }
        if (!before.contains(entry.id) && entry.text != null) {
            if (joined.length() > 0) {
                joined.append('\n');
            }
            joined.append(entry.text);
        }
        for (Entry child : entry.children) {
            appendNew(child, before, joined, seen);
        }
    }
}
