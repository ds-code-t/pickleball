package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code bidi script}, {@code bidi intercept}, and {@code bidi grid-attach}
 * use the same session rule as every other dispatched verb.
 */
class BidiSessionGateTest {
    @TempDir
    Path project;

    @Test
    void scriptInterceptAndGridAttachRequireASession() throws Exception {
        Path args = LauncherCli.projectFile(project);
        assertMissing(LauncherCli.run(project, args, "bidi", "script", "--source=1"));
        assertMissing(LauncherCli.run(project, args, "bidi", "intercept", "--all"));
        assertMissing(LauncherCli.run(project, args, "bidi", "grid-attach", "--url=http://127.0.0.1:9"));
        assertMissing(LauncherCli.run(project, args, "bidi", "help"));
    }

    @Test
    void sessionLetsTheBidiBodyRun() throws Exception {
        Path args = LauncherCli.projectFile(project);
        LauncherCli.Capture created = LauncherCli.run(project, args, "spine", "session", "new");
        assertEquals(0, created.code(), created.out() + created.err());

        LauncherCli.Capture script = LauncherCli.run(project, args, "bidi", "script", "--source=1");
        String text = script.out() + script.err();
        assertFalse(text.contains("missing session"), text);
        assertTrue(text.contains("bidi commands are off"), text);
        assertEquals(2, script.code(), text);
    }

    private static void assertMissing(LauncherCli.Capture capture) {
        String text = capture.out() + capture.err();
        assertEquals(2, capture.code(), text);
        assertTrue(text.contains("error: missing session"), text);
        assertTrue(text.contains("fix: spine session new"), text);
    }
}
