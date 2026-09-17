package tools.dscode.workbench.nav;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchGoResolverTest {
    @TempDir
    Path project;

    @Test
    void prefersPackCopyAndDoesNotRequireLiveBuffer() throws Exception {
        Path pack = project.resolve("reports/diagnostic-runs/run-1/source/files/features/login.feature");
        Files.createDirectories(pack.getParent());
        Files.writeString(pack, "Feature: pack copy\n");
        Files.createDirectories(project.resolve("features"));
        Files.writeString(project.resolve("features/login.feature"), "Feature: live disk\n");

        WorkbenchGoResolver resolver = new WorkbenchGoResolver(project);
        WorkbenchGoResolver.WorkbenchGoResult result = resolver.resolve(WorkbenchGoLink.fromMap(Map.of(
                "to", "editor",
                "runId", "run-1",
                "path", "features/login.feature",
                "kind", "component",
                "line", 4
        )));
        assertTrue(result.peek());
        assertTrue(result.readOnly());
        assertFalse(result.missing());
        assertEquals(pack.toAbsolutePath().normalize(), result.file().toAbsolutePath().normalize());
        assertEquals("Feature: pack copy\n", Files.readString(result.file()));
    }

    @Test
    void missingFileAndOutsideProjectDoNotResolveAFile() {
        WorkbenchGoResolver resolver = new WorkbenchGoResolver(project);
        WorkbenchGoResolver.WorkbenchGoResult missing = resolver.resolve(WorkbenchGoLink.fromMap(Map.of(
                "path", "features/nope.feature"
        )));
        assertTrue(missing.missing());
        assertNull(missing.file());
        assertFalse(missing.movesUi());

        WorkbenchGoResolver.WorkbenchGoResult outside = resolver.resolve(WorkbenchGoLink.parse("wb://editor?path=../secret.feature"));
        assertTrue(outside.outsideProject() || outside.missing());
        assertNull(outside.file());
    }

    @Test
    void frameworkJavaIsAVisibleMiss() {
        WorkbenchGoResolver resolver = new WorkbenchGoResolver(project);
        WorkbenchGoResolver.WorkbenchGoResult result = resolver.resolve(WorkbenchGoLink.fromMap(Map.of(
                "kind", "java",
                "path", "src/main/java/tools/dscode/coredefinitions/ServiceCallSteps.java"
        )));
        assertTrue(result.missing());
        assertNull(result.file());
        assertTrue(result.message().toLowerCase().contains("framework"));
    }

    @Test
    void parsesWbUri() {
        WorkbenchGoLink link = WorkbenchGoLink.parse("wb://explorer?run=run-9&seq=12&node=n1&path=features/a.feature");
        assertEquals("explorer", link.to());
        assertEquals("run-9", link.runId());
        assertEquals(12, link.eventSeq());
        assertEquals("n1", link.nodeId());
        assertEquals("features/a.feature", link.path());
    }

    @Test
    void explorerWithoutPathIsAPanelTargetNotAMissingFile() {
        WorkbenchGoResolver.WorkbenchGoResult result = new WorkbenchGoResolver(project).resolve(
                WorkbenchGoLink.parse("wb://explorer?run=run-9&seq=12")
        );
        assertEquals("explorer", result.to());
        assertFalse(result.missing());
        assertTrue(result.movesUi());
        assertNull(result.file());
    }
}
