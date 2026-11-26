package tools.dscode.engine.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.store.StoreDatabase;
import tools.dscode.store.sql.SqlConnections;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A visual target matches the step regex after substitution and replays the artifact identity chain. */
class VisualTargetTest {
    @TempDir
    Path project;

    @AfterEach
    void release() {
        SqlConnections.closeProject(project);
    }

    @Test
    void matchingIdentityPasses() throws Exception {
        writeArtifact("art-save", "save", "<button id=\"save\">Save</button>");
        VisualTarget.play(project, "art-save", "click save", "save", Map.of());
    }

    @Test
    void wrongChainFailsAfterTheRegexMatches() throws Exception {
        writeArtifact("art-wrong", "nope", "<button id=\"save\">Save</button>");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> VisualTarget.play(project, "art-wrong", "click save", "save", Map.of()));
        assertTrue(failure.getMessage().contains("error:"), failure.getMessage());
        assertTrue(failure.getMessage().contains("fix:"), failure.getMessage());
    }

    @Test
    void placeholderSubstitutesBeforeTheRegex() throws Exception {
        writeArtifact("art-city", "save", "<button id=\"save\">Save</button>");
        VisualTarget.play(project, "art-city", "the city is {{city}}", "the city is Austin", Map.of("city", "Austin"));
    }

    @Test
    void missingArtifactIsAnError() {
        open();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> VisualTarget.play(project, "missing", "click save", "save", Map.of()));
        assertTrue(failure.getMessage().contains("error:"), failure.getMessage());
        assertTrue(failure.getMessage().contains("fix:"), failure.getMessage());
    }

    @Test
    void rawScreenshotWithoutAChainIsAnError() throws Exception {
        Path file = project.resolve("reports/plain.png");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "PNGPIXELS", StandardCharsets.UTF_8);
        try (StoreDatabase database = StoreDatabase.open(project)) {
            insertRun(database);
            try (PreparedStatement statement = database.connection().prepareStatement("""
                    INSERT INTO artifacts(artifact_id, run_id, kind, path, retained_at, sandbox)
                    VALUES ('art-raw', 'run-visual', 'screenshot', 'reports/plain.png', '2026-11-26T01:00:00Z', '')
                    """)) {
                statement.executeUpdate();
            }
        }
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> VisualTarget.play(project, "art-raw", "click save", "save", Map.of()));
        assertTrue(failure.getMessage().contains("error:"), failure.getMessage());
        assertTrue(failure.getMessage().contains("no identity chain"), failure.getMessage());
        assertTrue(failure.getMessage().contains("fix:"), failure.getMessage());
    }

    private void writeArtifact(String id, String locator, String html) throws Exception {
        Path file = project.resolve("reports/" + id + ".json");
        Files.createDirectories(file.getParent());
        String body = """
                {"format":"pickleball.visual-target","html":"%s","identity":{"chain":[{"kind":"stable","locator":"id=%s"}]}}
                """.formatted(html.replace("\"", "\\\""), locator);
        Files.writeString(file, body, StandardCharsets.UTF_8);
        try (StoreDatabase database = StoreDatabase.open(project)) {
            insertRun(database);
            try (PreparedStatement statement = database.connection().prepareStatement("""
                    INSERT INTO artifacts(artifact_id, run_id, kind, path, retained_at, sandbox)
                    VALUES (?, 'run-visual', 'dom', ?, '2026-11-26T01:00:00Z', '')
                    """)) {
                statement.setString(1, id);
                statement.setString(2, "reports/" + id + ".json");
                statement.executeUpdate();
            }
        }
    }

    private void open() {
        try (StoreDatabase ignored = StoreDatabase.open(project)) {
            // Creates the store so a missing artifact is a lookup miss.
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void insertRun(StoreDatabase database) throws Exception {
        try (PreparedStatement statement = database.connection().prepareStatement("""
                INSERT INTO runs(
                    run_id, started_at, status, scenario, note, sandbox,
                    git_commit, feature_path, commit_sha, head_id, parent_commit, parent_head_id)
                VALUES ('run-visual', '2026-11-26T01:00:00Z', 'PASSED', 'visual', '', 'main',
                        '', '', '', 'current', '', '')
                ON CONFLICT(run_id) DO NOTHING
                """)) {
            statement.executeUpdate();
        }
    }
}
