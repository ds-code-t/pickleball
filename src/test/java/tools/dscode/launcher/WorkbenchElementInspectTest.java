package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
    }

    private static void assertEqualsPhrase(WorkbenchElementInspect.Hit found) {
        assertTrue(found.phrase().equals("the \"Save\" Button"), found.phrase());
    }
}
