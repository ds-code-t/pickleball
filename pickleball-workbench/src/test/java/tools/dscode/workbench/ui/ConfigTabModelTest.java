package tools.dscode.workbench.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.RunConfigs;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTabModelTest {
    @TempDir
    Path project;

    @Test
    void theConfigTabReadsAndSavesOnlyThatRunsCopy() throws Exception {
        Path projectFile = project.resolve("src/test/resources/configs/CHROME.yaml");
        Files.createDirectories(projectFile.getParent());
        Files.writeString(projectFile, "marker: project\n", StandardCharsets.UTF_8);
        Files.createDirectories(RunConfigs.directory(project, "run-a"));
        Files.createDirectories(RunConfigs.directory(project, "run-b"));
        Files.writeString(RunConfigs.directory(project, "run-a").resolve("CHROME.yaml"), "marker: project\n");
        Files.writeString(RunConfigs.directory(project, "run-a").resolve("URL.yaml"), "home: a\n");
        Files.writeString(RunConfigs.directory(project, "run-b").resolve("CHROME.yaml"), "marker: project\n");
        String projectBefore = Files.readString(projectFile);

        ConfigTabModel tab = new ConfigTabModel();
        tab.show(project, "run-a", true);
        assertEquals(List.of("CHROME.yaml", "URL.yaml"), tab.files());
        tab.select("CHROME.yaml");
        assertTrue(tab.text().contains("marker: project"));
        assertEquals(projectBefore, Files.readString(projectFile));

        tab.setDraft("marker: from-tab\n");
        tab.save();
        assertTrue(tab.status().contains("Saved CHROME.yaml"));
        assertTrue(Files.readString(RunConfigs.directory(project, "run-a").resolve("CHROME.yaml")).contains("from-tab"));
        assertEquals(projectBefore, Files.readString(projectFile));
        assertTrue(Files.readString(RunConfigs.directory(project, "run-b").resolve("CHROME.yaml")).contains("marker: project"));
        assertFalse(Files.readString(RunConfigs.directory(project, "run-b").resolve("CHROME.yaml")).contains("from-tab"));

        ConfigTabModel other = new ConfigTabModel();
        other.show(project, "run-b", true);
        other.select("CHROME.yaml");
        assertFalse(other.text().contains("from-tab"));
        assertTrue(other.text().contains("marker: project"));

        tab.show(project, "run-a", false);
        tab.select("CHROME.yaml");
        tab.setDraft("marker: read-only-edit\n");
        assertThrows(IllegalStateException.class, tab::save);
        assertTrue(Files.readString(RunConfigs.directory(project, "run-a").resolve("CHROME.yaml")).contains("from-tab"));
        assertEquals(projectBefore, Files.readString(projectFile));
        assertThrows(IllegalArgumentException.class, () ->
                RunConfigs.write(project, "run-a", "../src/test/resources/configs/CHROME.yaml", "marker: escaped\n"));
        assertEquals(projectBefore, Files.readString(projectFile));
    }
}
