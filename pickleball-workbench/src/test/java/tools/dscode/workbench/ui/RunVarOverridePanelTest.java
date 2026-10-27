package tools.dscode.workbench.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.workbench.discover.LastDiscoverSnapshot;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunVarOverridePanelTest {
    @Test
    void booleanLikeKeysUseToggles() {
        assertEquals(RunVarOverridePanel.Kind.BOOLEAN, RunVarOverridePanel.kindFor("pkb_debugBrowser", "false"));
        assertEquals(RunVarOverridePanel.Kind.BOOLEAN, RunVarOverridePanel.kindFor("pkb_rp_enable", "true"));
        assertEquals(RunVarOverridePanel.Kind.BOOLEAN, RunVarOverridePanel.kindFor("pkb_compositereport", "false"));
        assertEquals(RunVarOverridePanel.Kind.BOOLEAN, RunVarOverridePanel.kindFor("pkb_scenarioreport", "true"));
        assertEquals(RunVarOverridePanel.Kind.BOOLEAN, RunVarOverridePanel.kindFor("pkb_custom", "yes"));
    }

    @Test
    void knownEnumsUseCombos() {
        assertEquals(RunVarOverridePanel.Kind.ENUM, RunVarOverridePanel.kindFor("pkb_browser", "chrome"));
        assertEquals(RunVarOverridePanel.Kind.ENUM, RunVarOverridePanel.kindFor("pkb_reportingmode", "diagnostic"));
        assertEquals(RunVarOverridePanel.Kind.ENUM, RunVarOverridePanel.kindFor("pkb_reportretention", "failed"));
        assertEquals(RunVarOverridePanel.Kind.ENUM, RunVarOverridePanel.kindFor("pkb_loglevel", "warn"));
        assertEquals(RunVarOverridePanel.Kind.ENUM, RunVarOverridePanel.kindFor("pkb_parallel", "auto"));
        assertEquals(RunVarOverridePanel.BROWSER_CHOICES, RunVarOverridePanel.enumChoices("pkb_browser"));
    }

    @Test
    void otherKeysStayText() {
        assertEquals(RunVarOverridePanel.Kind.TEXT, RunVarOverridePanel.kindFor("pkb_tags", "@smoke"));
        assertEquals(RunVarOverridePanel.Kind.TEXT, RunVarOverridePanel.kindFor("pkb_glue", "com.example"));
        assertNull(RunVarOverridePanel.enumChoices("pkb_tags"));
    }

    @Test
    void panelDoesNotWriteASealedSnapshot(@TempDir Path tempDir) {
        new RunVarOverridePanel(tempDir);
        assertNull(LastDiscoverSnapshot.read(tempDir));
    }
}
