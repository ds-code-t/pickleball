package tools.dscode.store.dashboard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.store.RunProvenance;
import tools.dscode.store.StorePaths;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
        assertTrue(html.contains("id=\"legend\""));
        assertTrue(html.contains("id=\"clear\""));
        assertTrue(html.contains("Showing "));
        assertTrue(html.contains("function pick"));
        assertTrue(html.contains("section(\"Features\")"));
        assertTrue(html.contains("section(\"Data\")"));
        assertTrue(html.contains("section(\"Artifacts\")"));
        assertTrue(html.contains("section(\"Commit\")"));
        assertTrue(html.contains("overflow: auto"));
        assertTrue(html.contains("max-width: 1180px"));
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

    @Test
    void queryReturnsLeanRowsAndDoesNotChangeTheStore() throws Exception {
        seed();
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + StorePaths.runState(project).toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO scenario_executions VALUES
                    ('ex2','other-run','features/other.feature|1','2020-01-01T00:00:01Z')
                    """);
            statement.execute("""
                    INSERT INTO step_executions VALUES
                    ('st2','ex2','click the #save button','2020-01-01T00:00:02Z','PASSED')
                    """);
        }
        byte[] runs = Files.readAllBytes(StorePaths.runState(project));
        byte[] main = Files.readAllBytes(StorePaths.mainDatabase(project));
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code = DashboardCli.execute(new String[]{
                "dashboard", "query", "--project=" + project, "--status=FAILED", "--json"
        }, new PrintStream(out), new PrintStream(err));
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(out.toString(StandardCharsets.UTF_8));
        assertEquals("query", tree.get("op").asText());
        assertEquals(1, tree.get("count").asInt());
        assertEquals("pay-run", tree.get("runs").get(0).get("ref").asText());
        assertEquals("Pay", tree.get("runs").get(0).get("name").asText());
        assertEquals("FAILED", tree.get("runs").get(0).get("status").asText());
        assertEquals("features/pay.feature", tree.get("runs").get(0).get("feature").asText());
        assertEquals("features/pay.feature|12", tree.get("runs").get(0).get("scenarios").get(0).asText());
        assertEquals("reports/pay/shot.png", tree.get("runs").get(0).get("artifacts").get(0).asText());
        assertEquals("data/accounts/ada.yaml", tree.get("runs").get(0).get("data").get(0).asText());
        assertTrue(tree.get("runs").get(0).get("summary").asText().contains("FAILED"));
        assertFalse(tree.get("runs").get(0).has("note"));
        assertArrayEquals(runs, Files.readAllBytes(StorePaths.runState(project)));
        assertArrayEquals(main, Files.readAllBytes(StorePaths.mainDatabase(project)));

        out.reset();
        code = DashboardCli.execute(new String[]{
                "dashboard", "query", "--project=" + project, "--element=#save"
        }, new PrintStream(out), new PrintStream(err));
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(out.toString(StandardCharsets.UTF_8));
        assertEquals(1, tree.get("count").asInt());
        assertEquals("other-run", tree.get("runs").get(0).get("ref").asText());

        out.reset();
        code = DashboardCli.execute(new String[]{
                "dashboard", "query", "--project=" + project, "--from=2020-02-01", "--to=2020-02-02",
                "--feature=pay.feature", "--entity=Account#ada", "--text=declined", "--limit=1"
        }, new PrintStream(out), new PrintStream(err));
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(out.toString(StandardCharsets.UTF_8));
        assertEquals("pay-run", tree.get("runs").get(0).get("ref").asText());

        out.reset();
        code = DashboardCli.execute(new String[]{
                "dashboard", "query", "--project=" + project, "--element=missing-widget"
        }, new PrintStream(out), new PrintStream(err));
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(out.toString(StandardCharsets.UTF_8));
        assertEquals(0, tree.get("count").asInt());
        assertEquals(0, tree.get("total").asInt());

        code = DashboardCli.execute(new String[]{
                "dashboard", "query", "--project=" + project, "--limit=no"
        }, new PrintStream(new ByteArrayOutputStream()), new PrintStream(err));
        assertEquals(2, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("error: --limit must be a whole number"));
        String help = new String(helpText(), StandardCharsets.UTF_8);
        assertTrue(help.contains("dashboard query"));
        assertTrue(help.contains("--element=<hint>"));
        assertArrayEquals(runs, Files.readAllBytes(StorePaths.runState(project)));
    }

    private byte[] helpText() {
        var out = new ByteArrayOutputStream();
        int code = DashboardCli.execute(
                new String[]{"dashboard", "help"},
                new PrintStream(out),
                new PrintStream(new ByteArrayOutputStream())
        );
        assertEquals(0, code);
        return out.toByteArray();
    }

    @Test
    void xrefReturnsHistoryForAFixtureElementAndDoesNotChangeTheStore() throws Exception {
        var emptyOut = new ByteArrayOutputStream();
        var emptyErr = new ByteArrayOutputStream();
        int empty = DashboardCli.execute(new String[]{
                "dashboard", "xref", "--project=" + project, "--id=#save"
        }, new PrintStream(emptyOut), new PrintStream(emptyErr));
        assertEquals(0, empty, emptyErr.toString(StandardCharsets.UTF_8));
        var emptyTree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(emptyOut.toString(StandardCharsets.UTF_8));
        assertEquals("xref", emptyTree.get("op").asText());
        assertEquals(0, emptyTree.get("count").asInt());
        assertTrue(emptyTree.get("overlay").asText().contains("no related runs"));
        assertFalse(Files.exists(StorePaths.runState(project)));
        assertFalse(Files.exists(StorePaths.mainDatabase(project)));

        seed();
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + StorePaths.runState(project).toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("UPDATE runs SET finished_at = '2020-02-01T01:00:00Z' WHERE run_id = 'pay-run'");
            statement.execute("""
                    INSERT INTO runs VALUES
                    ('side-run','2020-02-01T00:30:00Z','2020-02-01T00:40:00Z','PASSED','Side',1,0,'','','features/side.feature'),
                    ('spot-run','2020-02-01T00:00:00Z',NULL,'PASSED','Spot',1,0,'','','features/spot.feature'),
                    ('later-run','2020-03-01T00:00:00Z','2020-03-01T00:10:00Z','PASSED','Later',1,0,'','','features/later.feature')
                    """);
            statement.execute("""
                    INSERT INTO step_executions VALUES
                    ('st-save','ex1','click the #save button','2020-02-01T00:00:04Z','PASSED')
                    """);
            statement.execute("ALTER TABLE run_logs ADD COLUMN message TEXT");
            statement.execute("""
                    INSERT INTO run_logs (run_id, logged_at, message) VALUES
                    ('pay-run','2020-02-01T00:00:05Z','save clicked in the pay form')
                    """);
            statement.execute("ALTER TABLE artifacts ADD COLUMN kind TEXT");
            statement.execute("INSERT INTO artifacts (path, run_id, kind) VALUES ('reports/pay/frame.bin','pay-run','screenshot')");
            statement.execute("INSERT INTO artifacts (path, run_id, kind) VALUES ('data/accounts/ada.yaml','pay-run','')");
        }
        byte[] runs = Files.readAllBytes(StorePaths.runState(project));
        byte[] main = Files.readAllBytes(StorePaths.mainDatabase(project));
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code = DashboardCli.execute(new String[]{
                "dashboard", "xref", "--project=" + project, "--id=#save"
        }, new PrintStream(out), new PrintStream(err));
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(out.toString(StandardCharsets.UTF_8));
        assertEquals(1, tree.get("count").asInt());
        assertEquals("pay-run", tree.get("runs").get(0).get("ref").asText());
        assertEquals("features/pay.feature|12", tree.get("scenarios").get(0).asText());
        assertTrue(tree.get("logs").toString().contains("the card was declined"), tree.get("logs").toString());
        assertTrue(tree.get("logs").toString().contains("save clicked in the pay form"), tree.get("logs").toString());
        assertTrue(tree.get("screenshots").toString().contains("reports/pay/shot.png"), tree.get("screenshots").toString());
        assertTrue(tree.get("screenshots").toString().contains("reports/pay/frame.bin"), tree.get("screenshots").toString());
        assertFalse(tree.get("screenshots").toString().contains("ada.yaml"), tree.get("screenshots").toString());
        assertEquals(2, tree.get("concurrent").size());
        assertEquals("spot-run", tree.get("concurrent").get(0).get("ref").asText());
        assertEquals("side-run", tree.get("concurrent").get(1).get("ref").asText());
        assertEquals("1 run mentions #save: Pay FAILED. 2 other runs overlap that time.", tree.get("summary").asText());
        assertTrue(tree.get("overlay").asText().contains("(pay-run) FAILED"));
        assertTrue(tree.get("overlay").asText().contains("reports/pay/frame.bin"));
        assertTrue(tree.get("chat").asText().contains("pay-run FAILED"));
        assertTrue(tree.get("chat").asText().contains("features/pay.feature|12"));
        assertFalse(tree.has("window"));
        assertFalse(tree.get("runs").get(0).has("note"));
        assertArrayEquals(runs, Files.readAllBytes(StorePaths.runState(project)));
        assertArrayEquals(main, Files.readAllBytes(StorePaths.mainDatabase(project)));

        out.reset();
        code = DashboardCli.execute(new String[]{
                "dashboard", "xref", "--project=" + project, "--role=button", "--name=Save"
        }, new PrintStream(out), new PrintStream(err));
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(out.toString(StandardCharsets.UTF_8));
        assertEquals("pay-run", tree.get("runs").get(0).get("ref").asText());

        out.reset();
        code = DashboardCli.execute(new String[]{
                "dashboard", "xref", "--project=" + project, "--id=#save", "--name=Cancel"
        }, new PrintStream(out), new PrintStream(err));
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(out.toString(StandardCharsets.UTF_8));
        assertEquals(0, tree.get("count").asInt());
        assertEquals(0, tree.get("concurrent").size());

        code = DashboardCli.execute(new String[]{
                "dashboard", "xref", "--project=" + project
        }, new PrintStream(new ByteArrayOutputStream()), new PrintStream(err));
        assertEquals(2, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains(
                "error: dashboard xref needs --id, --selector, or --role and --name"));

        out.reset();
        code = DashboardCli.execute(new String[]{
                "dashboard", "xref", "--project=" + project, "--selector=#save", "--window"
        }, new PrintStream(out), new PrintStream(err));
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(out.toString(StandardCharsets.UTF_8));
        assertEquals(".pickleball/dashboard/xref.html", tree.get("window").asText());
        String html = Files.readString(project.resolve(".pickleball/dashboard/xref.html"));
        assertTrue(html.contains("pay-run"));
        assertTrue(html.contains("features/pay.feature|12"));
        assertTrue(html.contains("reports/pay/shot.png"));
        assertTrue(html.contains("side-run"));
        assertFalse(html.contains("<script"));
        assertFalse(html.toLowerCase().contains("cdn"));
        assertFalse(html.contains("localhost"));
        assertArrayEquals(runs, Files.readAllBytes(StorePaths.runState(project)));
        String help = new String(helpText(), StandardCharsets.UTF_8);
        assertTrue(help.contains("dashboard xref"));
        assertTrue(help.contains("--window"));
    }

    @Test
    void overlapGroupsSharedFailuresAndDoesNotChangeTheStore() throws Exception {
        var emptyOut = new ByteArrayOutputStream();
        var emptyErr = new ByteArrayOutputStream();
        int empty = DashboardCli.execute(new String[]{
                "dashboard", "overlap", "--project=" + project
        }, new PrintStream(emptyOut), new PrintStream(emptyErr));
        assertEquals(0, empty, emptyErr.toString(StandardCharsets.UTF_8));
        var emptyTree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(emptyOut.toString(StandardCharsets.UTF_8));
        assertEquals("overlap", emptyTree.get("op").asText());
        assertEquals(0, emptyTree.get("count").asInt());
        assertFalse(Files.exists(StorePaths.runState(project)));

        seed();
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + StorePaths.runState(project).toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("UPDATE runs SET finished_at = '2020-02-01T01:00:00Z' WHERE run_id = 'pay-run'");
            statement.execute("""
                    INSERT INTO runs VALUES
                    ('side-run','2020-02-01T00:30:00Z','2020-02-01T00:40:00Z','FAILED','Side',0,1,'','','features/side.feature'),
                    ('spot-run','2020-02-01T00:00:00Z',NULL,'PASSED','Spot',1,0,'','','features/spot.feature'),
                    ('later-run','2020-03-01T00:00:00Z','2020-03-01T00:10:00Z','FAILED','Later',0,1,'','','features/later.feature'),
                    ('twin-a','2020-04-01T00:00:00Z',NULL,'FAILED','Twin A',0,1,'','','features/twin.feature'),
                    ('twin-b','2020-04-01T00:00:00Z',NULL,'FAILED','Twin B',0,1,'','','features/twin.feature'),
                    ('lone-run','2020-05-01T00:00:00Z',NULL,'FAILED','Lone',0,1,'','','features/lone.feature')
                    """);
            statement.execute("""
                    INSERT INTO scenario_executions VALUES
                    ('ex-side','side-run','features/side.feature|1','2020-02-01T00:30:01Z'),
                    ('ex-later','later-run','features/later.feature|1','2020-03-01T00:00:01Z'),
                    ('ex-a','twin-a','features/twin.feature|1','2020-04-01T00:00:01Z'),
                    ('ex-b','twin-b','features/twin.feature|2','2020-04-01T00:00:01Z'),
                    ('ex-lone','lone-run','features/lone.feature|1','2020-05-01T00:00:01Z')
                    """);
            statement.execute("""
                    INSERT INTO step_executions VALUES
                    ('st-side','ex-side','the card was declined','2020-02-01T00:30:02Z','FAILED'),
                    ('st-later','ex-later','the card was declined','2020-03-01T00:00:02Z','FAILED'),
                    ('st-a','ex-a','timeout talking to the bank','2020-04-01T00:00:02Z','FAILED'),
                    ('st-b','ex-b','timeout talking to the bank','2020-04-01T00:00:02Z','FAILED'),
                    ('st-lone','ex-lone','only me','2020-05-01T00:00:02Z','FAILED')
                    """);
            statement.execute("ALTER TABLE run_logs ADD COLUMN message TEXT");
            statement.execute("""
                    INSERT INTO run_logs (run_id, logged_at, message) VALUES
                    ('pay-run','2020-02-01T00:00:06Z','bank timeout'),
                    ('side-run','2020-02-01T00:30:06Z','bank timeout')
                    """);
        }
        byte[] runs = Files.readAllBytes(StorePaths.runState(project));
        byte[] main = Files.readAllBytes(StorePaths.mainDatabase(project));
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code = DashboardCli.execute(new String[]{
                "dashboard", "overlap", "--project=" + project
        }, new PrintStream(out), new PrintStream(err));
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(out.toString(StandardCharsets.UTF_8));
        assertEquals(2, tree.get("count").asInt());
        assertEquals(2, tree.get("total").asInt());
        var first = tree.get("groups").get(0);
        assertEquals("pay-run", first.get("runs").get(0).get("ref").asText());
        assertEquals("spot-run", first.get("runs").get(1).get("ref").asText());
        assertEquals("side-run", first.get("runs").get(2).get("ref").asText());
        assertEquals("bank timeout", first.get("shared").get(0).asText());
        assertEquals("the card was declined", first.get("shared").get(1).asText());
        var second = tree.get("groups").get(1);
        assertEquals("twin-a", second.get("runs").get(0).get("ref").asText());
        assertEquals("twin-b", second.get("runs").get(1).get("ref").asText());
        assertEquals("timeout talking to the bank", second.get("shared").get(0).asText());
        String refs = tree.get("groups").toString();
        assertFalse(refs.contains("later-run"), refs);
        assertFalse(refs.contains("lone-run"), refs);
        assertArrayEquals(runs, Files.readAllBytes(StorePaths.runState(project)));
        assertArrayEquals(main, Files.readAllBytes(StorePaths.mainDatabase(project)));

        out.reset();
        code = DashboardCli.execute(new String[]{
                "dashboard", "overlap", "--project=" + project,
                "--from=2020-04-01T00:00:00Z", "--to=2020-04-01T00:00:00Z", "--limit=1"
        }, new PrintStream(out), new PrintStream(err));
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(out.toString(StandardCharsets.UTF_8));
        assertEquals(1, tree.get("count").asInt());
        assertEquals(1, tree.get("total").asInt());
        assertEquals("twin-a", tree.get("groups").get(0).get("runs").get(0).get("ref").asText());

        code = DashboardCli.execute(new String[]{
                "dashboard", "overlap", "--project=" + project, "--limit=no"
        }, new PrintStream(new ByteArrayOutputStream()), new PrintStream(err));
        assertEquals(2, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("error: --limit must be a whole number"));
        String help = new String(helpText(), StandardCharsets.UTF_8);
        assertTrue(help.contains("dashboard overlap"));
        assertArrayEquals(runs, Files.readAllBytes(StorePaths.runState(project)));
    }

    @Test
    void oldBlankRunStaysBlankAfterDashboard() throws Exception {
        Path file = runsFile(true);
        byte[] before = Files.readAllBytes(file);
        var err = new ByteArrayOutputStream();
        int code = DashboardCli.execute(
                new String[]{"dashboard", "--project=" + project, "--out=" + project.resolve("page.html")},
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(err)
        );
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        assertArrayEquals(before, Files.readAllBytes(file));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT git_commit FROM runs WHERE run_id = 'old'")) {
            assertTrue(rows.next());
            assertEquals("", rows.getString(1));
        }
    }

    @Test
    void dashboardLeavesAStoreWithoutProvenanceColumnsByteIdentical() throws Exception {
        Path file = runsFile(false);
        byte[] before = Files.readAllBytes(file);
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code = DashboardCli.execute(
                new String[]{"dashboard", "--project=" + project, "--out=" + project.resolve("page.html")},
                new PrintStream(out),
                new PrintStream(err)
        );
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        assertArrayEquals(before, Files.readAllBytes(file));
        assertTrue(Files.readString(project.resolve("page.html")).contains("features/old.feature"));
        assertFalse(columns(file).contains("git_commit"));
        assertFalse(columns(file).contains("feature_path"));
    }

    @Test
    void fillBlanksDoesNotStampTheCurrentCommit() throws Exception {
        git("init");
        git("config", "user.email", "provenance@example.com");
        git("config", "user.name", "Provenance");
        Files.writeString(project.resolve("note.txt"), "one\n");
        git("add", "note.txt");
        git("commit", "-m", "one");
        Path file = runsFile(true);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath())) {
            RunProvenance.fillBlanks(connection, project);
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT git_commit FROM runs WHERE run_id = 'old'")) {
                assertTrue(rows.next());
                assertEquals("", rows.getString(1));
            }
        }
    }

    @Test
    void commitIsResolvedOnEachRunWrite() throws Exception {
        git("init");
        git("config", "user.email", "provenance@example.com");
        git("config", "user.name", "Provenance");
        Files.writeString(project.resolve("note.txt"), "one\n");
        git("add", "note.txt");
        git("commit", "-m", "one");
        String first = git("rev-parse", "HEAD").trim();
        Path file = runsFile(true);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            RunProvenance.fill(connection, project, "old", "features/old.feature|1");
            assertEquals(first, commitOf(statement, "old"));
            Files.writeString(project.resolve("note.txt"), "two\n");
            git("add", "note.txt");
            git("commit", "-m", "two");
            String second = git("rev-parse", "HEAD").trim();
            assertFalse(second.equals(first));
            statement.execute("""
                    INSERT INTO runs(run_id, started_at, status, scenario, passed, failed, note)
                    VALUES ('next','2020-03-01T00:00:00Z','PASSED','Next',1,0,'')
                    """);
            RunProvenance.fill(connection, project, "next", "features/next.feature|2");
            assertEquals(first, commitOf(statement, "old"));
            assertEquals(second, commitOf(statement, "next"));
        }
    }

    private Path runsFile(boolean provenanceColumns) throws Exception {
        Path file = StorePaths.runState(project);
        Files.createDirectories(file.getParent());
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            if (provenanceColumns) {
                statement.execute("""
                        CREATE TABLE runs (
                          run_id TEXT PRIMARY KEY, started_at TEXT, finished_at TEXT, status TEXT,
                          scenario TEXT, passed INTEGER, failed INTEGER, note TEXT,
                          git_commit TEXT NOT NULL DEFAULT '', feature_path TEXT NOT NULL DEFAULT '')
                        """);
            } else {
                statement.execute("""
                        CREATE TABLE runs (
                          run_id TEXT PRIMARY KEY, started_at TEXT, finished_at TEXT, status TEXT,
                          scenario TEXT, passed INTEGER, failed INTEGER, note TEXT)
                        """);
            }
            statement.execute("""
                    CREATE TABLE scenario_executions (
                      exec_id TEXT PRIMARY KEY, run_id TEXT, scenario_key TEXT, started_at TEXT)
                    """);
            statement.execute("""
                    CREATE TABLE step_executions (
                      step_exec_id TEXT PRIMARY KEY, scenario_exec_id TEXT, step_text TEXT,
                      started_at TEXT, status TEXT)
                    """);
            statement.execute("CREATE TABLE entity_usages (step_exec_id TEXT, entity_type TEXT, entity_id TEXT, logged_at TEXT)");
            statement.execute("CREATE TABLE artifacts (path TEXT, run_id TEXT)");
            statement.execute("CREATE TABLE run_logs (id INTEGER PRIMARY KEY, run_id TEXT, logged_at TEXT)");
            statement.execute("CREATE TABLE log_entity_links (log_id INTEGER, entity_type TEXT, entity_id TEXT)");
            statement.execute("""
                    INSERT INTO runs(run_id, started_at, status, scenario, passed, failed, note)
                    VALUES ('old','2020-01-01T00:00:00Z','PASSED','Old',1,0,'')
                    """);
            statement.execute("""
                    INSERT INTO scenario_executions VALUES
                    ('ex-old','old','features/old.feature|1','2020-01-01T00:00:01Z')
                    """);
        }
        return file;
    }

    private static String commitOf(Statement statement, String runId) throws Exception {
        try (ResultSet rows = statement.executeQuery("SELECT git_commit FROM runs WHERE run_id = '" + runId + "'")) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private static Set<String> columns(Path file) throws Exception {
        Set<String> names = new HashSet<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA table_info(runs)")) {
            while (rows.next()) names.add(rows.getString("name"));
        }
        return names;
    }

    private String git(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
                .directory(project.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        return output;
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
