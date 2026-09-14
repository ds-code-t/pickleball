package tools.dscode.workbench.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchUiSettingsTest {
    @TempDir
    Path project;

    @Test
    void blockActionLogDefaultsOnAndPersists() {
        WorkbenchUiSettings fresh = WorkbenchUiSettings.load(project);
        assertTrue(fresh.showBlockActionLog);
        fresh.showBlockActionLog = false;
        fresh.save(project);
        assertFalse(WorkbenchUiSettings.load(project).showBlockActionLog);
    }
}