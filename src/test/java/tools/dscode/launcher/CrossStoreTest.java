package tools.dscode.launcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentcontrol.SpineSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossStoreTest {
    @TempDir
    Path temp;

    private String previousCross;
    private String previousJournal;
    private String previousAgent;
    private String previousSession;

    @BeforeEach
    void isolate() {
        previousCross = System.getProperty("pkb_cross_store");
        previousJournal = System.getProperty("pkb_journal");
        previousAgent = System.getProperty("pkb_agent_id");
        previousSession = System.getProperty("pkb_session_id");
        System.setProperty("pkb_cross_store", temp.resolve("cross.db").toString());
        System.clearProperty("pkb_journal");
        System.clearProperty("pkb_agent_id");
        System.clearProperty("pkb_session_id");
    }

    @AfterEach
    void restore() {
        restore("pkb_cross_store", previousCross);
        restore("pkb_journal", previousJournal);
        restore("pkb_agent_id", previousAgent);
        restore("pkb_session_id", previousSession);
    }

    @Test
    void noteAddListAndLinkRoundTrip() throws Exception {
        Path db = temp.resolve("cross.db");
        ByteArrayOutputStream helpOut = new ByteArrayOutputStream();
        int help = CrossStore.execute(
                new String[] {"cross", "help"},
                new PrintStream(helpOut, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)
        );
        assertEquals(0, help);
        assertTrue(helpOut.toString(StandardCharsets.UTF_8).contains("cross note add"));
        assertFalse(Files.exists(db));

        Path project = temp.resolve("notes-project");
        Files.createDirectories(project);
        SpineSession.create(project, "agent-1");
        Invocation added = cross("cross", "note", "add", "--agent=ada", "--text=r7-note-kept", "--project=" + project);
        assertEquals(0, added.code, added.err);
        assertTrue(added.out.contains("note "), added.out);
        assertTrue(Files.isRegularFile(db));

        Invocation listed = cross("cross", "note", "list", "--agent=ada", "--project=" + project);
        assertEquals(0, listed.code, listed.err);
        assertTrue(listed.out.contains("r7-note-kept"), listed.out);

        Invocation json = cross("cross", "note", "list", "--json", "--project=" + project);
        assertEquals(0, json.code, json.err);
        assertTrue(json.out.contains("\"body\":\"r7-note-kept\""), json.out);

        Invocation link = cross("cross", "link", "add", "--commit=abc123def", "--head=current", "--run=run-r7", "--project=" + project);
        assertEquals(0, link.code, link.err);
        Invocation links = cross("cross", "link", "list", "--project=" + project);
        assertEquals(0, links.code, links.err);
        assertTrue(links.out.contains("abc123def"), links.out);
        assertTrue(links.out.contains("current"), links.out);
        assertTrue(links.out.contains("run-r7"), links.out);

        try (Connection connection = open(db);
             Statement statement = connection.createStatement();
             ResultSet schema = statement.executeQuery("SELECT value FROM schema_meta WHERE `key` = 'cross_schema'")) {
            assertTrue(schema.next());
            assertEquals("1", schema.getString(1));
        }
    }

    @Test
    void secretBodyIsRefused() throws Exception {
        Path project = temp.resolve("secret-project");
        Files.createDirectories(project);
        SpineSession.create(project, "agent-1");
        Invocation refused = cross(
                "cross", "note", "add", "--agent=ada", "--text=password=hunter2", "--project=" + project
        );
        assertEquals(1, refused.code, refused.err);
        assertFalse(refused.err.contains("hunter2"), refused.err);
        assertFalse(refused.out.contains("hunter2"), refused.out);
        String stored = dump(temp.resolve("cross.db"));
        assertFalse(stored.contains("hunter2"), stored);
        assertFalse(stored.contains("password=hunter2"), stored);

        Invocation reference = cross(
                "cross", "note", "add", "--agent=ada", "--text=password=~MASK;", "--project=" + project
        );
        assertEquals(0, reference.code, reference.err);
        Invocation listed = cross("cross", "note", "list", "--project=" + project);
        assertTrue(listed.out.contains("~MASK;"), listed.out);
        assertFalse(dump(temp.resolve("cross.db")).contains("hunter2"));
    }

    @Test
    void storeStatusWritesAJournalRowWithoutTheFlagText() throws Exception {
        Path project = temp.resolve("pkb-r7-path-marker");
        Files.createDirectories(project);
        SpineSession.create(project, "agent-1");
        System.setProperty("pkb_agent_id", "agent-r7");
        System.setProperty("pkb_session_id", "session-r7");
        String[] args = {
                "store", "status", "--project=" + project, "--marker=pkb-r7-flag-marker"
        };
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = PickleballWorkbenchLauncher.dispatchStoreIfAny(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        assertEquals(0, code, stderr.toString(StandardCharsets.UTF_8));
        assertFalse(Files.exists(project.resolve(".pickleball").resolve("store").resolve("main.db")));
        Path db = temp.resolve("cross.db");
        assertTrue(Files.isRegularFile(db));
        String stored = dump(db);
        assertFalse(stored.contains("pkb-r7-flag-marker"), stored);
        assertFalse(stored.contains("pkb-r7-path-marker"), stored);
        try (Connection connection = open(db);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT verb, args_hash, ok, code, agent_id, session_id FROM journal")) {
            assertTrue(rows.next());
            assertEquals("store", rows.getString(1));
            assertEquals(CrossStore.argsHash(args), rows.getString(2));
            assertEquals(1, rows.getInt(3));
            assertEquals("0", rows.getString(4));
            assertEquals("agent-r7", rows.getString(5));
            assertEquals("session-r7", rows.getString(6));
            assertFalse(rows.next());
        }
    }

    @Test
    void journalOffDoesNotCreateTheFile() throws Exception {
        System.setProperty("pkb_journal", "off");
        Path project = temp.resolve("quiet-project");
        Files.createDirectories(project);
        SpineSession.create(project, "agent-1");
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = PickleballWorkbenchLauncher.dispatchStoreIfAny(
                new String[] {"store", "status", "--project=" + project},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        assertEquals(0, code, stderr.toString(StandardCharsets.UTF_8));
        assertFalse(Files.exists(temp.resolve("cross.db")));
        assertFalse(Files.exists(project.resolve(".pickleball").resolve("store").resolve("main.db")));
    }

    @Test
    void deletingTheProjectLeavesTheNote() throws Exception {
        Path project = temp.resolve("gone-project");
        Files.createDirectories(project);
        SpineSession.create(project, "agent-1");
        Invocation added = cross("cross", "note", "add", "--agent=ada", "--text=r7-note-survives", "--project=" + project);
        assertEquals(0, added.code, added.err);
        deleteTree(project);
        assertFalse(Files.exists(project));
        Path reader = temp.resolve("reader");
        Files.createDirectories(reader);
        SpineSession.create(reader, "agent-1");
        Invocation listed = cross("cross", "note", "list", "--project=" + reader);
        assertEquals(0, listed.code, listed.err);
        assertTrue(listed.out.contains("r7-note-survives"), listed.out);
        assertTrue(Files.isRegularFile(temp.resolve("cross.db")));
    }

    @Test
    void journalRetainDaysZeroDropsTheRow() throws Exception {
        Path project = temp.resolve("retain-project");
        Files.createDirectories(project);
        SpineSession.create(project, "agent-1");
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int status = PickleballWorkbenchLauncher.dispatchStoreIfAny(
                new String[] {"store", "status", "--project=" + project},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        assertEquals(0, status, stderr.toString(StandardCharsets.UTF_8));
        assertEquals(1, count("SELECT COUNT(*) FROM journal WHERE verb = 'store'"));

        Invocation retained = cross("cross", "journal", "retain", "--days=0", "--project=" + project);
        assertEquals(0, retained.code, retained.err);
        assertTrue(retained.out.contains("dropped=1"), retained.out);
        assertEquals(0, count("SELECT COUNT(*) FROM journal WHERE verb = 'store'"));
    }

    private Invocation cross(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = PickleballWorkbenchLauncher.dispatchCrossIfAny(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Invocation(
                code,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8)
        );
    }

    private int count(String sql) throws Exception {
        try (Connection connection = open(temp.resolve("cross.db"));
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private static Connection open(Path db) throws Exception {
        Class.forName("org.sqlite.JDBC");
        return DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
    }

    private static String dump(Path db) throws IOException {
        StringBuilder raw = new StringBuilder();
        append(raw, db);
        append(raw, Path.of(db.toString() + "-wal"));
        append(raw, Path.of(db.toString() + "-journal"));
        return raw.toString();
    }

    private static void append(StringBuilder raw, Path file) throws IOException {
        if (Files.isRegularFile(file)) raw.append(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1));
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) Files.deleteIfExists(path);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private record Invocation(int code, String out, String err) {
    }
}
