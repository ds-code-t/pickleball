package tools.dscode.workbench.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.WorkbenchFeatureIndex;
import tools.dscode.workbench.sync.WorkbenchManifest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchSelectionTest {
    @TempDir
    Path project;

    @Test
    void manifestSourceRootIsPartOfTheIndex() throws Exception {
        Path extra = project.resolve("generated");
        Files.createDirectories(extra);
        Files.createDirectories(project.resolve("src/test/resources/features"));
        Files.writeString(project.resolve("src/test/resources/features/plain.feature"), """
                Feature: Plain
                  Scenario: Not generated
                    Given stay
                """);
        Files.writeString(extra.resolve("gen.feature"), """
                @gen
                Feature: Gen
                  Scenario: From manifest
                    Given generated
                """);
        WorkbenchManifest manifest = new WorkbenchManifest(
                1,
                project.toString(),
                "maven",
                "maven",
                List.of(extra.toString()),
                List.of(),
                List.of(),
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        WorkbenchFeatureIndex.Selection selection = LaunchSelection.select(
                project, manifest, "@gen", null, null, List.of(), true
        );
        assertEquals(1, selection.hits().size());
        assertEquals("From manifest", selection.hits().getFirst().scenarioName());
        assertTrue(selection.narrowed());
        assertTrue(selection.featureProperty().contains("gen.feature"));
        assertFalse(selection.featureProperty().contains("plain.feature"));
    }
}
