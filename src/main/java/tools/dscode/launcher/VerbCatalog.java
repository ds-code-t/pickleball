package tools.dscode.launcher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.desktop.DesktopTree;
import dev.domengine.mcp.McpRegistry;
import tools.dscode.common.agentfast.AgentEnvelope;
import tools.dscode.engine.EngineCli;
import tools.dscode.store.StoreCli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * One registry of CLI verbs. {@code engine inventory} prints it.
 * {@code kind=stream} is only the six follow verbs. The mcp-only count is 0.
 */
public final class VerbCatalog {
    /** Follow verbs. Every other row is {@code cli}. */
    public static final List<String> STREAMS = List.of(
            "browser-events",
            "chat",
            "domengine session log",
            "log tail",
            "observe",
            "record"
    );

    private static final Set<String> STREAM_NAMES = Set.copyOf(STREAMS);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DOMENGINE = "dev.domengine.cli.DomEngineMain.dispatch";
    private static final String STORE = "tools.dscode.store.StoreCli.execute";
    private static final String ENGINE = "tools.dscode.engine.EngineCli.execute";
    private static final String AGENT = "tools.dscode.launcher.WorkbenchAgentCommands.run";
    private static final String SESSION = "tools.dscode.launcher.WorkbenchSessionCommands.run";
    private static final String DISPATCH = "tools.dscode.launcher.VerbDispatch.run";
    private static final String LOG = "tools.dscode.common.composite.DomLogCli.execute";
    private static final String DASHBOARD = "tools.dscode.store.dashboard.DashboardCli.execute";
    private static final String CHAT = "tools.dscode.common.agentcontrol.AgentControl.execute";
    private static final String CROSS_STORE = "tools.dscode.launcher.CrossStore.execute";
    private static final String SPINE = "tools.dscode.launcher.SpineCli.execute";

    /**
     * Commands the domengine MCP {@code surfaces} tool lists. That tool is an index of
     * these CLI verbs, so it is not an mcp-only verb.
     */
    private static final List<String> SURFACE_VERBS = List.of(
            "html-write", "html-replace", "html-insert", "html-remove", "html-cleanup",
            "overlay", "overlay-poll", "overlay-remove", "highlight", "unhighlight",
            "label", "unlabel", "border", "unborder",
            "arrow", "unarrow", "step-badge", "unstep-badge",
            "magnifier", "unmagnifier", "spotlight", "unspotlight"
    );

    private static final List<Row> ROWS = List.copyOf(build());

    private VerbCatalog() {
    }

    public record Row(String name, String source, String kind, String entry) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("source", source);
            map.put("kind", kind);
            map.put("entry", entry);
            return map;
        }
    }

    public static List<Row> rows() {
        return ROWS;
    }

    public static int mcpOnlyCount() {
        return mcpOnly().size();
    }

    /** MCP registrations whose verb is not a catalog row. Empty. */
    public static List<String> mcpOnly() {
        List<String> missing = new ArrayList<>();
        for (String name : McpRegistry.names()) {
            if (!covers(name)) missing.add(name);
        }
        return List.copyOf(missing);
    }

    public static boolean covers(String name) {
        if (name == null || name.isBlank()) return false;
        if (contains(name) || contains("domengine " + name)) return true;
        if ("surfaces".equals(name)) {
            for (String verb : SURFACE_VERBS) {
                if (!contains(verb)) return false;
            }
            return true;
        }
        return false;
    }

    /** Tab-separated table. The last line is {@code mcp-only} and the count. */
    public static String table() {
        StringBuilder text = new StringBuilder();
        text.append("name\tsource\tkind\tentry\n");
        for (Row row : ROWS) {
            text.append(row.name()).append('\t')
                    .append(row.source()).append('\t')
                    .append(row.kind()).append('\t')
                    .append(row.entry()).append('\n');
        }
        text.append("mcp-only\t").append(mcpOnlyCount()).append('\n');
        return text.toString();
    }

    /** One agent envelope. The rows are {@code items}. */
    public static String json(Path project) {
        Map<String, Object> body = new LinkedHashMap<>();
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Row row : ROWS) lines.add(row.toMap());
        body.put("lines", lines);
        body.put("mcpOnly", mcpOnlyCount());
        Map<String, Object> envelope = AgentEnvelope.apply(body, 0, "engine inventory", project, null);
        try {
            return JSON.writeValueAsString(envelope);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("error: verb inventory JSON failed", failure);
        }
    }

    /** Committed copy is {@code docs/verb-inventory.md}. */
    public static String writeMarkdown() {
        StringBuilder text = new StringBuilder();
        text.append("# Verb inventory\n\n");
        text.append("Generated by `VerbCatalog.writeMarkdown`. A hand edit that drifts fails `VerbCatalogTest`.\n\n");
        text.append("`engine inventory` prints this table. `engine inventory --json` is one agent envelope. The rows are `items`.\n\n");
        text.append("MCP-only count: ").append(mcpOnlyCount()).append("\n\n");
        text.append("| name | source | kind | entry |\n");
        text.append("|---|---|---|---|\n");
        for (Row row : ROWS) {
            text.append("| ").append(row.name())
                    .append(" | ").append(row.source())
                    .append(" | ").append(row.kind())
                    .append(" | ").append(row.entry())
                    .append(" |\n");
        }
        return text.toString();
    }

    private static boolean contains(String name) {
        for (Row row : ROWS) {
            if (row.name().equals(name)) return true;
        }
        return false;
    }

    private static List<Row> build() {
        Map<String, Row> rows = new LinkedHashMap<>();
        TreeSet<String> workbench = new TreeSet<>();
        workbench.addAll(WorkbenchCommandLine.FORWARDED_COMMANDS);
        workbench.addAll(WorkbenchCommandLine.AGENT_CORE_COMMANDS);
        workbench.addAll(WorkbenchCommandLine.SESSION_CLIENT_COMMANDS);
        for (String name : workbench) {
            add(rows, name, workbenchSource(name), workbenchEntry(name));
        }
        add(rows, "store", "store", STORE);
        for (String command : StoreCli.COMMANDS) {
            add(rows, "store " + command, "store", STORE);
        }
        add(rows, "engine", "engine", ENGINE);
        for (String command : EngineCli.COMMANDS) {
            add(rows, "engine " + command, "engine", ENGINE);
        }
        add(rows, "domengine", "domengine", DOMENGINE);
        for (String command : DomEngineMain.COMMANDS) {
            String name = "desktop".equals(command) ? "desktop" : "domengine " + command;
            add(rows, name, "domengine", DOMENGINE);
        }
        for (String verb : DomEngineMain.SESSION_VERBS) {
            String name = "log".equals(verb) ? "domengine session log" : "domengine session " + verb;
            add(rows, name, "domengine", DOMENGINE);
        }
        for (String verb : DesktopTree.VERBS) {
            add(rows, "desktop " + verb, "domengine", DOMENGINE);
        }
        add(rows, "dashboard", "workbench", DASHBOARD);
        add(rows, "dashboard query", "workbench", DASHBOARD);
        add(rows, "dashboard xref", "workbench", DASHBOARD);
        add(rows, "dashboard overlap", "workbench", DASHBOARD);
        add(rows, "log", "workbench", LOG);
        add(rows, "log-tail", "workbench", LOG);
        add(rows, "log tail", "workbench", LOG);
        add(rows, "chat", "workbench", CHAT);
        add(rows, "spine", "workbench", SPINE);
        add(rows, "spine sample", "workbench", SPINE);
        add(rows, "spine start", "workbench", SPINE);
        add(rows, "spine stop", "workbench", SPINE);
        add(rows, "spine warm", "workbench", SPINE);
        add(rows, "spine warm status", "workbench", SPINE);
        add(rows, "cross", "workbench", CROSS_STORE);
        add(rows, "cross note", "workbench", CROSS_STORE);
        add(rows, "cross journal", "workbench", CROSS_STORE);
        add(rows, "cross link", "workbench", CROSS_STORE);
        List<Row> list = new ArrayList<>(rows.values());
        list.sort(Comparator.comparing(Row::name));
        return list;
    }

    private static void add(Map<String, Row> rows, String name, String source, String entry) {
        String kind = STREAM_NAMES.contains(name) ? "stream" : "cli";
        String useEntry = entry == null || entry.isBlank() ? DISPATCH : entry;
        Row existing = rows.get(name);
        if (existing == null) {
            rows.put(name, new Row(name, source, kind, useEntry));
            return;
        }
        String merged = existing.source().equals(source) ? source : "cross";
        String useKind = "stream".equals(existing.kind()) || "stream".equals(kind) ? "stream" : "cli";
        rows.put(name, new Row(name, merged, useKind, existing.entry()));
    }

    private static String workbenchSource(String name) {
        int sets = 0;
        if (WorkbenchCommandLine.isAgentCoreCommand(name)) sets++;
        if (WorkbenchCommandLine.isSessionClientCommand(name)) sets++;
        if (WorkbenchCommandLine.isForwardedCommand(name)) sets++;
        return sets > 1 ? "cross" : "workbench";
    }

    private static String workbenchEntry(String name) {
        if (WorkbenchCommandLine.isAgentCoreCommand(name)) return AGENT;
        if (WorkbenchCommandLine.isSessionClientCommand(name)) return SESSION;
        return DISPATCH;
    }
}
