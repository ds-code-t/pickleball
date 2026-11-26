package tools.dscode.common.treeparsing;

import org.junit.jupiter.api.Test;
import tools.dscode.engine.EngineLog;
import tools.dscode.engine.adapter.cucumber.CucumberIngest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhraseContextIngestTest {
    @Test
    void aliasTableMapsContextWordsAndSkipsAQuotedNear() {
        assertRelation("inside", "inside", "inside the \"Panel\" Button");
        assertRelation("within", "inside", "WITHIN the \"Panel\" Button");
        assertRelation("near", "near", "near the \"Save\" Button");
        assertRelation("next to", "near", "next to the \"Save\" Button");
        assertRelation("following", "after", "following the \"Save\" Button");
        assertRelation("preceding", "before", "preceding the \"Save\" Button");
        assertRelation("on", "in", "on the \"Dialog\" Button");
        assertRelation("of", "in", "of the \"Dialog\" Button");
        assertRelation("between", "between", "between the \"A\" Button and the \"B\" Button");
        ContextWords.Reading quoted = ContextWords.read("click the \"near\" Button");
        assertNull(quoted.relation(), quoted.toString());
        assertNull(quoted.word(), quoted.toString());
        ContextWords.Reading incomplete = ContextWords.read("between the \"A\" Button");
        assertNull(incomplete.relation(), incomplete.toString());
        ContextWords.Reading quotedSuffix = ContextWords.read("wait Loading \"with local context\"");
        assertEquals("inherit", quotedSuffix.mode());
    }

    @Test
    void matchLogNamesTheTranslatedRelation() throws Exception {
        String previous = System.setProperty("pkb_engine_log", "match");
        try {
            ContextWords.read("inside the \"Panel\" Button");
            Path log = Path.of(System.getProperty("user.dir", ".")).resolve(".pickleball").resolve("engine").resolve("engine.log");
            assertTrue(Files.isRegularFile(log), log.toString());
            String text = Files.readString(log);
            assertTrue(text.contains("inside -> inside"), text);
            assertTrue(EngineLog.enabled("match"));
        } finally {
            if (previous == null) System.clearProperty("pkb_engine_log");
            else System.setProperty("pkb_engine_log", previous);
        }
    }

    @Test
    void ingestWritesContextModeAndScope() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE steps (
                          file_path TEXT, scenario_ord INTEGER, ord INTEGER, text TEXT,
                          retry INTEGER, ignore_failures INTEGER, log_failures_continue INTEGER,
                          run_if_finished INTEGER, always_run INTEGER, run_if_passing INTEGER,
                          run_if_failed INTEGER, run_if_hard_failed INTEGER, run_if_soft_failed INTEGER,
                          until_mode TEXT, context_mode TEXT, context_scope TEXT)
                        """);
                statement.execute("INSERT INTO steps(file_path, scenario_ord, ord, text) VALUES "
                        + "('catalog.feature', 1, 1, 'inside the \"Panel\" Button')");
                statement.execute("INSERT INTO steps(file_path, scenario_ord, ord, text) VALUES "
                        + "('catalog.feature', 1, 2, 'wait Loading with local context')");
                statement.execute("INSERT INTO steps(file_path, scenario_ord, ord, text) VALUES "
                        + "('catalog.feature', 1, 3, 'in any parent, click the \"Go\" Button')");
                statement.execute("INSERT INTO steps(file_path, scenario_ord, ord, text) VALUES "
                        + "('catalog.feature', 1, 4, 'click the \"near\" Button')");
            }
            CucumberIngest.syncStepFlags(connection, "catalog.feature");
            List<String> rows = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT ord, context_mode, context_scope FROM steps ORDER BY ord")) {
                while (result.next()) {
                    rows.add(result.getInt(1) + " " + result.getString(2) + " " + result.getString(3));
                }
            }
            assertEquals(List.of(
                    "1 inherit null",
                    "2 local null",
                    "3 inherit any-parent",
                    "4 inherit null"
            ), rows);
        }
    }

    private static void assertRelation(String word, String relation, String text) {
        ContextWords.Reading reading = ContextWords.read(text);
        assertEquals(word, reading.word(), text);
        assertEquals(relation, reading.relation(), text);
        assertEquals("inherit", reading.mode(), text);
        assertNull(reading.scope(), text);
    }
}
