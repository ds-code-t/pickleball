package tools.dscode.workbench.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchUiSettingsTest {
    @TempDir
    Path project;

    @Test
    void loadAndSaveRoundTripWithoutBlockActionLog() {
        WorkbenchUiSettings fresh = WorkbenchUiSettings.load(project);
        fresh.save(project);
        assertTrue(Files.isRegularFile(WorkbenchUiSettings.file(project)));
        WorkbenchUiSettings.load(project);
        assertTrue(Files.isRegularFile(WorkbenchUiSettings.file(project)));
    }
}
