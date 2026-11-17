package tools.dscode.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The old CLI word, setting, and Gradle task are not aliases.
 * The new names are the ones that work.
 */
class StoreRenameTest {
    @TempDir
    Path project;

    @Test
    void oldCliWordIsUnknownAndStoreHelpWorks() {
        assertFalse(StoreCli.handles(new String[] {removed(), "status"}));
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = StoreCli.execute(
                new String[] {removed(), "status", "--project=" + project},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(2, code, err.toString(StandardCharsets.UTF_8));
        String error = err.toString(StandardCharsets.UTF_8);
        assertTrue(error.contains("error:"), error);
        assertFalse(error.toLowerCase().contains("alias"), error);
        assertFalse(Files.exists(project.resolve(".pickleball")));

        ByteArrayOutputStream helpOut = new ByteArrayOutputStream();
        ByteArrayOutputStream helpErr = new ByteArrayOutputStream();
        assertEquals(0, StoreCli.execute(
                new String[] {"store", "help"},
                new PrintStream(helpOut, true, StandardCharsets.UTF_8),
                new PrintStream(helpErr, true, StandardCharsets.UTF_8)));
        String help = helpOut.toString(StandardCharsets.UTF_8);
        assertTrue(help.contains("Usage: store status"), help);
        assertTrue(help.contains("Usage: store hydrate"), help);
        assertFalse(help.contains(removed() + " "), help);
    }

    @Test
    void oldPropertyIsUnreadAndTheNewPropertyOptsOut() {
        String oldKey = "pkb_" + removed();
        String previousOld = System.getProperty(oldKey);
        String previousNew = System.getProperty("pkb_store");
        try {
            System.clearProperty("pkb_store");
            System.setProperty(oldKey, "false");
            assertTrue(StoreConfig.enabled(project), "an unread setting must not turn the store off");
            System.clearProperty(oldKey);
            System.setProperty("pkb_store", "false");
            assertFalse(StoreConfig.enabled(project));
        } finally {
            restore(oldKey, previousOld);
            restore("pkb_store", previousNew);
        }
    }

    @Test
    void oldGradleTaskIsAbsentAndStoreTasksAreRegistered() throws Exception {
        String gradle = Files.readString(Path.of("build.gradle"), StandardCharsets.UTF_8);
        assertFalse(gradle.contains("tasks.register('" + removed()), gradle);
        assertFalse(gradle.contains("engine" + "Do" + "lt"), gradle);
        assertTrue(gradle.contains("tasks.register('storeStatus'"), gradle);
        assertTrue(gradle.contains("storeProp('storeSandbox')"), gradle);
        assertTrue(gradle.contains("if (project.hasProperty('engineStore')) args += ['--store']"), gradle);
        assertFalse(gradle.contains("-P" + removed()), gradle);
    }

    private static String removed() {
        return "do" + "lt";
    }

    private static void restore(String key, String previous) {
        if (previous == null) System.clearProperty(key);
        else System.setProperty(key, previous);
    }
}
