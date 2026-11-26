package tools.dscode.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.dscode.store.sql.Binding;
import tools.dscode.store.sql.SqlConnections;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunVariableSyncTest {
    @TempDir
    Path project;

    @BeforeEach
    void unbound() {
        StoreScope.clear();
    }

    @AfterEach
    void releaseStoreFiles() throws Exception {
        StoreScope.clear();
        SqlConnections.closeProject(project);
        StoreHandles.assertReleased(project);
    }

    @Test
    void mainSetWritesTheFileAndACopySandboxSetDoesNot() throws Exception {
        Capture main = run("store", "var", "set", "pkb_browser", "chrome", "--project=" + project);
        assertEquals(0, main.code, main.err);
        Path file = project.resolve("src/test/resources/pickleball.properties");
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(text.contains("pkb_browser=chrome"), text);
        assertEquals("chrome", value(Binding.main(), "pkb_browser"));

        Capture created = run("store", "sandbox", "create", "trial", "--mode=copy", "--project=" + project);
        assertEquals(0, created.code, created.err);
        Capture sandbox = run("store", "var", "set", "pkb_browser", "edge", "--sandbox=trial", "--project=" + project);
        assertEquals(0, sandbox.code, sandbox.err);
        assertTrue(sandbox.out.contains("sandbox"), sandbox.out);
        assertEquals("pkb_browser=chrome", Files.readString(file, StandardCharsets.UTF_8).trim());
        assertEquals("chrome", value(Binding.main(), "pkb_browser"));
        assertEquals("edge", value(Binding.sandbox("trial"), "pkb_browser"));
    }

    @Test
    void cleartextSecretFieldIsRejected() throws Exception {
        write(project.resolve("store.properties"), "secret-fields=pkb_password\n");
        Capture rejected = run("store", "var", "set", "pkb_password", "hunter2", "--project=" + project);
        assertEquals(1, rejected.code, rejected.out);
        assertTrue(rejected.err.contains("error:"), rejected.err);
        assertTrue(rejected.err.contains("fix:"), rejected.err);
        assertFalse(rejected.err.contains("hunter2"), rejected.err);
        assertEquals(null, value(Binding.main(), "pkb_password"));
    }

    private String value(Binding binding, String name) throws Exception {
        try (StoreDatabase database = StoreDatabase.open(project, binding)) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT value FROM run_variables WHERE name = ?")) {
                statement.setString(1, name);
                try (var rows = statement.executeQuery()) {
                    return rows.next() ? rows.getString(1) : null;
                }
            }
        }
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static Capture run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = StoreCli.execute(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
