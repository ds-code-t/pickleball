package tools.dscode.workbench.ui;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Workbench GUI preferences. Not part of the execution RunVar profile. */
public final class WorkbenchUiSettings {
    public boolean showBlockActionLog = true;

    private static final ObjectMapper JSON = new ObjectMapper();

    public static Path file(Path projectRoot) {
        return projectRoot.resolve(".pickleball").resolve("workbench").resolve("ui-settings.json");
    }

    public static WorkbenchUiSettings load(Path projectRoot) {
        Path file = file(projectRoot);
        if (!Files.isRegularFile(file)) return new WorkbenchUiSettings();
        try {
            WorkbenchUiSettings settings = JSON.readValue(file.toFile(), WorkbenchUiSettings.class);
            return settings == null ? new WorkbenchUiSettings() : settings;
        } catch (IOException ignored) {
            return new WorkbenchUiSettings();
        }
    }

    public void save(Path projectRoot) {
        Path file = file(projectRoot);
        try {
            Files.createDirectories(file.getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), this);
        } catch (IOException ignored) {
            // GUI prefs are best-effort.
        }
    }
}