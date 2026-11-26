package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.mcp.McpRegistry;
import org.junit.jupiter.api.Test;
import tools.dscode.engine.EngineCli;
import tools.dscode.store.StoreCli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerbCatalogTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path MARKDOWN = Path.of("docs/verb-inventory.md");
    private static final Set<String> SOURCES = Set.of("workbench", "store", "domengine", "engine", "cross");
    private static final Pattern FORWARDED = Pattern.compile("(?:directVerb|viewCommand|forward)\\(\"([^\"]+)\"");

    @Test
    void catalogListsTheSpineVerbsAndOnlyTheSixStreams() {
        List<VerbCatalog.Row> rows = VerbCatalog.rows();
        Set<String> names = names(rows);
        assertTrue(names.contains("store"), names.toString());
        assertTrue(names.contains("engine run"));
        assertTrue(names.contains("desktop"));
        for (String stream : VerbCatalog.STREAMS) assertTrue(names.contains(stream), stream);
        List<String> streams = new ArrayList<>();
        for (VerbCatalog.Row row : rows) {
            assertFalse(row.entry() == null || row.entry().isBlank(), row.name());
            assertFalse(row.name().isBlank(), row.toString());
            assertTrue(SOURCES.contains(row.source()), row.name() + " " + row.source());
            assertTrue("cli".equals(row.kind()) || "stream".equals(row.kind()), row.name());
            if ("stream".equals(row.kind())) streams.add(row.name());
        }
        assertEquals(new TreeSet<>(VerbCatalog.STREAMS), new TreeSet<>(streams));
        assertEquals(0, VerbCatalog.mcpOnlyCount());
        for (String name : McpRegistry.names()) {
            assertTrue(VerbCatalog.covers(name), "mcp registration is not a CLI verb: " + name);
        }
    }

    @Test
    void parserSetsStoreAndDomengineAreInTheCatalog() throws Exception {
        Set<String> names = names(VerbCatalog.rows());
        for (String command : WorkbenchCommandLine.FORWARDED_COMMANDS) assertTrue(names.contains(command), command);
        for (String command : WorkbenchCommandLine.AGENT_CORE_COMMANDS) assertTrue(names.contains(command), command);
        for (String command : WorkbenchCommandLine.SESSION_CLIENT_COMMANDS) assertTrue(names.contains(command), command);
        assertTrue(names.contains("store"));
        String store = Files.readString(Path.of("src/main/java/tools/dscode/store/StoreCli.java"));
        for (String command : StoreCli.COMMANDS) {
            assertTrue(names.contains("store " + command), command);
            assertTrue(store.contains("\"" + command + "\""), command);
        }
        String engine = Files.readString(Path.of("src/main/java/tools/dscode/engine/EngineCli.java"));
        for (String command : EngineCli.COMMANDS) {
            assertTrue(names.contains("engine " + command), command);
            assertTrue(engine.contains("\"" + command + "\""), command);
        }
        for (String command : DomEngineMain.COMMANDS) {
            String name = "desktop".equals(command) ? "desktop" : "domengine " + command;
            assertTrue(names.contains(name), name);
        }
        assertTrue(names.contains("domengine session log"));
    }

    @Test
    void workbenchMcpForwardsACliVerb() throws Exception {
        List<Path> sources = List.of(
                Path.of("pickleball-workbench/src/main/java/tools/dscode/workbench/mcp/WorkbenchMcpTools.java"),
                Path.of("pickleball-workbench/src/main/java/tools/dscode/workbench/mcp/popup/PopupMcpCommands.java")
        );
        Set<String> names = names(VerbCatalog.rows());
        for (Path source : sources) {
            Matcher matcher = FORWARDED.matcher(Files.readString(source));
            while (matcher.find()) {
                assertTrue(names.contains(matcher.group(1)), source + " " + matcher.group(1));
            }
        }
    }

    @Test
    void engineInventoryPrintsTheTableAndAnEnvelope() throws Exception {
        Capture text = run("engine", "inventory");
        assertEquals(0, text.code, text.err);
        assertTrue(text.out.contains("store"));
        assertTrue(text.out.contains("engine run"));
        assertTrue(text.out.contains("desktop"));
        assertTrue(text.out.contains("mcp-only\t0"));
        Capture json = run("engine", "inventory", "--json");
        assertEquals(0, json.code, json.err);
        JsonNode envelope = JSON.readTree(json.out);
        assertEquals(1, envelope.path("v").asInt());
        assertEquals("engine inventory", envelope.path("op").asText());
        assertTrue(envelope.path("ok").asBoolean());
        Set<String> items = new TreeSet<>();
        envelope.path("items").forEach(item -> items.add(item.path("name").asText()));
        assertTrue(items.contains("store"));
        assertTrue(items.contains("engine run"));
        assertTrue(items.contains("desktop"));
        for (String stream : VerbCatalog.STREAMS) assertTrue(items.contains(stream), stream);
        assertFalse(envelope.has("lines"));
    }

    @Test
    void markdownMatchesTheCommittedFile() throws Exception {
        String generated = VerbCatalog.writeMarkdown().replace("\r\n", "\n");
        if (Boolean.getBoolean("pkb.update.verb-inventory")) {
            Files.createDirectories(MARKDOWN.getParent());
            Files.writeString(MARKDOWN, generated, StandardCharsets.UTF_8);
        }
        assertTrue(Files.isRegularFile(MARKDOWN), "docs/verb-inventory.md");
        String expected = Files.readString(MARKDOWN, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertEquals(generated, expected);
    }

    @Test
    void privateRouteFails() throws Exception {
        assertFalse(VerbCatalog.routeAllowed("/secret-action"));
        assertTrue(VerbCatalog.routeAllowed("/store"));
        assertTrue(VerbCatalog.routeAllowed("/scenarios"));
        assertTrue(VerbCatalog.routeAllowed("/"));
        Path ui = Path.of("workbench-ui");
        assertTrue(Files.isDirectory(ui), "workbench-ui");
        String server = Files.readString(ui.resolve("server.mjs"), StandardCharsets.UTF_8);
        String page = Files.readString(ui.resolve("pages/index.html"), StandardCharsets.UTF_8);
        assertTrue(server.contains("\"/node_modules/\""), server);
        assertTrue(server.contains("\"/ui/\""), server);
        assertTrue(page.contains("\"/node_modules/monaco-editor/min/vs\""), page);
        assertTrue(VerbCatalog.routeAllowed("/node_modules/monaco-editor/min/vs/loader.js"));
        assertTrue(VerbCatalog.routeAllowed("/ui/session/page.html"));
        List<String> forbidden = VerbCatalog.forbiddenRoutes(ui);
        assertTrue(forbidden.isEmpty(), forbidden.toString());
    }

    @Test
    void generatedHtmlOutsideTheCatalogFails() {
        assertFalse(VerbCatalog.outsideCatalog("<script>fetch(\"/not-a-verb\")</script>").isEmpty());
        assertTrue(VerbCatalog.outsideCatalog("<script>fetch(\"/store\", {method:\"POST\"})</script>").isEmpty());
    }

    private static Set<String> names(List<VerbCatalog.Row> rows) {
        TreeSet<String> names = new TreeSet<>();
        for (VerbCatalog.Row row : rows) names.add(row.name());
        return names;
    }

    private static Capture run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = EngineCli.execute(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
