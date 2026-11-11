package tools.dscode.dalton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaltonParseTest {
    private static final String FEATURE = """
            # language: en
            # comment stays only in the raw bytes
            @billing @smoke
            Feature: Invoices
              Customers receive an invoice.

              @setup
              Background: Ready
                Given the ledger is open
                \"\"\"
                line one
                \"\"\"

              @outline
              Scenario Outline: Pay <name>
                When the amount is <amount>
                  | field | value |
                  | name | <name> |
                Then the receipt is stored
                Examples:
                  | name | amount |
                  | Ada  | 10     |
                @extra
                Examples: second
                  | name | amount |
                  | Lin  | 4      |

              Scenario: Done
                * finished
            """;

    @TempDir
    Path project;

    @Test
    void parseKeepsTagsTablesDocstringsAndCommentsOutOfTheDescription() {
        DaltonGherkin.Document document = DaltonGherkin.parse(FEATURE);
        assertEquals("Feature", document.keyword());
        assertEquals("Invoices", document.name());
        assertEquals("en", document.language());
        assertEquals("@billing @smoke", document.tags());
        assertEquals("Customers receive an invoice.", document.description());
        assertFalse(document.description().contains("comment"));

        DaltonGherkin.Scenario background = document.scenarios().getFirst();
        assertEquals("Background", background.keyword());
        assertEquals("Ready", background.name());
        assertEquals("@setup", background.tags());
        assertEquals("Given", background.steps().getFirst().keyword());
        assertEquals("the ledger is open", background.steps().getFirst().text());
        assertEquals("line one", background.steps().getFirst().docstring());
        assertNull(background.steps().getFirst().docstringType());

        DaltonGherkin.Scenario outline = document.scenarios().get(1);
        assertEquals("Scenario Outline", outline.keyword());
        assertEquals("Pay <name>", outline.name());
        assertEquals("@outline", outline.tags());
        assertEquals("[[\"field\",\"value\"],[\"name\",\"<name>\"]]", outline.steps().getFirst().tableJson());
        DaltonGherkin.Step firstExamples = outline.steps().get(2);
        assertEquals("Examples", firstExamples.keyword());
        assertEquals("", firstExamples.text());
        assertEquals("[[\"name\",\"amount\"],[\"Ada\",\"10\"]]", firstExamples.tableJson());
        DaltonGherkin.Step secondExamples = outline.steps().get(3);
        assertEquals("@extra\nsecond", secondExamples.text());

        DaltonGherkin.Scenario done = document.scenarios().get(2);
        assertEquals("Scenario", done.keyword());
        assertEquals("*", done.steps().getFirst().keyword());
        assertEquals("finished", done.steps().getFirst().text());
    }

    @Test
    void hydrateStoresRawBytesAndStructuredRowsAndSkipsUnchanged() throws Exception {
        Path feature = project.resolve("src/test/resources/features/invoices.feature");
        Files.createDirectories(feature.getParent());
        byte[] raw = FEATURE.getBytes(StandardCharsets.UTF_8);
        Files.write(feature, raw);
        Files.createDirectories(project.resolve("src/test/resources/configs"));
        Files.writeString(project.resolve("src/test/resources/configs/URL.yaml"), "home: /\n");

        DaltonHydrator.Result first = DaltonHydrator.hydrate(project, DaltonHydrator.Options.full());
        assertEquals(2, first.inserted());
        try (DaltonDatabase database = DaltonDatabase.open(project)) {
            DaltonFiles.Row row = DaltonFiles.find(database.connection(), "src/test/resources/features/invoices.feature");
            assertArrayEquals(raw, row.raw());
            assertEquals(DaltonHashes.sha256(raw), row.contentHash());
            assertEquals("Invoices", text(database, "SELECT name FROM feature_docs"));
            assertEquals(3, count(database, "SELECT COUNT(*) FROM scenarios"));
            assertTrue(count(database, "SELECT COUNT(*) FROM steps") > 0);
            assertEquals(0, count(database, "SELECT COUNT(*) FROM feature_docs WHERE file_path LIKE '%URL.yaml'"));
        }

        DaltonHydrator.Result second = DaltonHydrator.hydrate(project, DaltonHydrator.Options.full());
        assertEquals(2, second.skipped());
        assertEquals(0, second.updated());
        try (DaltonDatabase database = DaltonDatabase.open(project)) {
            assertEquals(3, count(database, "SELECT COUNT(*) FROM scenarios"));
        }
    }

    private static String text(DaltonDatabase database, String sql) throws Exception {
        try (Statement statement = database.connection().createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : "";
        }
    }

    private static int count(DaltonDatabase database, String sql) throws Exception {
        try (Statement statement = database.connection().createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }
}
