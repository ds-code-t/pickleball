package com.example.pickleball;

import io.cucumber.core.runner.CurrentScenarioState;
import org.junit.jupiter.api.Test;
import tools.dscode.control.api.ControlCallResult;
import tools.dscode.control.api.ControlCallStatus;
import tools.dscode.control.api.DynamicControl;

import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AreaCElementChecks {

    @Test
    void missingContextClickFails() {
        navigate("URL.catalog");
        expectFailure(
                ", in the \"Primary Queue\" Test Panel, click the \"Missing Area C\" Button",
                "No elements found"
        );
    }

    @Test
    void actionMissesFail() {
        navigate("URL.forms");
        expectFailure(", hover the \"Missing Area C\" Button", "No elements found");
        expectFailure(", move to the \"Missing Area C\" Button", "No elements found");
        expectFailure(", double click the \"Missing Area C\" Button", "No elements found");
        expectFailure(", right click the \"Missing Area C\" Button", "No elements found");
        expectFailure(", scroll the \"Missing Area C\" Button", "No elements found");
        expectFailure(", overwrite \"x\" in the \"Missing Area C\" Textbox", "No elements found");
        expectFailure(", clear the \"Missing Area C\" Textbox", "No elements found");
        expectFailure(", select \"Premium\" in the \"Missing Area C\" Dropdown", "No elements found");
        expectFailure(", attach the InternalFileInput \"customers.yaml\"");
        expectFailure(", create and attach the InternalFileInput \"area-c-note.txt\"");
        expectFailure(
                ", dragAndDrop the \"Missing Area C\" Button the \"Other Missing Area C\" Button"
        );
    }

    @Test
    void assertionMissesFailAlone() {
        expectFailure(", ensure \"Phoenix\" contains \"Tempe\"");
        expectFailure(", ensure 1 is greater than 5");
        expectFailure(", ensure 5 is less than 1");
        expectFailure(", ensure 1 is greater than or equal to 2");
        expectFailure(", ensure 5 is less than or equal to 1");
        navigate("URL.forms");
        expectFailure(", ensure the \"Locked Action\" Button is enabled");
        expectFailure(", ensure the \"Submit Form\" Button is disabled");
        expectFailure(", ensure the \"Receive Updates\" Checkbox is on");
        succeed(", click the \"Receive Updates\" Checkbox");
        expectFailure(", ensure the \"Receive Updates\" Checkbox is off");
    }

    @Test
    void missingDialogFails() {
        navigate("URL.dialogs");
        expectFailure(", accept the Alert", "Alert");
    }

    @Test
    void unknownAndLowercaseKeysFail() {
        navigate("URL.keyboard");
        expectFailure(
                ", press \"NOT_A_KEY\" in the \"Keyboard Input\" Textbox",
                "Unknown key: NOT_A_KEY"
        );
        expectFailure(
                ", press \"control[a]\" in the \"Keyboard Input\" Textbox",
                "case-sensitive"
        );
    }

    @Test
    void docStringQueryAndRejectedSelectionsFail() {
        expectFailure(
                ", in the \"<data:Data element native fixtures.Structured sources.jsonDocument>\" Doc String, save \"x\" as \"docQuery\"",
                "Data Doc String has no query runtime"
        );
        succeed(", save \"<data:Data element native fixtures.Structured sources.listCollection>\" JSON Data as \"areaLists\"");
        expectFailure(
                ", save every \"<areaLists[]>\" List with first equaling \"missing\" as \"everyMiss\"",
                "No Lists matched"
        );
        expectFailure(
                ", save every 3rd \"<areaLists[]>\" List with first equaling \"alpha\" as \"everyThirdMiss\"",
                "every 3rd"
        );
        expectFailure(
                ", save none \"<areaLists[]>\" List as \"noneLists\"",
                "none"
        );
    }

    private static void navigate(String urlKey) {
        succeed("navigate to: " + urlKey);
    }

    private static void succeed(String step) {
        ControlCallResult<Object> result = DynamicControl.executeStep(step);
        assertTrue(result.successful(), () -> step + "\n" + detail(result));
        assertFalse(requireScenario().isScenarioFailed());
    }

    private static void expectFailure(String step) {
        expectFailure(step, null);
    }

    private static void expectFailure(String step, String snippet) {
        ControlCallResult<Object> result = DynamicControl.executeStep(step);
        String text = detail(result);
        assertEquals(ControlCallStatus.FAILED, result.status(), step + "\n" + text);
        if (snippet != null) {
            assertTrue(text.contains(snippet), step + "\n" + text);
        }
        assertFalse(requireScenario().isScenarioFailed(), step);
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
}
