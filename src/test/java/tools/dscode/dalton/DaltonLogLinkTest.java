package tools.dscode.dalton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaltonLogLinkTest {
    @TempDir
    Path project;

    @Test
    void linksComeFromTheArgumentNotTheMessage() throws Exception {
        Capture named = run("dalton", "history", "record", "linked-run",
                "--message=opened the account Account#ada",
                "--link=Account#ada", "--link=Case#12",
                "--project=" + project);
        assertEquals(0, named.code, named.err);
        List<DaltonHistory.LinkRow> links = DaltonHistory.links(project, "linked-run");
        assertEquals(2, links.size(), links.toString());
        assertEquals("Account", links.get(0).type());
        assertEquals("ada", links.get(0).id());
        assertEquals("Case", links.get(1).type());
        assertEquals("12", links.get(1).id());

        Capture plain = run("dalton", "history", "record", "text-only",
                "--message=saw Account#ada in the note",
                "--project=" + project);
        assertEquals(0, plain.code, plain.err);
        assertTrue(DaltonHistory.links(project, "text-only").isEmpty());
        String db = DaltonDatabase.storedText(project);
        assertTrue(db.contains("opened the account Account#ada"));
        assertFalse(db.contains("hunter2"));
    }

    @Test
    void retainedScreenshotsAreLinkedAndNotDeleted() throws Exception {
        Path index = project.resolve("reports/diagnostic-runs/sample/run-index.json");
        Path shot = index.getParent().resolve("shot.png");
        Files.createDirectories(shot.getParent());
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 1, 2, 3};
        Files.write(shot, png);
        String json = """
                {"schemaVersion":1,"runId":"sample","outcome":"PASSED","startedAt":"2020-01-01T00:00:00Z","counts":{"passed":1,"failed":0},"scenarios":[{"name":"Pay"}]}
                """;
        Files.writeString(index, json, StandardCharsets.UTF_8);
        byte[] indexBefore = Files.readAllBytes(index);
        Capture recorded = run("dalton", "history", "record",
                "--from=reports/diagnostic-runs/sample/run-index.json",
                "--project=" + project);
        assertEquals(0, recorded.code, recorded.err);
        assertTrue(recorded.out.contains("artifacts 1"), recorded.out);
        assertEquals(indexBefore.length, Files.readAllBytes(index).length);
        assertEquals(png.length, Files.readAllBytes(shot).length);
        String step = DaltonExecution.recordStep(project, new DaltonExecution.StepDraft(
                "sample", "features/pay.feature|2", "Pay", null, null, 1, "the total is 10", 3, "PASSED", null, null));
        DaltonArtifacts.link(project, "sample", shot, step);
        List<DaltonArtifacts.Artifact> artifacts = DaltonArtifacts.list(project, "sample");
        assertEquals(1, artifacts.size(), artifacts.toString());
        assertEquals("screenshot", artifacts.get(0).kind());
        assertEquals("reports/diagnostic-runs/sample/shot.png", artifacts.get(0).path());
        assertEquals(step, artifacts.get(0).stepExecId());

        Capture retained = run("dalton", "history", "retain", "--days=1", "--max-bytes=1000000", "--project=" + project);
        assertEquals(0, retained.code, retained.err);
        assertTrue(DaltonArtifacts.list(project, "sample").isEmpty());
        assertEquals(png.length, Files.readAllBytes(shot).length);
        assertTrue(new String(Files.readAllBytes(index), StandardCharsets.UTF_8).contains("\"outcome\":\"PASSED\""));
    }

    private static Capture run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = DaltonCli.execute(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
