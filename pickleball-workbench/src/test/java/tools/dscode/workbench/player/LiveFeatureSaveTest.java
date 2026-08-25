package tools.dscode.workbench.player;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveFeatureSaveTest {
    @TempDir
    Path project;

    @Test
    void demoBufferIsUnsavableAndLoadDoesNotWriteFiles() throws Exception {
        Path feature = project.resolve("login.feature");
        Files.writeString(feature, """
                Feature: Sign in
                  Scenario: Valid password
                    Given a user
                  Scenario: Locked account
                    Given a lock
                """);
        String original = Files.readString(feature);
        LivePlaybackCoordinator playback = new LivePlaybackCoordinator(LiveScenarioPlayer.interactiveBuffer());
        WorkbenchSavePreview demo = LiveFeatureSave.preview(playback);
        assertFalse(demo.savable());
        assertTrue(demo.summary().contains("session-only"));

        playback.loadScenario(
                List.of("Feature: Sign in", "", "Scenario: Locked account", "  Given a lock"),
                feature,
                "Locked account",
                4,
                5
        );
        assertEquals(original, Files.readString(feature));
        assertTrue(LiveFeatureSave.preview(playback).savable());
    }

    @Test
    void writeSplicesOnlyTheOriginatingScenario() throws Exception {
        Path feature = project.resolve("login.feature");
        Files.writeString(feature, """
                Feature: Sign in
                  Scenario: Valid password
                    Given a user
                  Scenario: Locked account
                    Given a lock
                """);
        LivePlaybackCoordinator playback = new LivePlaybackCoordinator(new LiveScenarioPlayer(List.of(
                "Feature: Sign in",
                "",
                "Scenario: Locked account",
                "  Given a lock",
                "  And stay locked"
        )));
        playback.loadScenario(
                List.of(
                        "Feature: Sign in",
                        "",
                        "Scenario: Locked account",
                        "  Given a lock",
                        "  And stay locked"
                ),
                feature,
                "Locked account",
                4,
                5
        );

        WorkbenchSaveResult result = LiveFeatureSave.write(playback);
        assertTrue(result.written());
        String saved = Files.readString(feature);
        assertTrue(saved.contains("Scenario: Valid password"));
        assertTrue(saved.contains("Given a user"));
        assertTrue(saved.contains("And stay locked"));
        assertTrue(saved.contains("Scenario: Locked account"));
    }

    @Test
    void denyPathNeverCallsWrite() throws Exception {
        Path feature = project.resolve("only.feature");
        Files.writeString(feature, """
                Feature: Only
                  Scenario: Keep me
                    Given original
                """);
        String original = Files.readString(feature);
        WorkbenchSaveResult denied = WorkbenchSaveResult.denied();
        assertFalse(denied.written());
        assertEquals(original, Files.readString(feature));
    }
}
