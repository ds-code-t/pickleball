package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchElementInspectTest {
    @TempDir
    Path tempDir;

    @Test
    void dumpAndFindUseTheDictionaryWithoutActing() throws Exception {
        String html = """
                <html><body>
                  <button id="save">Save</button>
                  <button hidden="hidden">Hide</button>
                  <a href="/login">Login</a>
                </body></html>
                """;
        List<WorkbenchElementInspect.Hit> dumped = WorkbenchElementInspect.dump(html);
        assertTrue(dumped.stream().anyMatch(hit -> hit.phrase().equals("the \"Save\" Button")), dumped.toString());
        assertTrue(dumped.stream().anyMatch(hit -> hit.phrase().equals("the \"Login\" Link")), dumped.toString());
        assertTrue(dumped.stream().allMatch(hit -> hit.xpath() != null && !hit.xpath().isBlank()), dumped.toString());
        assertTrue(dumped.stream().allMatch(hit -> hit.visibleCount() <= hit.rawMatchCount()));

        WorkbenchElementInspect.Hit found = WorkbenchElementInspect.find("Button", "Save", html);
        assertEqualsPhrase(found);
        assertFalse(found.xpath().isBlank(), found.xpath());
        assertTrue(found.rawMatchCount() >= found.visibleCount());

        Path file = tempDir.resolve("page.html");
        Files.writeString(file, html, StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream stdout = new java.io.ByteArrayOutputStream();
        int exit = WorkbenchAgentCommands.run(
                new String[]{"find-element", tempDir.toString(), "--category=Button", "--text=Save", "--html=" + file},
                new java.io.PrintStream(stdout, true, StandardCharsets.UTF_8),
                new java.io.PrintStream(new java.io.ByteArrayOutputStream(), true, StandardCharsets.UTF_8)
        );
        assertTrue(exit == 0 || exit == 1);
        String text = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("phrase=the \"Save\" Button"), text);
        assertTrue(text.contains("xpath="), text);
        assertTrue(text.contains("rawMatchCount="), text);
        assertTrue(text.contains("visibleCount="), text);
        assertTrue(text.contains("source=offline-html"), text);

        String html5 = """
                <!DOCTYPE html>
                <html><body>
                  <button id="save">Save</button>
                </body></html>
                """;
        Path html5File = tempDir.resolve("html5.html");
        Files.writeString(html5File, html5, StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream html5Out = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream html5Err = new java.io.ByteArrayOutputStream();
        int html5Exit = WorkbenchAgentCommands.run(
                new String[]{"find-element", tempDir.toString(), "--category=Button", "--text=Save", "--html=" + html5File},
                new java.io.PrintStream(html5Out, true, StandardCharsets.UTF_8),
                new java.io.PrintStream(html5Err, true, StandardCharsets.UTF_8)
        );
        assertTrue(html5Exit == 0 || html5Exit == 1, html5Err.toString(StandardCharsets.UTF_8));
        String html5Text = html5Out.toString(StandardCharsets.UTF_8);
        assertTrue(html5Text.contains("source=offline-html"), html5Text);
        assertTrue(html5Text.contains("phrase=the \"Save\" Button"), html5Text);

        java.io.ByteArrayOutputStream missingErr = new java.io.ByteArrayOutputStream();
        int missing = WorkbenchAgentCommands.run(
                new String[]{"find-element", tempDir.toString(), "--category=Button", "--text=Save"},
                new java.io.PrintStream(new java.io.ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new java.io.PrintStream(missingErr, true, StandardCharsets.UTF_8)
        );
        assertEquals(2, missing);
        assertTrue(missingErr.toString(StandardCharsets.UTF_8).contains("start a session first"));

        java.io.ByteArrayOutputStream pageErr = new java.io.ByteArrayOutputStream();
        int page = WorkbenchAgentCommands.run(
                new String[]{"inspect-page", tempDir.toString()},
                new java.io.PrintStream(new java.io.ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new java.io.PrintStream(pageErr, true, StandardCharsets.UTF_8)
        );
        assertEquals(2, page);
        assertTrue(pageErr.toString(StandardCharsets.UTF_8).contains("start a session first"));
    }

    private static void assertEqualsPhrase(WorkbenchElementInspect.Hit found) {
        assertTrue(found.phrase().equals("the \"Save\" Button"), found.phrase());
    }
}
