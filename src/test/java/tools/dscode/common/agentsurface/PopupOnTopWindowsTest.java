package tools.dscode.common.agentsurface;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PKB-HCR 2026-11-28 item 24: popup {@code alwaysOnTop} failed with SetWindowPos exit 1 on Windows. */
class PopupOnTopWindowsTest {
    @Test
    void theWindowsCommandHasNoDoubleQuoteForJavaToMangle() {
        PopupOnTop.Attempt windows = PopupOnTop.plan("Windows 11", "", 42);
        String script = windows.command().get(windows.command().size() - 1);
        assertFalse(script.contains("\""), "an embedded double quote is split by the Windows command line: " + script);
        assertTrue(script.contains("SetWindowPos"), script);
        assertTrue(script.contains("ParentProcessId="), "a child process's window is used too");
        assertEquals("no window found for the popup browser process", PopupOnTop.exitReason("SetWindowPos", 2, List.of()));
        assertEquals("SetWindowPos returned false", PopupOnTop.exitReason("SetWindowPos", 3, List.of()));
        assertEquals("exit 1", PopupOnTop.exitReason("wmctrl", 1, List.of()));
    }

    /** The script parses and runs: this test JVM has no window, so the answer is exit 2, not a PowerShell error. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void theScriptRunsOnWindows() {
        PopupOnTop.Attempt attempt = PopupOnTop.apply(ProcessHandle.current().pid());
        assertNotEquals("exit 1", attempt.reason(), attempt.toString());
        assertFalse(attempt.reason().contains("timed out"), attempt.toString());
    }
}
