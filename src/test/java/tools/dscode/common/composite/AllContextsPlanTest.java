package tools.dscode.common.composite;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domcomposite.Argv;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.seleniumextensions.AllContextsPlan;
import tools.dscode.common.seleniumextensions.AllContextsScan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One all-contexts query replaces the frame and shadow walk.
 * Before: a find, a script, and a frame switch per context. After: 1 query.
 */
class AllContextsPlanTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void clean() {
        System.clearProperty(CompositeBinaryLocator.BIN_KEY);
        System.clearProperty(CompositeMode.KEY);
        System.clearProperty(CompositeLog.KEY);
        System.clearProperty(CompositeLog.DIR_KEY);
        CompositeRuntime.resetForTests();
    }

    @Test
    void pierceIsQueryOnly() {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("xpath", "//*");
        body.put("mode", "composite");
        body.put("all_frames", true);
        body.put("pierce", "open");
        List<String> query = Argv.from("query", body);
        assertTrue(query.contains("--pierce"), query.toString());
        assertTrue(query.contains("open"), query.toString());
        assertTrue(query.contains("--all-frames"), query.toString());
        List<String> click = Argv.from("click", body);
        assertFalse(click.contains("--pierce"), click.toString());
    }

    @Test
    void currentFrameIsLiveAndAnotherFrameIsOneForeignHit() {
        ObjectNode report = report();
        frame(report, "main", null, "", "", 0, false, true, null, 0);
        frame(report, "child", "main", "only", "only", 1, false, false, 0, 0);
        node(report, "main", "stay", 0, 0, true);
        node(report, "child", "spin", 1, 0, true);
        node(report, "child", "shut", 1, 0, true, true, false);
        node(report, "main", "hidden", 0, 0, false);
        AllContextsPlan.Result top = AllContextsPlan.classify(report, List.of());
        assertEquals(2, top.hits().size());
        assertFalse(top.hits().get(0).foreign());
        assertEquals("stay", top.hits().get(0).node().path("element_id").asText());
        assertTrue(top.hits().get(1).foreign());
        AllContextsPlan.Result inside = AllContextsPlan.classify(
                report,
                List.of(new AllContextsPlan.FrameStep("only", "only", 0))
        );
        assertEquals(2, inside.hits().size());
        assertFalse(inside.hits().get(0).foreign());
        assertEquals("spin", inside.hits().get(0).node().path("element_id").asText());
        assertTrue(inside.hits().get(1).foreign());
        assertNull(inside.hits().get(1).node());
    }

    @Test
    void depthAndFrameCapAreWarningsAndClosedShadowsDrop() {
        ObjectNode deep = report();
        ((ObjectNode) deep.get("context")).put("depth_exceeded", true);
        frame(deep, "main", null, "", "", 0, false, true, null, 0);
        frame(deep, "too-deep", "main", "level-6", "level-6", 6, false, false, null, 0);
        node(deep, "too-deep", "deep-spin", 6, 0, true);
        AllContextsPlan.Result depth = AllContextsPlan.classify(deep, List.of());
        assertTrue(depth.hits().isEmpty());
        assertTrue(depth.depthExceeded());
        assertFalse(depth.frameCapped());

        ObjectNode cap = report();
        ((ObjectNode) cap.get("context")).put("frame_capped", true);
        frame(cap, "main", null, "", "", 0, false, true, null, 0);
        frame(cap, "first", "main", "cap-1", "cap-1", 1, false, false, 0, 0);
        frame(cap, "last", "main", "cap-21", "cap-21", 1, false, false, null, 20);
        node(cap, "first", "cap-spin", 1, 0, true);
        node(cap, "last", "late", 1, 0, true);
        AllContextsPlan.Result within = AllContextsPlan.classify(cap, List.of());
        assertEquals(1, within.hits().size());
        assertTrue(within.hits().get(0).foreign());
        assertTrue(within.frameCapped());
        assertEquals(AllContextsScan.MAX_FRAMES, 20);
        assertEquals(AllContextsScan.MAX_DEPTH, 5);

        ObjectNode cross = report();
        frame(cross, "main", null, "", "", 0, false, true, null, 0);
        frame(cross, "other", "main", "cross", "cross", 1, true, false, null, 0);
        node(cross, "other", "cross-spin", 1, 0, true);
        assertTrue(AllContextsPlan.classify(cross, List.of()).hits().isEmpty());
        assertNull(AllContextsPlan.classify(MAPPER.createObjectNode(), List.of()));
    }

    @Test
    void scanQueryIsOneCall() throws Exception {
        Path log = temp.resolve("calls.log");
        Path binary = stub(temp.resolve("composite"), log);
        System.setProperty(CompositeBinaryLocator.BIN_KEY, binary.toString());
        System.setProperty(CompositeLog.KEY, "adapter");
        System.setProperty(CompositeLog.DIR_KEY, temp.resolve("logs").toString());
        assertTrue(ElementOperations.primary());
        DomCompositeAdapter.get().resetCommandsForTests();
        var report = DomCompositeAdapter.get().queryAllContexts("//*[@aria-busy='true']");
        assertEquals(List.of("query"), DomCompositeAdapter.get().commandsForTests());
        assertEquals(1, report.path("nodes").size());
        assertEquals("spin", report.path("nodes").get(0).path("element_id").asText());
        String calls = Files.readString(log);
        assertEquals(1, calls.lines().filter(line -> line.startsWith("query ")).count(), calls);
        assertTrue(calls.contains("--pierce"), calls);
        assertTrue(calls.contains("open"), calls);
        assertTrue(calls.contains("--all-frames"), calls);
        assertTrue(calls.contains("--mode"), calls);
        assertTrue(calls.contains("composite"), calls);
        String trace = Files.readString(CompositeLog.file());
        assertTrue(trace.contains("scan all-contexts"), trace);
        AllContextsPlan.Result plan = AllContextsPlan.classify(report, List.of());
        assertEquals(1, plan.hits().size());
        assertTrue(plan.hits().get(0).foreign());
    }

    private static ObjectNode report() {
        ObjectNode report = MAPPER.createObjectNode();
        report.putArray("nodes");
        ObjectNode context = report.putObject("context");
        context.putArray("frames");
        context.put("depth_exceeded", false);
        context.put("frame_capped", false);
        return report;
    }

    private static void frame(
            ObjectNode report,
            String id,
            String parent,
            String name,
            String elementId,
            int depth,
            boolean cross,
            boolean main,
            Integer order,
            int index
    ) {
        ObjectNode row = ((ArrayNode) report.get("context").get("frames")).addObject();
        row.put("id", id);
        if (parent == null) {
            row.putNull("parent");
        } else {
            row.put("parent", parent);
        }
        row.put("name", name);
        row.put("element_id", elementId);
        row.put("depth", depth);
        row.put("cross_origin", cross);
        row.put("is_main", main);
        if (order == null) {
            row.putNull("order");
        } else {
            row.put("order", order);
        }
        row.put("index", index);
    }

    private static void node(ObjectNode report, String frame, String elementId, int frameDepth, int shadowDepth, boolean displayed) {
        node(report, frame, elementId, frameDepth, shadowDepth, displayed, false, false);
    }

    private static void node(
            ObjectNode report,
            String frame,
            String elementId,
            int frameDepth,
            int shadowDepth,
            boolean displayed,
            boolean closed,
            boolean cross
    ) {
        ObjectNode node = ((ArrayNode) report.get("nodes")).addObject();
        node.put("frame_id", frame);
        node.put("element_id", elementId);
        node.put("id", frame + ":1");
        node.put("frame_depth", frameDepth);
        node.put("shadow_depth", shadowDepth);
        node.put("displayed", displayed);
        node.put("in_closed_shadow", closed);
        node.put("cross_origin", cross);
        node.put("node_name", "DIV");
    }

    private static Path stub(Path path, Path log) throws Exception {
        String script = """
                #!/usr/bin/env python3
                import json, sys
                log = %s
                def note(argv):
                    with open(log, "a", encoding="utf-8") as handle:
                        handle.write(" ".join(argv) + "\\n")
                body = {
                    "count": 1,
                    "nodes": [{
                        "id": "child:4",
                        "frame_id": "child",
                        "element_id": "spin",
                        "node_name": "DIV",
                        "frame_depth": 1,
                        "shadow_depth": 0,
                        "displayed": True,
                        "in_closed_shadow": False,
                        "cross_origin": False,
                        "attributes": {"id": "spin"}
                    }],
                    "context": {
                        "depth_exceeded": False,
                        "frame_capped": False,
                        "frames": [
                            {"id": "main", "parent": None, "name": "", "element_id": "", "depth": 0, "cross_origin": False, "is_main": True, "order": None, "index": 0},
                            {"id": "child", "parent": "main", "name": "only", "element_id": "only", "depth": 1, "cross_origin": False, "is_main": False, "order": 0, "index": 0}
                        ]
                    }
                }
                for line in sys.stdin:
                    line = line.strip()
                    if not line:
                        continue
                    req = json.loads(line)
                    argv = req.get("argv") or []
                    note(argv)
                    if "version" in argv:
                        payload = {"version": "2.7.0"}
                    elif "capabilities" in argv:
                        payload = {"ok": True, "desktop": ["list", "query", "close"], "actions": ["click", "type", "scroll"]}
                    elif "create" in argv:
                        payload = {"ok": True, "id": "sess-1", "launched": False}
                    elif argv and argv[0] == "query":
                        payload = body
                    else:
                        payload = {"ok": True}
                    sys.stdout.write(json.dumps({
                        "id": req.get("id"),
                        "exit": 0,
                        "stdout": json.dumps(payload) + "\\n",
                        "stderr": ""
                    }) + "\\n")
                    sys.stdout.flush()
                """.formatted(quote(log.toString()));
        Files.createDirectories(path.getParent());
        Files.writeString(path, script);
        Files.setPosixFilePermissions(path, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
        ));
        return path;
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
