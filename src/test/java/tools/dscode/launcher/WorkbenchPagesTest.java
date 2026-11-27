package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The served Workbench page is the production caller for the Scenarios, Globals,
 * Data, branch, code, and Point controls.
 */
class WorkbenchPagesTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SOURCE = "class Steps { void ready() {} }\n";
    private static final String JS_SOURCE = "When(\"the total is 10\")\n";

    @Test
    void servedPageLoadsGlueSourceAndCallsStoreForEachTab(@TempDir Path project) throws Exception {
        try (WorkbenchUiServer.Handle handle = WorkbenchUiServer.start(project)) {
            String home = exchange(handle.port(), request("GET", "/?t=" + handle.token(), "127.0.0.1"));
            assertTrue(status(home).startsWith("HTTP/1.1 200"), status(home));
            String page = body(home);
            int scriptAt = page.indexOf("<script>");
            int scriptEnd = page.lastIndexOf("</script>");
            assertTrue(scriptAt >= 0 && scriptEnd > scriptAt, page);
            String script = page.substring(scriptAt + "<script>".length(), scriptEnd);
            Path dir = project.resolve("page-drive");
            Files.createDirectories(dir);
            Path scriptFile = dir.resolve("page.js");
            Path fixtures = dir.resolve("fixtures.json");
            Path harness = dir.resolve("harness.js");
            Files.writeString(scriptFile, script, StandardCharsets.UTF_8);
            Files.writeString(fixtures, fixtures(), StandardCharsets.UTF_8);
            Files.writeString(harness, HARNESS, StandardCharsets.UTF_8);
            JsonNode report = drive(harness, scriptFile, fixtures);

            assertEquals(SOURCE, report.path("opened").asText(), report.toString());
            assertEquals(SOURCE, textArg(report, "glue", "put"), report.toString());
            assertEquals(List.of("main", "feature"), names(report.path("branches")), report.toString());
            assertEquals("#9b2335", report.path("accent").asText(), report.toString());
            assertTrue(hasArgs(report, "sandbox", "use", "feature"), report.toString());
            assertTrue(hasArgs(report, "var", "set", "pkb_browser", "CHROME_HEADLESS"), report.toString());
            String data = report.path("data").asText();
            assertTrue(data.contains("notes/a.txt"), data);
            assertFalse(data.contains("notes/b.txt"), data);
            assertTrue(hasArgs(report, "files", "list", "--text=exact:notes/a.txt"), report.toString());
            assertTrue(hasArgs(report, "sql", "--text=re:alpha"), report.toString());
            assertTrue(hasArgs(report, "query", "--text=pay"), report.toString());
            assertTrue(hasArgs(report, "--session=s1", "--handle=h42"), report.toString());
            assertFalse(hasArgs(report, "--x=0", "--y=0"), report.toString());
            assertTrue(hasArgs(report, "steps", "list", "--feature=features/pay.feature", "--scenario=1"), report.toString());
            assertTrue(hasArgs(report, "glue", "show", "--path=src/test/java/PaySteps.java"), report.toString());
            assertTrue(hasArgs(report, "glue", "show", "--path=glue/js/pay.js"), report.toString());
            assertTrue(hasArgs(report, "glue", "show", "--path=glue/js/pay.ts"), report.toString());
            String definitions = report.path("definitions").asText();
            assertTrue(definitions.contains(".java") && definitions.contains(".js") && definitions.contains(".ts"), definitions);
            assertEquals(JS_SOURCE, report.path("matched").asText(), report.toString());
            assertEquals("javascript", report.path("language").asText(), report.toString());

            int scenarios = page.indexOf("id=\"scenarios\"");
            int point = page.indexOf("id=\"point\"");
            int sql = page.indexOf("<section id=\"sql\">");
            int search = page.indexOf("id=\"search\"");
            assertTrue(search >= 0 && scenarios > search, page);
            assertTrue(point > scenarios && sql > point, page);
            assertTrue(page.contains("id=\"var-set\""), page);
            assertTrue(page.contains("id=\"lease\""), page);
            assertTrue(page.contains("\"ask-answer\""), page);
            assertEquals("", report.path("error").asText(), report.toString());
        }
    }

    private static JsonNode drive(Path harness, Path script, Path fixtures) throws Exception {
        Process process = new ProcessBuilder(nodeBinary(), harness.toString(), script.toString(), fixtures.toString())
                .redirectErrorStream(true)
                .start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        process.getInputStream().transferTo(output);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), output.toString(StandardCharsets.UTF_8));
        String text = output.toString(StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), text);
        return JSON.readTree(text);
    }

    private static String nodeBinary() {
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(";")) {
                if (dir.isBlank()) continue;
                Path candidate = Path.of(dir.trim(), "node.exe");
                if (Files.isRegularFile(candidate)) return candidate.toString();
            }
        }
        return "node";
    }

    private static String fixtures() throws Exception {
        Map<String, String> fixtures = new LinkedHashMap<>();
        fixtures.put("glueShow", envelope("store glue", outcome(SOURCE, "src/main/java/Steps.java")));
        fixtures.put("glueShowJava", envelope("store glue", outcome("When(\"other step\")\n", "src/test/java/PaySteps.java")));
        fixtures.put("glueShowJs", envelope("store glue", outcome(JS_SOURCE, "glue/js/pay.js")));
        fixtures.put("glueShowTs", envelope("store glue", outcome("When(\"other step\")\n", "glue/js/pay.ts")));
        fixtures.put("glueList", envelope("store glue", Map.of(
                "text", "",
                "items", List.of(
                        Map.of("path", "src/test/java/PaySteps.java"),
                        Map.of("path", "glue/js/pay.js"),
                        Map.of("path", "glue/js/pay.ts")))));
        fixtures.put("sandbox", envelope("store sandbox", Map.of(
                "command", "sandbox list",
                "count", 2,
                "sandboxes", List.of(
                        Map.of("name", "main", "inUse", true),
                        Map.of("name", "feature", "inUse", false)))));
        fixtures.put("scenarios", envelope("store scenarios", Map.of(
                "scenarios", List.of(Map.of(
                        "path", "features/pay.feature",
                        "ord", 1,
                        "keyword", "Scenario",
                        "name", "Pay")))));
        fixtures.put("steps", envelope("store steps", Map.of(
                "steps", List.of(Map.of(
                        "path", "features/pay.feature",
                        "scenarioOrd", 1,
                        "scenario", "Pay",
                        "ord", 1,
                        "keyword", "When",
                        "text", "the total is 10")))));
        fixtures.put("files", envelope("store files", Map.of(
                "files", List.of(
                        file("notes/a.txt"),
                        file("notes/b.txt")))));
        fixtures.put("vars", envelope("store var", Map.of(
                "command", "var list",
                "vars", List.of(Map.of("name", "pkb_browser", "value", "CHROME_HEADLESS")))));
        return JSON.writeValueAsString(fixtures);
    }

    private static Map<String, Object> file(String path) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("path", path);
        row.put("sourceType", "text");
        row.put("edited", false);
        row.put("bytes", 4);
        row.put("contentHash", path);
        row.put("lastSyncedHash", "");
        return row;
    }

    private static Map<String, Object> outcome(String text, String path) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("ok", true);
        item.put("text", text);
        item.put("error", "");
        item.put("fix", "");
        item.put("path", path);
        item.put("items", List.of(Map.of("id", path, "path", path, "name", path)));
        return item;
    }

    private static String envelope(String op, Object item) throws Exception {
        ObjectNode root = JSON.createObjectNode();
        root.put("v", 1);
        root.put("ok", true);
        root.put("op", op);
        root.set("items", JSON.valueToTree(List.of(item)));
        return JSON.writeValueAsString(root);
    }

    private static String textArg(JsonNode report, String... parts) {
        for (JsonNode call : report.path("calls")) {
            String joined = String.join("\n", texts(call.path("args")));
            if (!containsAll(joined, parts)) continue;
            for (JsonNode arg : call.path("args")) {
                String text = arg.asText();
                if (text.startsWith("--text=")) return text.substring("--text=".length());
            }
        }
        return "";
    }

    private static boolean hasArgs(JsonNode report, String... parts) {
        for (JsonNode call : report.path("calls")) {
            List<String> args = texts(call.path("args"));
            if (call.path("verb").asText().isBlank()) args.add(0, "store");
            String joined = String.join("\n", args);
            if (containsAll(joined, parts)) return true;
        }
        return false;
    }

    private static boolean containsAll(String joined, String... parts) {
        for (String part : parts) {
            if (!joined.contains(part)) return false;
        }
        return true;
    }

    private static List<String> names(JsonNode node) {
        List<String> names = new ArrayList<>();
        if (node.isArray()) for (JsonNode item : node) names.add(item.asText());
        return names;
    }

    private static List<String> texts(JsonNode node) {
        List<String> texts = new ArrayList<>();
        if (node.isArray()) for (JsonNode item : node) texts.add(item.asText());
        return texts;
    }

    private static String request(String method, String path, String host) {
        return method + " " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n";
    }

    private static String exchange(int port, String head) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(head.getBytes(StandardCharsets.UTF_8));
            socket.shutdownOutput();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(body);
            return body.toString(StandardCharsets.UTF_8);
        }
    }

    private static String status(String response) {
        int end = response.indexOf('\n');
        return end < 0 ? response : response.substring(0, end).trim();
    }

    private static String body(String response) {
        int split = response.indexOf("\r\n\r\n");
        return split < 0 ? response : response.substring(split + 4);
    }

    private static final String HARNESS = """
            const fs = require("fs");
            const fixtures = JSON.parse(fs.readFileSync(process.argv[3], "utf8"));
            const calls = [];
            const elements = {};
            function make(id) {
                let text = "";
                const node = {
                    id: id || "",
                    value: "",
                    hidden: false,
                    children: [],
                    dataset: {},
                    style: {},
                    listeners: {},
                    setAttribute() {},
                    getAttribute() { return ""; },
                    appendChild(child) { this.children.push(child); return child; },
                    addEventListener(type, fn) { (this.listeners[type] ||= []).push(fn); },
                    async dispatch(type) {
                        const list = (this.listeners[type] || []).slice();
                        for (const fn of list) await fn();
                    }
                };
                Object.defineProperty(node, "textContent", {
                    get() { return text; },
                    set(value) {
                        text = value == null ? "" : String(value);
                        if (text === "") node.children = [];
                    }
                });
                if (id) elements[id] = node;
                return node;
            }
            const rootStyle = { props: {}, setProperty(name, value) { this.props[name] = value; } };
            const document = {
                title: "",
                documentElement: { style: rootStyle },
                body: { appendChild() {} },
                getElementById(id) { return elements[id] || null; },
                querySelectorAll() { return []; },
                createElement() { return make(""); }
            };
            const location = { search: "?t=token", pathname: "/scenarios" };
            let editor = null;
            const monaco = {
                editor: {
                    create(node, options) {
                        editor = {
                            value: options.value,
                            language: options.language,
                            getValue() { return this.value; },
                            setValue(value) { this.value = value; },
                            getModel() { return this; }
                        };
                        return editor;
                    },
                    setModelLanguage(model, language) { model.language = language; }
                }
            };
            function reply(body) {
                const args = body.args || [];
                const verb = body.verb || "store";
                const joined = args.join("\\n");
                if (verb === "point") return { out: "{\\"ok\\":true}\\n", err: "" };
                if (joined.includes("glue") && joined.includes("show")) {
                    const path = args.find((arg) => arg.startsWith("--path=")) || "";
                    if (path.endsWith("pay.js")) return { out: fixtures.glueShowJs, err: "" };
                    if (path.endsWith("pay.ts")) return { out: fixtures.glueShowTs, err: "" };
                    if (path.endsWith("PaySteps.java")) return { out: fixtures.glueShowJava, err: "" };
                    return { out: fixtures.glueShow, err: "" };
                }
                if (joined.includes("glue") && joined.includes("list")) return { out: fixtures.glueList, err: "" };
                if (joined.includes("sandbox") && joined.includes("list")) return { out: fixtures.sandbox, err: "" };
                if (joined.includes("scenarios")) return { out: fixtures.scenarios, err: "" };
                if (joined.includes("steps")) return { out: fixtures.steps, err: "" };
                if (joined.includes("files")) return { out: fixtures.files, err: "" };
                if (joined.includes("var") && joined.includes("list")) return { out: fixtures.vars, err: "" };
                return { out: "{\\"ok\\":true}\\n", err: "" };
            }
            async function fetch(url, init) {
                const body = JSON.parse(init.body);
                calls.push(body);
                return { json: async () => reply(body) };
            }
            global.document = document;
            global.location = location;
            global.fetch = fetch;
            global.window = global;
            global.monaco = monaco;
            for (const id of ["title", "branch", "lease", "ask-id", "ask-value", "ask-answer", "search",
                    "scenarios", "globals", "data", "sql", "definition-path", "open-definition", "save-definition",
                    "monaco", "definition-message", "scenario-body", "scenario-list", "step-list", "definition-list",
                    "globals-body", "data-body", "var-name", "var-value", "var-set", "query", "run-sql", "point",
                    "point-session", "point-handle", "point-x", "point-y", "sql-body"]) {
                make(id);
            }
            const report = { opened: "", matched: "", language: "", accent: "", data: "", definitions: "",
                    branches: [], calls: [], error: "" };
            function capture() {
                report.calls = calls.map((call) => ({ verb: call.verb || "store", args: call.args || [] }));
                const branch = document.getElementById("branch");
                report.branches = branch ? branch.children.map((option) => option.value) : [];
                report.accent = rootStyle.props["--accent"] || "";
                const data = document.getElementById("data-body");
                report.data = data ? data.textContent : "";
                const definitions = document.getElementById("definition-list");
                report.definitions = definitions ? definitions.textContent : "";
                report.matched = editor ? editor.value : "";
                report.language = editor ? editor.language : "";
            }
            async function flush() {
                await new Promise((resolve) => setImmediate(resolve));
                await new Promise((resolve) => setImmediate(resolve));
            }
            (async () => {
                try {
                    eval(fs.readFileSync(process.argv[2], "utf8"));
                    await flush();
                    document.getElementById("definition-path").value = "src/main/java/Steps.java";
                    await document.getElementById("open-definition").dispatch("click");
                    report.opened = editor ? editor.value : "";
                    await document.getElementById("save-definition").dispatch("click");
                    const branch = document.getElementById("branch");
                    branch.value = "feature";
                    await branch.dispatch("change");
                    await flush();
                    const varName = document.getElementById("var-name");
                    const varValue = document.getElementById("var-value");
                    if (!varName || !varValue) throw new Error("missing var form");
                    varName.value = "pkb_browser";
                    varValue.value = "CHROME_HEADLESS";
                    await document.getElementById("var-set").dispatch("click");
                    location.pathname = "/data";
                    document.getElementById("search").value = "exact:notes/a.txt";
                    await document.getElementById("search").dispatch("change");
                    location.pathname = "/sql";
                    document.getElementById("search").value = "re:alpha";
                    document.getElementById("query").value = "SELECT 1";
                    await document.getElementById("run-sql").dispatch("click");
                    location.pathname = "/scenarios";
                    document.getElementById("search").value = "pay";
                    await document.getElementById("search").dispatch("change");
                    document.getElementById("point-session").value = "s1";
                    document.getElementById("point-handle").value = "h42";
                    await document.getElementById("point").dispatch("click");
                    const scenario = document.getElementById("scenario-list").children[0];
                    await scenario.dispatch("click");
                    await flush();
                    const step = document.getElementById("step-list").children[0];
                    await step.dispatch("click");
                    await flush();
                    capture();
                } catch (failure) {
                    capture();
                    report.error = String(failure && failure.stack ? failure.stack : failure);
                }
                process.stdout.write(JSON.stringify(report));
            })();
            """;
}
