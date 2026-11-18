package tools.dscode.store.dashboard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.store.RunProvenance;
import tools.dscode.store.StorePaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardTest {
    @TempDir
    Path project;

    @Test
    void featurePathComesFromTheScenarioKeyAndAStoredCommitStays() throws Exception {
        assertEquals("features/pay.feature", RunProvenance.featureOf("features/pay.feature|12"));
        assertEquals("", RunProvenance.featureOf("memory"));
        Path file = StorePaths.runState(project);
        Files.createDirectories(file.getParent());
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE runs (
                      run_id TEXT PRIMARY KEY,
                      git_commit TEXT NOT NULL DEFAULT '',
                      feature_path TEXT NOT NULL DEFAULT ''
                    )
                    """);
            statement.execute("INSERT INTO runs(run_id, git_commit) VALUES ('r1', 'abc1234')");
            RunProvenance.fill(connection, project, "r1", "features/pay.feature|2");
            RunProvenance.fill(connection, project, "r1", "features/other.feature|4");
            try (var rows = statement.executeQuery("SELECT git_commit, feature_path FROM runs WHERE run_id = 'r1'")) {
                assertTrue(rows.next());
                assertEquals("abc1234", rows.getString(1));
                assertEquals("features/pay.feature\nfeatures/other.feature", rows.getString(2));
            }
        }
    }

    @Test
    void missingStoreStaysMissing() throws Exception {
        DashboardSnapshot snapshot = new SqliteDashboardSource(project).read(DashboardQuery.all());
        assertTrue(snapshot.runs().isEmpty());
        assertFalse(Files.exists(StorePaths.mainDatabase(project)));
        assertFalse(Files.exists(StorePaths.runState(project)));
    }

    @Test
    void readsRunsEntitiesCasesErrorsAndLinks() throws Exception {
        seed();
        DashboardSnapshot all = new SqliteDashboardSource(project).read(DashboardQuery.all());
        assertEquals(2, all.runs().size());
        DashboardSnapshot failed = new SqliteDashboardSource(project).read(
                new DashboardQuery("", "", "FAILED", "", "", "", ""));
        assertEquals(1, failed.runs().size());
        DashboardSnapshot.RunView run = failed.runs().get(0);
        assertEquals("pay-run", run.id());
        assertEquals("abcdef1", run.gitCommit());
        assertEquals("https://github.com/ds-code-t/pickleball/commit/abcdef1", run.commitUrl());
        assertTrue(run.features().contains("features/pay.feature"));
        assertTrue(run.dataFiles().contains("data/accounts/ada.yaml"));
        assertTrue(run.artifacts().contains("reports/pay/shot.png"));
        assertEquals("Account#ada", failed.entities().get(0).key());
        assertEquals("Case#12", failed.cases().get(0).key());
        assertEquals("billing", failed.caseTypes().get(0).key());
        assertEquals("the card was declined", failed.errors().get(0).key());
        assertTrue(failed.markers().stream().anyMatch(marker -> "case".equals(marker.kind()) && "Case#12".equals(marker.id())));
        assertTrue(failed.markers().stream().anyMatch(marker -> "scenario".equals(marker.kind())));
    }

    @Test
    void pageIsSelfContainedAndVirtualized() throws Exception {
        seed();
        String html = DashboardHtml.render(new SqliteDashboardSource(project).read(DashboardQuery.all()));
        assertTrue(html.contains("function virtualize"));
        assertTrue(html.contains("id=\"rewind\""));
        assertTrue(html.contains("pay-run"));
        assertTrue(html.contains("features/pay.feature"));
        assertTrue(html.contains("Business entities"));
        assertFalse(html.contains("<script src="));
        assertFalse(html.toLowerCase().contains("cdn"));
        assertFalse(html.contains("localhost"));
        assertFalse(html.contains("127.0.0.1"));
        Path root = Path.of("src/main/java/tools/dscode/store/dashboard");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                assertFalse(text.contains("localhost"), file.toString());
                assertFalse(text.contains("127.0.0.1"), file.toString());
                assertFalse(text.contains("DriverManager"), file.toString());
            }
        }
    }

    @Test
    void writesThePageAndPrintsThePath() throws Exception {
        seed();
        var out = new java.io.ByteArrayOutputStream();
        var err = new java.io.ByteArrayOutputStream();
        int code = DashboardCli.execute(
                new String[]{"dashboard", "--project=" + project, "--status=FAILED"},
                new java.io.PrintStream(out),
                new java.io.PrintStream(err)
        );
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("dashboard=.pickleball/dashboard/index.html"), text);
        assertTrue(text.contains("runs=1"), text);
        String html = Files.readString(project.resolve(".pickleball/dashboard/index.html"));
        assertTrue(html.contains("pay-run"));
        assertFalse(html.contains("other-run"));
    }

    @Test
    void writtenPageLinksFromItsOwnFolderAndItsDataIsValidJson() throws Exception {
        seed();
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + StorePaths.runState(project).toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("UPDATE runs SET note = 'tab\there' || char(1) || char(8232) WHERE run_id = 'pay-run'");
        }
        var out = new java.io.ByteArrayOutputStream();
        var err = new java.io.ByteArrayOutputStream();
        int code = DashboardCli.execute(
                new String[]{"dashboard", "--project=" + project},
                new java.io.PrintStream(out),
                new java.io.PrintStream(err)
        );
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        String html = Files.readString(project.resolve(".pickleball/dashboard/index.html"));
        String open = "<script id=\"data\" type=\"application/json\">";
        int start = html.indexOf(open);
        assertTrue(start >= 0, "data block");
        String json = html.substring(start + open.length(), html.indexOf("</script>", start)).trim();
        var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        assertEquals("../../", tree.get("linkBase").asText());
        var notes = new java.util.ArrayList<String>();
        tree.get("runs").forEach(run -> notes.add(run.get("note").asText()));
        assertTrue(notes.contains("tab\there\u0001\u2028"), notes.toString());
        assertTrue(html.contains("add(local(path), path)"), "project paths are prefixed with linkBase");
        Path page = project.resolve(".pickleball/dashboard/index.html");
        assertEquals(project.resolve("features/pay.feature").normalize(),
                page.getParent().resolve(tree.get("linkBase").asText() + "features/pay.feature").normalize());
        assertEquals("", DashboardCli.linkBase(project, project.resolve("index.html")));
    }

    private void seed() throws Exception {
        Path runs = StorePaths.runState(project);
        Path main = StorePaths.mainDatabase(project);
        Files.createDirectories(runs.getParent());
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + runs.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE runs (
                      run_id TEXT PRIMARY KEY, started_at TEXT, finished_at TEXT, status TEXT,
                      scenario TEXT, passed INTEGER, failed INTEGER, note TEXT,
                      git_commit TEXT NOT NULL DEFAULT '', feature_path TEXT NOT NULL DEFAULT '')
                    """);
            statement.execute("""
                    CREATE TABLE scenario_executions (
                      exec_id TEXT PRIMARY KEY, run_id TEXT, scenario_key TEXT, started_at TEXT)
                    """);
            statement.execute("""
                    CREATE TABLE step_executions (
                      step_exec_id TEXT PRIMARY KEY, scenario_exec_id TEXT, step_text TEXT,
                      started_at TEXT, status TEXT)
                    """);
            statement.execute("""
                    CREATE TABLE entity_usages (
                      step_exec_id TEXT, entity_type TEXT, entity_id TEXT, logged_at TEXT)
                    """);
            statement.execute("CREATE TABLE artifacts (path TEXT, run_id TEXT)");
            statement.execute("CREATE TABLE run_logs (id INTEGER PRIMARY KEY, run_id TEXT, logged_at TEXT)");
            statement.execute("""
                    CREATE TABLE log_entity_links (
                      log_id INTEGER, entity_type TEXT, entity_id TEXT)
                    """);
            statement.execute("""
                    INSERT INTO runs VALUES
                    ('other-run','2020-01-01T00:00:00Z',NULL,'PASSED','Other',1,0,'','', 'features/other.feature'),
                    ('pay-run','2020-02-01T00:00:00Z',NULL,'FAILED','Pay',0,1,'card','abcdef1','features/pay.feature')
                    """);
            statement.execute("""
                    INSERT INTO scenario_executions VALUES
                    ('ex1','pay-run','features/pay.feature|12','2020-02-01T00:00:01Z')
                    """);
            statement.execute("""
                    INSERT INTO step_executions VALUES
                    ('st1','ex1','the card was declined','2020-02-01T00:00:02Z','FAILED')
                    """);
            statement.execute("""
                    INSERT INTO entity_usages VALUES
                    ('st1','Account','ada','2020-02-01T00:00:02Z'),
                    ('st1','Case','12','2020-02-01T00:00:03Z')
                    """);
            statement.execute("INSERT INTO artifacts VALUES ('reports/pay/shot.png','pay-run')");
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + main.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE entities (
                      entity_type TEXT, entity_id TEXT, payload_json TEXT, source_file TEXT)
                    """);
            statement.execute("""
                    INSERT INTO entities VALUES
                    ('Account','ada','{}','data/accounts/ada.yaml'),
                    ('Case','12','{"type":"billing"}','data/cases/12.yaml')
                    """);
            statement.execute("CREATE TABLE provenance (id INTEGER PRIMARY KEY, github_repo TEXT)");
            statement.execute("INSERT INTO provenance VALUES (1, 'ds-code-t/pickleball')");
        }
    }
}
