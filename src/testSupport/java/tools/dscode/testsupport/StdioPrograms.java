package tools.dscode.testsupport;

import tools.dscode.testsupport.StdioFake.Reply;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Canned stdio replies. Each mode is the Java form of one Python fake.
 * A newline inside {@code stdout} matches {@code json.dumps(body) + "\\n"}.
 */
final class StdioPrograms {
    private static final String SAVE = """
            {"id":"main:1","text":"Save","role":"button","bounds":{"x":1,"y":2,"width":10,"height":8},"attributes":{"id":"save"},"states":["enabled"],"source":"Dom"}""";
    private static final String OK = "{\"ok\":true}";
    private static final String CREATED = "{\"ok\":true,\"id\":\"sess-1\",\"launched\":false}";

    private StdioPrograms() {
    }

    static void run(String[] args) throws Exception {
        String mode = args[0];
        switch (mode) {
            case "verb-plan" -> verbPlan(Path.of(args[1]), Boolean.parseBoolean(args[2]));
            case "capability" -> capability(Path.of(args[1]), args[2], args[3], args[4]);
            case "location" -> location();
            case "join-refused" -> joinRefused();
            case "wrapper-actions" -> wrapperActions(Path.of(args[1]));
            case "snapshot" -> snapshot(Path.of(args[1]), Boolean.parseBoolean(args[2]));
            case "nest" -> nest(Path.of(args[1]), Boolean.parseBoolean(args[2]));
            case "position" -> position(Path.of(args[1]), Boolean.parseBoolean(args[2]));
            case "contexts" -> contexts(Path.of(args[1]));
            case "lean" -> lean(Path.of(args[1]), args[2], Boolean.parseBoolean(args[3]));
            case "geometry" -> geometry(Path.of(args[1]), Boolean.parseBoolean(args[2]), Boolean.parseBoolean(args[3]), Boolean.parseBoolean(args[4]), Boolean.parseBoolean(args[5]));
            case "selector" -> selector(Path.of(args[1]), Boolean.parseBoolean(args[2]));
            case "session" -> session(Path.of(args[1]), args[2], args.length > 3 ? args[3] : "");
            case "page-query" -> pageQuery(Path.of(args[1]), Boolean.parseBoolean(args[2]));
            default -> throw new IllegalArgumentException("unknown stdio fake mode " + mode);
        }
    }

    private static void verbPlan(Path log, boolean verbs) throws Exception {
        String actions = verbs
                ? "[\"click\",\"type\",\"scroll\",\"submit\",\"key\",\"dblclick\",\"contextclick\",\"drag\"]"
                : "[\"click\",\"type\",\"scroll\"]";
        StdioFake.serve(request -> {
            StdioFake.logArgv(log, request.argv());
            List<String> argv = request.argv();
            if (StdioFake.has(argv, "version")) return Reply.json("{\"version\":\"2.5.0\"}");
            if (StdioFake.has(argv, "capabilities")) {
                return Reply.json("{\"ok\":true,\"desktop\":[\"list\",\"query\",\"close\"],\"actions\":" + actions + "}");
            }
            if (StdioFake.has(argv, "create")) return Reply.json(CREATED);
            if (any(argv, "query", "click", "type", "scroll", "submit", "key", "dblclick", "contextclick", "drag")) {
                return Reply.json("{\"ok\":true,\"composite\":[" + SAVE + "]}");
            }
            return Reply.json(OK);
        });
    }

    private static void capability(Path log, String version, String name, String fail) throws Exception {
        int[] calls = {0};
        String hit = """
                {"ok":true,"composite":[{"id":"main:1","text":"Save","role":"button","bounds":{"x":1,"y":2,"width":10,"height":8},"attributes":{"id":"save"},"states":["enabled"],"source":"Desktop"}]}""";
        String success = "{\"ok\":true,\"name\":" + StdioFake.quote(name) + ",\"version\":" + StdioFake.quote(version)
                + ",\"desktop\":[\"list\",\"attach\",\"walk\",\"query\",\"resolve\",\"close\",\"click\",\"type\"],"
                + "\"actions\":[\"click\",\"type\",\"scroll\",\"hover\"],\"query_flags\":[\"limit\",\"nth\"]}";
        StdioFake.serve(request -> {
            StdioFake.logArgv(log, request.argv());
            List<String> argv = request.argv();
            if (StdioFake.has(argv, "version") && !StdioFake.has(argv, "capabilities")) {
                return Reply.json("{\"name\":" + StdioFake.quote(name) + ",\"version\":" + StdioFake.quote(version) + "}");
            }
            if (StdioFake.has(argv, "capabilities")) {
                calls[0]++;
                if ("always".equals(fail) || calls[0] == 1) {
                    return Reply.of(1, "", "{\"ok\":false,\"error\":\"boom\",\"code\":\"failed\"}");
                }
                return Reply.json(success);
            }
            if (StdioFake.has(argv, "desktop")) return Reply.json(hit);
            return Reply.json(CREATED);
        });
    }

    private static void location() throws Exception {
        StdioFake.serve(request -> {
            List<String> argv = request.argv();
            if (StdioFake.has(argv, "version")) return Reply.json("{\"version\":\"2.4.0\"}");
            if (StdioFake.has(argv, "create")) return Reply.json(CREATED);
            return Reply.json(OK);
        });
    }

    private static void joinRefused() throws Exception {
        StdioFake.serve(request -> {
            if (StdioFake.has(request.argv(), "version")) {
                return Reply.of(0, "{\"version\":\"2.4.0\"}", "join refused");
            }
            return Reply.of(1, "", "join refused");
        });
    }

    private static void wrapperActions(Path log) throws Exception {
        StdioFake.serve(request -> {
            StdioFake.logArgv(log, request.argv());
            List<String> argv = request.argv();
            if (StdioFake.has(argv, "version")) return Reply.json("{\"version\":\"2.4.0\"}");
            if (StdioFake.has(argv, "capabilities")) {
                return Reply.json("{\"ok\":true,\"desktop\":[\"list\",\"query\",\"close\"],\"actions\":[\"click\",\"type\",\"scroll\"]}");
            }
            if (StdioFake.has(argv, "create")) return Reply.json(CREATED);
            if (any(argv, "query", "click", "type")) return Reply.json("{\"ok\":true,\"composite\":[" + SAVE + "]}");
            return Reply.json(OK);
        });
    }

    private static void snapshot(Path log, boolean eval) throws Exception {
        String actions = eval
                ? "[\"click\",\"type\",\"scroll\",\"submit\",\"key\",\"dblclick\",\"contextclick\",\"drag\",\"eval\"]"
                : "[\"click\",\"type\",\"scroll\"]";
        String value = """
                {"tag":"div","text":"Hello","value":"","selectedValue":"sv","attributes":{"id":"host"},"siblingIndex":2,"sameTagIndex":1,"stableSelector":"#host","elementState":{"disabled":true,"blank":false,"required":false,"expanded":true,"attributeOn":false,"selectable":false,"domSelected":false}}""";
        StdioFake.serve(request -> {
            StdioFake.logArgv(log, request.argv());
            List<String> argv = request.argv();
            if (StdioFake.has(argv, "version")) return Reply.json("{\"version\":\"2.6.0\"}");
            if (StdioFake.has(argv, "capabilities")) {
                return Reply.json("{\"ok\":true,\"desktop\":[\"list\",\"query\",\"close\"],\"actions\":" + actions + "}");
            }
            if (StdioFake.has(argv, "create")) return Reply.json(CREATED);
            if (!argv.isEmpty() && "eval".equals(argv.get(0))) {
                return Reply.json("{\"ok\":true,\"kind\":\"eval\",\"backend_node_id\":12,\"value\":" + value + "}");
            }
            if (any(argv, "query", "click")) {
                return Reply.json("""
                        {"ok":true,"composite":[{"id":"main:12","text":"Save","role":"button","bounds":{"x":1,"y":2,"width":10,"height":8},"attributes":{"id":"host"},"states":["enabled"],"source":"Dom"}]}""");
            }
            return Reply.json(OK);
        });
    }

    private static void nest(Path log, boolean gates) throws Exception {
        String flags = gates ? "[\"within\",\"nesting\"]" : "[]";
        String evalNodes = gates ? "\"one or many\"" : "null";
        StdioFake.serve(request -> {
            StdioFake.logArgv(log, request.argv());
            List<String> argv = request.argv();
            if (StdioFake.has(argv, "version")) return Reply.json("{\"version\":\"2.9.0\"}");
            if (StdioFake.has(argv, "capabilities")) {
                return Reply.json("{\"ok\":true,\"actions\":[\"click\",\"type\",\"scroll\",\"eval\"],\"query_flags\":"
                        + flags + ",\"eval_nodes\":" + evalNodes + "}");
            }
            if (StdioFake.has(argv, "create")) return Reply.json(CREATED);
            if (!argv.isEmpty() && "eval".equals(argv.get(0))) {
                return Reply.json("""
                        {"ok":true,"kind":"eval","values":[{"node_id":"main:1","backend_node_id":1,"value":{"tag":"div","text":"a","siblingIndex":1,"sameTagIndex":0,"stableSelector":"#a","attributes":{}}},{"node_id":"main:2","backend_node_id":2,"value":{"tag":"div","text":"b","siblingIndex":2,"sameTagIndex":1,"stableSelector":"#b","attributes":{}}}]}""");
            }
            if (!argv.isEmpty() && "query".equals(argv.get(0)) && StdioFake.has(argv, "--nesting")) {
                return Reply.json("{\"ok\":true,\"nodes\":[" + span("main:inner", "deep") + "]}");
            }
            if (!argv.isEmpty() && "query".equals(argv.get(0)) && StdioFake.has(argv, "--within")) {
                return Reply.json("{\"ok\":true,\"nodes\":[" + span("main:span", "inside") + "]}");
            }
            return Reply.json(OK);
        });
    }

    private static void position(Path log, boolean nth) throws Exception {
        String flags = nth
                ? "[\"within\",\"nesting\",\"nth\",\"every\",\"count-only\"]"
                : "[\"within\",\"nesting\"]";
        String[] names = {"one", "two", "three", "four", "five"};
        StdioFake.serve(request -> {
            StdioFake.logArgv(log, request.argv());
            List<String> argv = request.argv();
            if (StdioFake.has(argv, "version")) return Reply.json("{\"version\":\"2.10.0\"}");
            if (StdioFake.has(argv, "capabilities")) {
                return Reply.json("{\"ok\":true,\"actions\":[\"click\",\"type\",\"scroll\",\"eval\"],\"query_flags\":"
                        + flags + ",\"eval_nodes\":null}");
            }
            if (StdioFake.has(argv, "create")) return Reply.json(CREATED);
            if (!argv.isEmpty() && "query".equals(argv.get(0))) {
                List<String> rows = new ArrayList<>();
                for (int i = 0; i < names.length; i++) rows.add(span("main:" + (i + 1), names[i]));
                if (StdioFake.has(argv, "--count-only")) {
                    return Reply.json("{\"ok\":true,\"count\":" + rows.size() + ",\"nodes\":[],\"count_only\":true}");
                }
                String nthValue = presentFlag(argv, "--nth");
                List<String> chosen = rows;
                if (nthValue != null) {
                    int start = Integer.parseInt(nthValue);
                    if (start < 0) start = rows.size() + start;
                    String everyValue = presentFlag(argv, "--every");
                    Integer step = everyValue == null ? null : Integer.valueOf(everyValue);
                    if (start < 0 || start >= rows.size()) chosen = List.of();
                    else if (step != null && step != 0) {
                        List<String> sliced = new ArrayList<>();
                        for (int i = start; i < rows.size(); i += step) sliced.add(rows.get(i));
                        chosen = sliced;
                    } else chosen = List.of(rows.get(start));
                }
                return Reply.json("{\"ok\":true,\"count\":" + chosen.size() + ",\"nodes\":[" + String.join(",", chosen) + "]}");
            }
            return Reply.json(OK);
        });
    }

    private static void contexts(Path log) throws Exception {
        String body = """
                {"count":1,"nodes":[{"id":"child:4","frame_id":"child","element_id":"spin","node_name":"DIV","frame_depth":1,"shadow_depth":0,"displayed":true,"in_closed_shadow":false,"cross_origin":false,"attributes":{"id":"spin"}}],"context":{"depth_exceeded":false,"frame_capped":false,"frames":[{"id":"main","parent":null,"name":"","element_id":"","depth":0,"cross_origin":false,"is_main":true,"order":null,"index":0},{"id":"child","parent":"main","name":"only","element_id":"only","depth":1,"cross_origin":false,"is_main":false,"order":0,"index":0}]}}""";
        StdioFake.serve(request -> {
            StdioFake.logArgv(log, request.argv());
            List<String> argv = request.argv();
            if (StdioFake.has(argv, "version")) return Reply.json("{\"version\":\"2.7.0\"}");
            if (StdioFake.has(argv, "capabilities")) {
                return Reply.json("{\"ok\":true,\"desktop\":[\"list\",\"query\",\"close\"],\"actions\":[\"click\",\"type\",\"scroll\"]}");
            }
            if (StdioFake.has(argv, "create")) return Reply.json(CREATED);
            if (!argv.isEmpty() && "query".equals(argv.get(0))) return Reply.json(body);
            return Reply.json(OK);
        });
    }

    private static void lean(Path log, String queryMode, boolean eval) throws Exception {
        String actions = eval
                ? "[\"click\",\"type\",\"scroll\",\"eval\"]"
                : "[\"click\",\"type\",\"scroll\"]";
        String snapshot = """
                {"tag":"div","text":"Hello","value":"","selectedValue":"","attributes":{"id":"host"},"siblingIndex":0,"sameTagIndex":0,"stableSelector":"#host","elementState":{"disabled":false,"blank":true,"required":false,"expanded":false,"attributeOn":false,"selectable":false,"domSelected":false}}""";
        StdioFake.serve(request -> {
            StdioFake.logArgv(log, request.argv());
            List<String> argv = request.argv();
            String text = StdioFake.joined(argv);
            if (StdioFake.has(argv, "version")) return Reply.json("{\"version\":\"2.8.0\"}");
            if (StdioFake.has(argv, "capabilities")) {
                return Reply.json("{\"ok\":true,\"desktop\":[\"list\",\"query\",\"close\"],\"actions\":" + actions + "}");
            }
            if (StdioFake.has(argv, "create")) return Reply.json(CREATED);
            if (!argv.isEmpty() && "eval".equals(argv.get(0))) {
                if (text.contains("isConnected")) return Reply.json("{\"ok\":true,\"kind\":\"eval\",\"value\":true}");
                return Reply.json("{\"ok\":true,\"kind\":\"eval\",\"backend_node_id\":42,\"value\":" + snapshot + "}");
            }
            if (!argv.isEmpty() && "query".equals(argv.get(0))) {
                if ("reject".equals(queryMode)) {
                    return Reply.of(2, "{}\n", "unexpected argument --poll-ms");
                }
                if ("timeout".equals(queryMode)) {
                    return Reply.of(1, "{}\n", "{\"code\":\"timeout\",\"error\":\"timed out waiting\"}");
                }
                if (text.contains("ancestor::")) {
                    return Reply.json("{\"ok\":true,\"composite\":[" + div("main:1", "outer") + "]}");
                }
                if (text.contains("descendant::")) {
                    return Reply.json("{\"ok\":true,\"composite\":[" + div("main:3", "inner") + "]}");
                }
                return Reply.json("{\"ok\":true,\"composite\":["
                        + div("main:1", "outer") + "," + div("main:2", "mid") + "," + div("main:3", "inner") + "]}");
            }
            return Reply.json(OK);
        });
    }

    private static void geometry(Path log, boolean geometry, boolean scrollport, boolean open, boolean point) throws Exception {
        StringBuilder flags = new StringBuilder("[\"within\",\"nesting\",\"nth\",\"every\",\"count-only\"");
        if (geometry) flags.append(",\"geometry\"");
        if (point) flags.append(",\"point\"");
        flags.append(']');
        StdioFake.serve(request -> {
            StdioFake.logArgv(log, request.argv());
            List<String> argv = request.argv();
            if (StdioFake.has(argv, "version")) return Reply.json("{\"version\":\"2.11.0\"}");
            if (StdioFake.has(argv, "capabilities")) {
                return Reply.json("{\"ok\":true,\"actions\":[\"click\",\"type\",\"scroll\",\"eval\"],\"query_flags\":"
                        + flags + ",\"eval_nodes\":\"one node\"}");
            }
            if (StdioFake.has(argv, "create")) return Reply.json(CREATED);
            if (!argv.isEmpty() && "query".equals(argv.get(0))) {
                String node = geometryNode(StdioFake.has(argv, "--geometry"), scrollport, open);
                return Reply.json("{\"ok\":true,\"count\":1,\"nodes\":[" + node + "],\"composite\":[" + node + "]}");
            }
            return Reply.json(OK);
        });
    }

    private static void selector(Path log, boolean desktopClick) throws Exception {
        String desktop = desktopClick
                ? "[\"list\",\"attach\",\"walk\",\"query\",\"resolve\",\"close\",\"click\",\"type\"]"
                : "[\"list\",\"attach\",\"walk\",\"query\",\"resolve\",\"close\"]";
        StdioFake.serve(request -> {
            StdioFake.logArgv(log, request.argv());
            List<String> argv = request.argv();
            if (StdioFake.has(argv, "version")) return Reply.json("{\"version\":\"2.4.0\"}");
            if (StdioFake.has(argv, "capabilities")) {
                return Reply.json("{\"ok\":true,\"desktop\":" + desktop + ",\"actions\":[\"click\",\"type\",\"scroll\"]}");
            }
            if (StdioFake.has(argv, "create")) return Reply.json(CREATED);
            if (any(argv, "desktop", "query", "click", "type", "scroll")) {
                String source = StdioFake.has(argv, "desktop") ? "Desktop" : "Dom";
                return Reply.json("{\"ok\":true,\"composite\":[{"
                        + "\"id\":\"main:1\",\"text\":\"Save\",\"role\":\"button\","
                        + "\"bounds\":{\"x\":1,\"y\":2,\"width\":10,\"height\":8},"
                        + "\"attributes\":{\"id\":\"save\"},\"states\":[\"enabled\"],\"source\":\"" + source + "\"}]}");
            }
            return Reply.json(OK);
        });
    }

    private static void session(Path log, String rejectMode, String token) throws Exception {
        StdioFake.serve(request -> {
            StdioFake.logJson(log, request.argv());
            List<String> argv = request.argv();
            int exit = 0;
            String body;
            if (StdioFake.has(argv, "version")) {
                body = "{\"version\":\"2.4.0\"}";
            } else if (argv.size() >= 2 && "session".equals(argv.get(0)) && "create".equals(argv.get(1))) {
                String cdp = StdioFake.flag(argv, "--cdp");
                boolean reject = ("page".equals(rejectMode) && cdp.contains("/devtools/page/"))
                        || ("token".equals(rejectMode) && token != null && !token.isEmpty() && cdp.contains(token));
                if (reject) {
                    exit = 1;
                    body = "{\"ok\":false,\"error\":\"page session refused\"}";
                } else {
                    body = "{\"ok\":true,\"id\":" + StdioFake.quote(StdioFake.flag(argv, "--id"))
                            + ",\"cdp\":" + StdioFake.quote(cdp) + ",\"launched\":false}";
                }
            } else {
                body = OK;
            }
            return Reply.of(exit, body, "");
        });
    }

    private static void pageQuery(Path script, boolean geometry) throws Exception {
        String name = script.getFileName() == null ? "composite" : script.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        Path log = script.getParent() == null ? Path.of(name + ".requests") : script.getParent().resolve(name + ".requests");
        String flags = geometry
                ? "\"within\",\"nesting\",\"nth\",\"every\",\"count-only\",\"geometry\",\"point\",\"exposure\",\"limit\""
                : "\"within\",\"nesting\",\"nth\",\"every\",\"count-only\",\"point\",\"exposure\",\"limit\"";
        String engines = geometry
                ? "\"css\",\"xpath\",\"text\",\"near\",\"below\",\"above\",\"left-of\",\"right-of\""
                : "\"css\",\"xpath\",\"text\"";
        StdioFake.serve(request -> {
            StdioFake.logJson(log, request.argv());
            return Reply.json(pageBody(request.argv(), flags, engines));
        });
    }

    private static String pageBody(List<String> argv, String flags, String engines) {
        if (StdioFake.has(argv, "version")) return "{\"version\":\"2.4.0\"}";
        if (StdioFake.has(argv, "create")) return CREATED;
        if (StdioFake.has(argv, "capabilities")) {
            return "{\"ok\":true,\"query_flags\":[" + flags + "],\"engines\":[" + engines + "]}";
        }
        if (StdioFake.has(argv, "query")) {
            String selector = StdioFake.flag(argv, "--selector");
            boolean promote = StdioFake.has(argv, "--promote-visual");
            if (selector.startsWith("image=")) {
                if (promote) {
                    return """
                            {"ok":true,"composite":[{"id":"main:4","handle":"main:4","text":"logo","role":"img","source":"cdp","bounds":{"x":1,"y":2,"width":10,"height":8},"attributes":{"id":"logo"},"states":["enabled"],"rank":0,"score":0.8}]}""";
                }
                return """
                        {"ok":true,"composite":[{"id":"visual:1","text":"logo","role":"image","source":"visual","bounds":{"x":1,"y":2,"width":10,"height":8},"states":["enabled"],"rank":0,"score":0.8,"human_limits":{"codes":["source_only","human_could_not_follow"],"sentence":"a person could not follow this hit because it is a visual match with no DOM node"}}]}""";
            }
            if ("css=a".equals(selector)) {
                return """
                        {"ok":true,"composite":[{"id":"main:7","handle":"main:7","text":"Docs","role":"link","source":"cdp","bounds":{"x":1,"y":2,"width":10,"height":8},"attributes":{"id":"docs"},"states":["enabled"],"rank":0,"score":0.9},{"id":"main:8","handle":"main:8","text":"Api","role":"link","source":"cdp","bounds":{"x":20,"y":2,"width":10,"height":8},"attributes":{"id":"api"},"states":["enabled"],"rank":1,"score":0.4}]}""";
            }
            String label = selector.isEmpty() ? "Save" : selector;
            return "{\"ok\":true,\"composite\":[{"
                    + "\"id\":\"main:7\",\"text\":" + StdioFake.quote(label) + ",\"role\":\"button\",\"source\":\"Dom\","
                    + "\"bounds\":{\"x\":1,\"y\":2,\"width\":10,\"height\":8},\"attributes\":{\"id\":\"save\"},"
                    + "\"states\":[\"enabled\"],\"score\":0.5}],\"nodes\":[{"
                    + "\"frame_id\":\"main\",\"backend_node_id\":7,\"node_name\":\"BUTTON\",\"text\":" + StdioFake.quote(label)
                    + ",\"handle\":\"main:7\",\"rank\":0,\"score\":0.5,\"human_limits\":{"
                    + "\"codes\":[\"off_visual_viewport\"],\"sentence\":\"scrolled out of the visual viewport\"}}],"
                    + "\"explain\":[{\"why\":\"css matched 1\"}]}";
        }
        if (any(argv, "click", "type")) {
            String selector = StdioFake.flag(argv, "--selector");
            String exposure = StdioFake.flag(argv, "--exposure");
            if ("human".equals(exposure) && !selector.contains(":")) return OK;
            String handle = selector.contains(":") ? selector : "main:9";
            String sentence = "a person could not have seen or clicked it because it is scrolled out of the visual viewport and has opacity:0";
            boolean lower = "lower".equals(exposure);
            String limits = "{\"codes\":[\"human_could_not_see\",\"human_could_not_click\",\"off_visual_viewport\",\"opacity_zero\"],\"sentence\":"
                    + StdioFake.quote(sentence) + "}";
            StringBuilder body = new StringBuilder();
            body.append("{\"ok\":true,\"node\":{");
            body.append("\"id\":").append(StdioFake.quote(handle));
            body.append(",\"handle\":").append(StdioFake.quote(handle));
            body.append(",\"text\":").append(lower ? "\"hidden\"" : "\"Docs\"");
            body.append(",\"role\":\"button\",\"bounds\":{\"x\":1,\"y\":900,\"width\":10,\"height\":8}");
            body.append(",\"states\":[").append(lower ? "\"hidden\"" : "\"enabled\"").append(']');
            body.append(",\"human_limits\":").append(lower ? limits : "null");
            body.append('}');
            if (lower) {
                body.append(",\"human_limits\":{\"codes\":[\"human_could_not_see\",\"human_could_not_click\",\"off_visual_viewport\",\"opacity_zero\"],\"sentence\":");
                body.append(StdioFake.quote("acted on node " + handle + "; " + sentence));
                body.append('}');
            }
            body.append('}');
            return body.toString();
        }
        return OK;
    }

    private static String geometryNode(boolean withGeometry, boolean scrollport, boolean open) {
        StringBuilder row = new StringBuilder();
        row.append("{\"id\":\"main:7\",\"node_name\":\"BUTTON\",\"text\":\"Go\",\"attributes\":{\"id\":\"go\"},");
        row.append("\"bounds\":{\"x\":12,\"y\":20,\"width\":20,\"height\":16}");
        if (withGeometry) {
            row.append(",\"geometry\":{");
            row.append("\"closed_shadow\":false,");
            row.append("\"closed_shadow_limit\":").append(StdioFake.quote(
                    "Page script hit-testing retargets to the host. Corners are CDP or stored quads, not page-JS elementFromPoint."));
            row.append(",\"document_css_px\":").append(space(12, 20, 20, 16));
            row.append(",\"visual_viewport_relative\":").append(space(10, 17, 20, 16));
            row.append(",\"layout_viewport_relative\":").append(space(12, 18, 20, 16));
            row.append(",\"nearest_scrollport_relative\":").append(space(2, 4, 20, 16));
            row.append(",\"frame_document_relative\":").append(space(12, 20, 20, 16));
            row.append(",\"nearest_scrollport\":{\"kind\":\"viewport\",\"id\":\"main\",\"origin\":");
            row.append(scrollport ? "\"client-box\"" : "\"frame-scroll\"").append('}');
            if (scrollport) {
                row.append(",\"scrollport\":{\"client\":{\"document_css_px\":").append(space(108, 104, 180, 140));
                row.append("},\"content\":{\"document_css_px\":").append(space(78, 54, 400, 800)).append("}}");
            }
            if (open) {
                row.append(",\"expanded\":true,\"expanded_source\":\"aria-expanded\"");
                row.append(",\"revealed\":{\"document_css_px\":").append(space(4, 30, 40, 18)).append('}');
                row.append(",\"corners\":[{\"box\":\"border\",\"index\":0,\"x\":5.0,\"y\":7.0,\"hit\":true,\"reason\":null}]");
            }
            row.append('}');
        }
        row.append('}');
        return row.toString();
    }

    private static String space(int x, int y, int w, int h) {
        return "{\"kind\":\"quad\",\"corners\":[[" + x + "," + y + "],[" + (x + w) + "," + y + "],["
                + (x + w) + "," + (y + h) + "],[" + x + "," + (y + h) + "]],\"x\":" + x + ",\"y\":" + y
                + ",\"width\":" + w + ",\"height\":" + h + "}";
    }

    private static String span(String id, String text) {
        return "{\"id\":" + StdioFake.quote(id) + ",\"node_name\":\"SPAN\",\"text\":" + StdioFake.quote(text)
                + ",\"attributes\":{},\"bounds\":{\"x\":1,\"y\":2,\"width\":10,\"height\":8}}";
    }

    private static String div(String id, String htmlId) {
        return "{\"id\":" + StdioFake.quote(id) + ",\"node_name\":\"DIV\",\"text\":" + StdioFake.quote(htmlId)
                + ",\"attributes\":{\"id\":" + StdioFake.quote(htmlId)
                + "},\"bounds\":{\"x\":1,\"y\":2,\"width\":10,\"height\":8}}";
    }

    /** {@code null} when the flag is absent, matching the Python {@code flag} helper. */
    private static String presentFlag(List<String> argv, String name) {
        for (int i = 0; i < argv.size(); i++) {
            if (name.equals(argv.get(i))) return i + 1 < argv.size() ? argv.get(i + 1) : null;
        }
        return null;
    }

    private static boolean any(List<String> argv, String... tokens) {
        for (String token : tokens) if (argv.contains(token)) return true;
        return false;
    }
}
