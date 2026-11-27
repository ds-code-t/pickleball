package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One {@code spine} help entry covers session verbs and warm-spine verbs.
 * {@code help spine} and {@code --help} both come from the launcher process.
 */
class SpineHelpMergeTest {
    @TempDir
    Path project;

    @Test
    void helpClassLoadsBothSpineHalves() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = WorkbenchCliHelp.answer(
                new String[] {"help", "spine"},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        String text = out.toString(StandardCharsets.UTF_8);
        assertEquals(0, code, text);
        assertBothHalves(text);
    }

    @Test
    void launcherHelpSpineAndDashHelpPrintBothHalves() throws Exception {
        LauncherCli.Capture page = LauncherCli.run(project, null, "help", "spine");
        assertEquals(0, page.code(), page.out() + page.err());
        assertBothHalves(page.out());

        LauncherCli.Capture catalog = LauncherCli.run(project, null, "--help");
        assertEquals(0, catalog.code(), catalog.out() + catalog.err());
        assertBothHalves(catalog.out());
    }

    private static void assertBothHalves(String text) {
        assertTrue(text.contains("spine session new"), text);
        assertTrue(text.contains("show|end") || text.contains("spine session show"), text);
        assertTrue(text.contains("spine start"), text);
        assertTrue(text.contains("spine warm status") || text.contains("warm status"), text);
        assertTrue(text.contains("spine sample") || text.contains("sample"), text);
    }
}
