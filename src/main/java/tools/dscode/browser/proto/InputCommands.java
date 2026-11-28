package tools.dscode.browser.proto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.AriaSnapshotMode;
import com.microsoft.playwright.options.MouseButton;
import tools.dscode.browser.BrowserProtocols;
import tools.dscode.browser.BrowserProtocols.Backend;
import tools.dscode.browser.UnsupportedProtocolOperation;
import tools.dscode.common.agentcontrol.HumanLock;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * E8: W3C action chains ({@code input}), accessibility locators ({@code find --role}), Playwright aria snapshots and
 * their refs ({@code aria-snap}, {@code act --aria-ref}). Backends follow {@link BrowserProtocols#CAPABILITIES}.
 */
final class InputCommands {
    /** Describes one element; works with {@code this} (CDP callFunctionOn) or the first argument (BiDi, Playwright). */
    static final String DESCRIBE_FN = "function(n){const el=(n&&n.nodeType)?n:this;const r=el.getBoundingClientRect();"
            + "const ids=(el.getAttribute('aria-describedby')||'').split(/\\s+/).filter(Boolean);"
            + "const desc=el.ariaDescription||el.getAttribute('aria-description')||ids.map(i=>{const d=document.getElementById(i);return d?d.textContent.trim():''}).join(' ')||el.title||'';"
            + "return JSON.stringify({tag:el.localName,id:el.id||'',text:((el.innerText||el.value||'')+'').trim().slice(0,120),description:desc,"
            + "box:[Math.round(r.x),Math.round(r.y),Math.round(r.width),Math.round(r.height)]})}";

    private InputCommands() {
    }

    static void register() {
        ProtoHost.register("input", InputCommands::input);
        ProtoHost.register("find-role", InputCommands::findRole);
        ProtoHost.register("aria-snap", InputCommands::ariaSnap);
        ProtoHost.register("aria-act", InputCommands::ariaAct);
    }

    // ---- input ----

    static JsonNode chain(ProtoHost host, String raw) throws Exception {
        if (raw == null || raw.isBlank() || "true".equals(raw)) {
            throw new IllegalArgumentException("error: input needs --actions=<json>|@chain.json\nfix: input --actions='[{\"type\":\"key\",\"id\":\"k\",\"actions\":[{\"type\":\"keyDown\",\"value\":\"a\"},{\"type\":\"keyUp\",\"value\":\"a\"}]}]'");
        }
        if (raw.startsWith("@")) raw = Files.readString(host.project().resolve(raw.substring(1)).normalize());
        JsonNode node = ProtoJson.MAPPER.readTree(raw);
        if (node.isObject() && node.has("actions")) node = node.get("actions");
        if (!node.isArray()) throw new IllegalArgumentException("error: --actions is a W3C action list (an array of sources)\nfix: see help input");
        for (JsonNode source : node) {
            String type = source.path("type").asText();
            if (!Set.of("key", "pointer", "wheel", "none").contains(type)) {
                throw new IllegalArgumentException("error: action source type " + type + " is not key, pointer, wheel or none\nfix: see help input");
            }
            if (!source.path("actions").isArray()) throw new IllegalArgumentException("error: source " + source.path("id").asText() + " has no actions array\nfix: see help input");
        }
        return node;
    }

    /** True when no pointer action is anchored to an element, so the chain is coordinate and keyboard input only. */
    static boolean coordinateOnly(JsonNode chain) {
        for (JsonNode source : chain) {
            for (JsonNode a : source.path("actions")) {
                JsonNode origin = a.path("origin");
                if (origin.isObject()) return false;
            }
        }
        return true;
    }

    /** {@code --text} and {@code --at} with no {@code --actions} is one {@code Input.insertText}. */
    static boolean oneInsertText(Map<String, String> flags) {
        if (flags == null) return false;
        String actions = flags.get("actions");
        if (actions != null && !actions.isBlank()) return false;
        String text = flags.get("text");
        String at = flags.get("at");
        return text != null && !text.isBlank() && at != null && !at.isBlank();
    }

    static Map<String, Object> insertTextParams(String text) {
        return Map.of("text", text == null ? "" : text);
    }

    static ObjectNode input(ProtoHost host, Map<String, String> f) throws Exception {
        String session = f == null ? null : f.get("session");
        boolean blocked = HumanLock.inputBlocked(host.project(), session);
        if (blocked) HumanLock.passAgentInput(host.project(), session);
        try {
            return dispatchInput(host, f);
        } finally {
            if (blocked) HumanLock.restoreInputBlock(host.project(), session);
        }
    }

    private static ObjectNode dispatchInput(ProtoHost host, Map<String, String> f) throws Exception {
        if (f != null && f.containsKey("at") && (f.get("at") == null || f.get("at").isBlank() || "true".equals(f.get("at")))
                && (f.get("actions") == null || f.get("actions").isBlank())) {
            throw new IllegalArgumentException("error: input --at needs a short id\nfix: find --query=<name>, then input --text=<s> --at=<short id>");
        }
        if (oneInsertText(f)) {
            CdpSession.Target target = ContextCommands.target(host, f);
            host.cdp().send(target.sessionId, "Input.insertText", insertTextParams(f.get("text")));
            return ProtoJson.obj().put("op", "input").put("via", "cdp").put("at", f.get("at")).put("calls", 1);
        }
        boolean release = f.containsKey("release") && !f.containsKey("actions");
        JsonNode chain = release ? null : chain(host, f.get("actions"));
        String via = f.get("via");
        if (chain != null && !coordinateOnly(chain) && (via == null || "auto".equals(via))) via = "bidi";
        BrowserProtocols.Served<String> served = host.protocols().serve("input", via, backend -> {
            switch (backend) {
                case BIDI -> {
                    String context = ContextCommands.bidiContext(host, f);
                    if (chain != null) host.bidiModules().performActions(context, ProtoJson.MAPPER.convertValue(chain, List.class));
                    if (release || f.containsKey("release")) host.bidiModules().releaseActions(context);
                }
                case CDP -> {
                    CdpSession.Target t = ContextCommands.target(host, f);
                    Held held = held(host);
                    try {
                        if (chain != null) ticks(chain, (source, a) -> cdp(host, t.sessionId, held, source, a));
                    } catch (RuntimeException failure) {
                        throw failure;
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure.getMessage(), failure);
                    }
                    if (release || f.containsKey("release")) releaseCdp(host, t.sessionId, held);
                }
                case PLAYWRIGHT -> host.pw().call(c -> {
                    Page page = PwBridge.page(c, f.get("page"));
                    Held held = held(host);
                    try {
                        if (chain != null) ticks(chain, (source, a) -> pw(page, held, source, a));
                        if (release || f.containsKey("release")) releasePw(page, held);
                    } catch (RuntimeException failure) {
                        throw failure;
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure.getMessage(), failure);
                    }
                    return null;
                });
                default -> throw new UnsupportedProtocolOperation("input", backend.id(), null);
            }
            return backend.id();
        });
        int count = 0;
        if (chain != null) for (JsonNode source : chain) count += source.path("actions").size();
        host.log("input actions=" + count + (f.containsKey("release") ? " release" : "") + " via=" + served.value());
        return ProtoJson.obj().put("op", "input").put("via", served.value()).put("actions", count).put("released", f.containsKey("release"));
    }

    interface Step {
        void run(JsonNode source, JsonNode action) throws Exception;
    }

    /** Runs the chain tick by tick (action i of every source, then i+1), honoring pause durations. */
    static void ticks(JsonNode chain, Step step) throws Exception {
        int longest = 0;
        for (JsonNode source : chain) longest = Math.max(longest, source.path("actions").size());
        for (int i = 0; i < longest; i++) {
            long pause = 0;
            for (JsonNode source : chain) {
                JsonNode a = source.path("actions").path(i);
                if (a.isMissingNode()) continue;
                if ("pause".equals(a.path("type").asText())) pause = Math.max(pause, a.path("duration").asLong(0));
                else step.run(source, a);
                if (a.has("duration") && !"pause".equals(a.path("type").asText())) pause = Math.max(pause, a.path("duration").asLong(0));
            }
            if (pause > 0) ContextCommands.sleep(Math.min(pause, 10_000));
        }
    }

    /** Keys and buttons held down, per host, so input --release can let them go on CDP and Playwright. */
    static final class Held {
        final Set<String> keys = new LinkedHashSet<>();
        final Set<Integer> buttons = new LinkedHashSet<>();
        final Map<String, double[]> pointer = new LinkedHashMap<>();
        int modifiers;
    }

    static Held held(ProtoHost host) {
        return (Held) host.stateMap().computeIfAbsent("input.held", k -> new Held());
    }

    /** W3C special key code points to DOM key names, code and Windows virtual key code. */
    static final Map<String, Object[]> SPECIAL = new LinkedHashMap<>();

    static {
        SPECIAL.put("\uE003", new Object[]{"Backspace", "Backspace", 8});
        SPECIAL.put("\uE004", new Object[]{"Tab", "Tab", 9});
        SPECIAL.put("\uE006", new Object[]{"Enter", "NumpadEnter", 13});
        SPECIAL.put("\uE007", new Object[]{"Enter", "Enter", 13});
        SPECIAL.put("\uE008", new Object[]{"Shift", "ShiftLeft", 16});
        SPECIAL.put("\uE009", new Object[]{"Control", "ControlLeft", 17});
        SPECIAL.put("\uE00A", new Object[]{"Alt", "AltLeft", 18});
        SPECIAL.put("\uE00C", new Object[]{"Escape", "Escape", 27});
        SPECIAL.put("\uE00D", new Object[]{" ", "Space", 32});
        SPECIAL.put("\uE00E", new Object[]{"PageUp", "PageUp", 33});
        SPECIAL.put("\uE00F", new Object[]{"PageDown", "PageDown", 34});
        SPECIAL.put("\uE010", new Object[]{"End", "End", 35});
        SPECIAL.put("\uE011", new Object[]{"Home", "Home", 36});
        SPECIAL.put("\uE012", new Object[]{"ArrowLeft", "ArrowLeft", 37});
        SPECIAL.put("\uE013", new Object[]{"ArrowUp", "ArrowUp", 38});
        SPECIAL.put("\uE014", new Object[]{"ArrowRight", "ArrowRight", 39});
        SPECIAL.put("\uE015", new Object[]{"ArrowDown", "ArrowDown", 40});
        SPECIAL.put("\uE017", new Object[]{"Delete", "Delete", 46});
        SPECIAL.put("\uE03D", new Object[]{"Meta", "MetaLeft", 91});
    }

    static String keyName(String value) {
        Object[] special = SPECIAL.get(value);
        return special == null ? value : (String) special[0];
    }

    static int modifierBit(String key) {
        return switch (key) {
            case "Alt" -> 1;
            case "Control" -> 2;
            case "Meta" -> 4;
            case "Shift" -> 8;
            default -> 0;
        };
    }

    static String button(int b) {
        return switch (b) {
            case 1 -> "middle";
            case 2 -> "right";
            default -> "left";
        };
    }

    static double[] point(Held held, JsonNode source, JsonNode a) {
        double[] at = held.pointer.getOrDefault(source.path("id").asText(), new double[]{0, 0});
        double x = a.path("x").asDouble(0);
        double y = a.path("y").asDouble(0);
        if ("pointer".equals(a.path("origin").asText())) return new double[]{at[0] + x, at[1] + y};
        if (a.path("origin").isObject()) throw new IllegalArgumentException("error: an element origin needs BiDi\nfix: use --via=bidi or viewport coordinates");
        return new double[]{x, y};
    }

    static void cdp(ProtoHost host, String session, Held held, JsonNode source, JsonNode a) {
        String type = a.path("type").asText();
        switch (source.path("type").asText()) {
            case "key" -> {
                String value = a.path("value").asText();
                Object[] special = SPECIAL.get(value);
                String key = keyName(value);
                Map<String, Object> p = new LinkedHashMap<>();
                boolean down = "keyDown".equals(type);
                if (down) {
                    held.keys.add(value);
                    held.modifiers |= modifierBit(key);
                }
                p.put("type", down ? (special == null || "Enter".equals(key) || " ".equals(key) ? "keyDown" : "rawKeyDown") : "keyUp");
                p.put("key", key);
                p.put("modifiers", held.modifiers);
                if (special != null) {
                    p.put("code", special[1]);
                    p.put("windowsVirtualKeyCode", special[2]);
                } else if (key.length() == 1) {
                    char ch = key.charAt(0);
                    p.put("code", Character.isLetter(ch) ? "Key" + Character.toUpperCase(ch) : Character.isDigit(ch) ? "Digit" + ch : "");
                    p.put("windowsVirtualKeyCode", (int) Character.toUpperCase(ch));
                }
                if (down && (special == null || "Enter".equals(key)) && (held.modifiers & 7) == 0) p.put("text", "Enter".equals(key) ? "\r" : key);
                if (!down) {
                    held.keys.remove(value);
                    held.modifiers &= ~modifierBit(key);
                }
                host.cdp().send(session, "Input.dispatchKeyEvent", p);
            }
            case "pointer" -> {
                String pointerType = source.path("parameters").path("pointerType").asText("mouse");
                String id = source.path("id").asText();
                double[] at = held.pointer.getOrDefault(id, new double[]{0, 0});
                if ("pointerMove".equals(type)) at = point(held, source, a);
                held.pointer.put(id, at);
                int b = a.path("button").asInt(0);
                if ("touch".equals(pointerType)) {
                    String touch = switch (type) {
                        case "pointerDown" -> "touchStart";
                        case "pointerUp" -> "touchEnd";
                        default -> "touchMove";
                    };
                    if ("touchMove".equals(touch) && !held.buttons.contains(b)) return;
                    if ("pointerDown".equals(type)) held.buttons.add(b);
                    if ("pointerUp".equals(type)) held.buttons.remove(b);
                    List<Map<String, Object>> points = "touchEnd".equals(touch) ? List.of() : List.of(ContextCommands.p("x", at[0], "y", at[1]));
                    host.cdp().send(session, "Input.dispatchTouchEvent", ContextCommands.p("type", touch, "touchPoints", points));
                    return;
                }
                String mouse = switch (type) {
                    case "pointerDown" -> "mousePressed";
                    case "pointerUp" -> "mouseReleased";
                    default -> "mouseMoved";
                };
                if ("pointerDown".equals(type)) held.buttons.add(b);
                if ("pointerUp".equals(type)) held.buttons.remove(b);
                Map<String, Object> p = new LinkedHashMap<>(ContextCommands.p("type", mouse, "x", at[0], "y", at[1], "modifiers", held.modifiers,
                        "pointerType", "pen".equals(pointerType) ? "pen" : "mouse"));
                if (!"mouseMoved".equals(mouse)) {
                    p.put("button", button(b));
                    p.put("clickCount", 1);
                } else if (!held.buttons.isEmpty()) p.put("button", button(held.buttons.iterator().next()));
                host.cdp().send(session, "Input.dispatchMouseEvent", p);
            }
            case "wheel" -> {
                if (!"scroll".equals(type)) return;
                double[] at = point(held, source, a);
                host.cdp().send(session, "Input.dispatchMouseEvent", ContextCommands.p("type", "mouseWheel", "x", at[0], "y", at[1],
                        "deltaX", a.path("deltaX").asDouble(0), "deltaY", a.path("deltaY").asDouble(0)));
            }
            default -> {
            }
        }
    }

    static void releaseCdp(ProtoHost host, String session, Held held) {
        for (String value : List.copyOf(held.keys)) {
            host.cdp().send(session, "Input.dispatchKeyEvent", ContextCommands.p("type", "keyUp", "key", keyName(value)));
        }
        double[] at = held.pointer.values().stream().findFirst().orElse(new double[]{0, 0});
        for (int b : List.copyOf(held.buttons)) {
            host.cdp().send(session, "Input.dispatchMouseEvent", ContextCommands.p("type", "mouseReleased", "x", at[0], "y", at[1], "button", button(b), "clickCount", 1));
        }
        held.keys.clear();
        held.buttons.clear();
        held.modifiers = 0;
    }

    static void pw(Page page, Held held, JsonNode source, JsonNode a) {
        String type = a.path("type").asText();
        switch (source.path("type").asText()) {
            case "key" -> {
                String value = a.path("value").asText();
                String key = keyName(value);
                if ("keyDown".equals(type)) {
                    held.keys.add(value);
                    page.keyboard().down(key);
                } else {
                    held.keys.remove(value);
                    page.keyboard().up(key);
                }
            }
            case "pointer" -> {
                String pointerType = source.path("parameters").path("pointerType").asText("mouse");
                String id = source.path("id").asText();
                double[] at = held.pointer.getOrDefault(id, new double[]{0, 0});
                if ("pointerMove".equals(type)) at = point(held, source, a);
                held.pointer.put(id, at);
                int b = a.path("button").asInt(0);
                MouseButton mb = b == 1 ? MouseButton.MIDDLE : b == 2 ? MouseButton.RIGHT : MouseButton.LEFT;
                if ("touch".equals(pointerType)) {
                    if ("pointerDown".equals(type)) held.buttons.add(b);
                    if ("pointerUp".equals(type) && held.buttons.remove(b)) page.touchscreen().tap(at[0], at[1]);
                    return;
                }
                switch (type) {
                    case "pointerMove" -> page.mouse().move(at[0], at[1]);
                    case "pointerDown" -> {
                        held.buttons.add(b);
                        page.mouse().down(new com.microsoft.playwright.Mouse.DownOptions().setButton(mb));
                    }
                    case "pointerUp" -> {
                        held.buttons.remove(b);
                        page.mouse().up(new com.microsoft.playwright.Mouse.UpOptions().setButton(mb));
                    }
                    default -> {
                    }
                }
            }
            case "wheel" -> {
                if (!"scroll".equals(type)) return;
                double[] at = point(held, source, a);
                page.mouse().move(at[0], at[1]);
                page.mouse().wheel(a.path("deltaX").asDouble(0), a.path("deltaY").asDouble(0));
            }
            default -> {
            }
        }
    }

    static void releasePw(Page page, Held held) {
        for (String value : List.copyOf(held.keys)) page.keyboard().up(keyName(value));
        for (int b : List.copyOf(held.buttons)) {
            page.mouse().up(new com.microsoft.playwright.Mouse.UpOptions().setButton(b == 1 ? MouseButton.MIDDLE : b == 2 ? MouseButton.RIGHT : MouseButton.LEFT));
        }
        held.keys.clear();
        held.buttons.clear();
        held.modifiers = 0;
    }

    // ---- find --role ----

    static AriaRole ariaRole(String role) {
        try {
            return AriaRole.valueOf(role.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("error: " + role + " is not an ARIA role\nfix: find --role=button --name=Submit");
        }
    }

    /** {@code --frame} defaults to every frame. {@code top} is the page target. An id matches the target id or url. */
    private static List<CdpSession.Target> roleTargets(ProtoHost host, Map<String, String> f) {
        String frame = f.get("frame");
        if (frame == null || frame.isBlank() || "true".equals(frame)) frame = "all";
        CdpSession.Target page = ContextCommands.target(host, f);
        List<CdpSession.Target> targets = new ArrayList<>();
        if ("top".equalsIgnoreCase(frame)) {
            if (page != null) targets.add(page);
            return targets;
        }
        if ("all".equalsIgnoreCase(frame)) {
            if (page != null) targets.add(page);
            for (CdpSession.Target target : host.cdp().all()) {
                if (target == page) continue;
                if ("iframe".equals(target.type) || "page".equals(target.type)) targets.add(target);
            }
            return targets;
        }
        for (CdpSession.Target target : host.cdp().all()) {
            String url = target.url == null ? "" : target.url;
            if (frame.equals(target.targetId) || url.contains(frame)) targets.add(target);
        }
        return targets;
    }

    /**
     * Role search on one target. An out-of-process iframe is its own target. A same-site iframe stays in the page
     * process, so the page document is pierced and each content document is queried on its own.
     */
    private static void queryRole(ProtoHost host, CdpSession.Target target, String role, String name, String frame, int limit, List<String> described, Set<String> seen) {
        boolean pierce = frame == null || frame.isBlank() || "true".equals(frame) || "all".equalsIgnoreCase(frame);
        try {
            JsonNode root = host.cdp().send(target.sessionId, "DOM.getDocument",
                    ContextCommands.p("depth", pierce ? -1 : 0, "pierce", pierce)).path("root");
            List<JsonNode> docs = new ArrayList<>();
            if (root.isObject() && root.has("nodeId")) docs.add(root);
            if (pierce) collectContentDocuments(root, docs);
            for (JsonNode doc : docs) {
                if (described.size() >= limit) return;
                String token = doc == root ? ("iframe".equals(target.type) ? target.targetId : "") : frameToken(doc);
                if (!frameAllowed(frame, token, target)) continue;
                try {
                    collectRoleNodes(host, target, doc, role, name, token, limit, described, seen);
                } catch (RuntimeException ignored) {
                    // One frame that is still loading does not drop the others.
                }
            }
        } catch (RuntimeException ignored) {
            // A target that left during the scan is skipped.
        }
    }

    private static void collectRoleNodes(ProtoHost host, CdpSession.Target target, JsonNode doc, String role, String name,
            String frameToken, int limit, List<String> described, Set<String> seen) {
        JsonNode nodes = axNodes(host, target, doc, role, name);
        boolean fallback = (nodes == null || !nodes.isArray() || nodes.isEmpty()) && name != null && !name.isBlank();
        if (fallback) nodes = axNodes(host, target, doc, role, null);
        if (nodes == null || !nodes.isArray()) return;
        for (JsonNode node : nodes) {
            if (described.size() >= limit) return;
            if (node.path("ignored").asBoolean(false) || !node.has("backendDOMNodeId")) continue;
            if (fallback && !axName(node).equalsIgnoreCase(name.trim())) continue;
            int backend = node.path("backendDOMNodeId").asInt();
            if (backend <= 0 || !seen.add(target.sessionId + ":" + backend)) continue;
            JsonNode object = host.cdp().send(target.sessionId, "DOM.resolveNode", ContextCommands.p("backendNodeId", backend)).path("object");
            JsonNode read = host.cdp().send(target.sessionId, "Runtime.callFunctionOn", ContextCommands.p("functionDeclaration", DESCRIBE_FN,
                    "objectId", object.path("objectId").asText(), "returnByValue", true));
            described.add(withFrame(read.path("result").path("value").asText(), frameToken));
        }
    }

    private static JsonNode axNodes(ProtoHost host, CdpSession.Target target, JsonNode doc, String role, String name) {
        Map<String, Object> query = new LinkedHashMap<>(ContextCommands.p("nodeId", doc.path("nodeId").asInt(), "role", role));
        if (name != null && !name.isBlank()) query.put("accessibleName", name);
        return host.cdp().send(target.sessionId, "Accessibility.queryAXTree", query).path("nodes");
    }

    private static String axName(JsonNode node) {
        JsonNode name = node.path("name");
        String value = name.path("value").asText("");
        return value.isBlank() ? name.asText("") : value;
    }

    private static void collectContentDocuments(JsonNode node, List<JsonNode> docs) {
        if (node == null || node.isMissingNode()) return;
        JsonNode content = node.get("contentDocument");
        if (content != null && content.isObject() && content.has("nodeId")) {
            docs.add(content);
            collectContentDocuments(content, docs);
        }
        for (JsonNode child : node.path("children")) collectContentDocuments(child, docs);
        for (JsonNode shadow : node.path("shadowRoots")) collectContentDocuments(shadow, docs);
    }

    private static String frameToken(JsonNode doc) {
        String id = doc.path("frameId").asText("");
        return id.isBlank() ? "frame" : id;
    }

    private static boolean frameAllowed(String filter, String frameToken, CdpSession.Target target) {
        if (filter == null || filter.isBlank() || "true".equals(filter) || "all".equalsIgnoreCase(filter)) return true;
        if ("top".equalsIgnoreCase(filter)) return frameToken == null || frameToken.isBlank();
        String url = target.url == null ? "" : target.url;
        return filter.equals(frameToken) || filter.equals(target.targetId) || url.contains(filter);
    }

    private static List<Frame> roleFrames(Page page, String frame) {
        if (frame == null || frame.isBlank() || "true".equals(frame) || "all".equalsIgnoreCase(frame)) return page.frames();
        if ("top".equalsIgnoreCase(frame)) return List.of(page.mainFrame());
        List<Frame> matched = new ArrayList<>();
        for (Frame one : page.frames()) {
            String url = one.url() == null ? "" : one.url();
            if (frame.equals(one.name()) || url.contains(frame)) matched.add(one);
        }
        return matched;
    }

    private static String withFrame(String json, String frame) {
        if (json == null || json.isBlank() || frame == null || frame.isBlank() || !json.endsWith("}")) return json == null ? "" : json;
        String escaped = frame.replace("\\", "\\\\").replace("\"", "\\\"");
        return json.substring(0, json.length() - 1) + ",\"frame\":\"" + escaped + "\"}";
    }

    static ObjectNode findRole(ProtoHost host, Map<String, String> f) throws Exception {
        String role = f.get("role");
        if (role == null || role.isBlank() || "true".equals(role)) throw new IllegalArgumentException("error: find --role needs a role\nfix: find --role=button --name=Submit");
        String name = f.get("name");
        String description = f.get("description");
        int limit = Math.max(1, Integer.parseInt(f.getOrDefault("limit", "20")));
        BrowserProtocols.Served<List<String>> served = host.protocols().serve("find-role", f.get("via"), backend -> {
            List<String> described = new ArrayList<>();
            switch (backend) {
                case BIDI -> {
                    String context = ContextCommands.bidiContext(host, f);
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("role", role);
                    if (name != null) value.put("name", name);
                    JsonNode nodes = host.bidiModules().locateNodes(context, ContextCommands.p("type", "accessibility", "value", value), limit).path("nodes");
                    for (JsonNode node : nodes) {
                        JsonNode r = host.bidiModules().callFunction(context, DESCRIBE_FN, List.of(ContextCommands.p("sharedId", node.path("sharedId").asText())), "pkb-find");
                        described.add(r.path("result").path("value").asText());
                    }
                }
                case CDP -> {
                    host.cdp().autoAttachChildren();
                    String frame = f.get("frame");
                    Set<String> seen = new LinkedHashSet<>();
                    for (CdpSession.Target t : roleTargets(host, f)) {
                        if (described.size() >= limit) break;
                        queryRole(host, t, role, name, frame, limit, described, seen);
                    }
                }
                case PLAYWRIGHT -> described.addAll(host.pw().call(c -> {
                    Page page = PwBridge.page(c, f.get("page"));
                    List<String> out = new ArrayList<>();
                    for (Frame frame : roleFrames(page, f.get("frame"))) {
                        if (out.size() >= limit) break;
                        Frame.GetByRoleOptions options = new Frame.GetByRoleOptions().setExact(true);
                        if (name != null) options.setName(name);
                        Locator found = frame.getByRole(ariaRole(role), options);
                        int count = Math.min(found.count(), limit - out.size());
                        for (int i = 0; i < count; i++) {
                            String raw = String.valueOf(found.nth(i).evaluate("el => (" + DESCRIBE_FN + ")(el)"));
                            String frameId = frame == page.mainFrame() ? "" : frame.name();
                            if (frameId.isBlank() && frame != page.mainFrame()) frameId = frame.url();
                            out.add(withFrame(raw, frameId));
                        }
                    }
                    return out;
                }));
                default -> throw new UnsupportedProtocolOperation("find-role", backend.id(), null);
            }
            return described;
        });
        ArrayNode items = ProtoJson.MAPPER.createArrayNode();
        for (String raw : served.value()) {
            JsonNode d = ProtoJson.MAPPER.readTree(raw);
            if (description != null && !d.path("description").asText().toLowerCase(Locale.ROOT).contains(description.toLowerCase(Locale.ROOT))) continue;
            ObjectNode item = items.addObject().put("role", role).put("name", name == null ? d.path("text").asText() : name);
            item.setAll((ObjectNode) d);
            item.put("source", "role");
        }
        ObjectNode out = ProtoJson.obj().put("op", "find").put("via", served.via().id()).put("role", role).put("count", items.size());
        if (name != null) out.put("name", name);
        out.set("items", items);
        return out;
    }

    // ---- aria snapshot and refs ----

    /** Aria snapshots exist only in Playwright, so auto means Playwright even when it does not serve the session. */
    static String playwright(Map<String, String> f) {
        String via = f.get("via");
        return via == null || via.isBlank() || "auto".equals(via) ? "playwright" : via;
    }

    static ObjectNode ariaSnap(ProtoHost host, Map<String, String> f) {
        BrowserProtocols.Served<String> served = host.protocols().serve("aria-snap", playwright(f), backend -> {
            if (backend != Backend.PLAYWRIGHT) throw new UnsupportedProtocolOperation("aria-snap", backend.id(), null);
            return host.pw().call(c -> {
                Page page = PwBridge.page(c, f.get("page"));
                Page.AriaSnapshotOptions options = new Page.AriaSnapshotOptions();
                if (f.containsKey("boxes")) options.setBoxes(true);
                if (f.get("depth") != null) options.setDepth(Integer.parseInt(f.get("depth")));
                if ("ai".equals(f.get("mode"))) options.setMode(AriaSnapshotMode.AI);
                String snapshot = page.ariaSnapshot(options);
                return redactAria(snapshot, secretValues(page), null);
            });
        });
        String mode = "ai".equals(f.get("mode")) ? "ai" : "default";
        ArrayNode redacted = ProtoJson.MAPPER.createArrayNode();
        // Second pass on the reply: a secret-named textbox is masked even when its value was not read.
        String snapshot = redactAria(served.value(), List.of(), redacted);
        ObjectNode out = ProtoJson.obj().put("op", "aria-snap").put("via", served.via().id()).put("mode", mode).put("boxes", f.containsKey("boxes"))
                .put("snapshot", snapshot);
        if (snapshot != null && snapshot.contains(ProtoRedact.MASK)) out.put("redactedValues", true);
        return out;
    }

    /** Password, one-time-code, card and {@code data-sensitive} field values in every frame of the page. */
    static final String SECRET_VALUES_JS = "() => { const out = []; for (const el of document.querySelectorAll('input,textarea,[contenteditable]')) {"
            + " const type = String(el.type || el.getAttribute('type') || '').toLowerCase();"
            + " const auto = String(el.getAttribute('autocomplete') || '').toLowerCase();"
            + " const secret = type === 'password' || auto.indexOf('password') >= 0 || auto.indexOf('cc-') >= 0"
            + " || auto.indexOf('one-time-code') >= 0 || el.getAttribute('data-sensitive') === 'true';"
            + " if (!secret) continue; const v = 'value' in el ? String(el.value || '') : String(el.innerText || '');"
            + " if (v) out.push(v); } return out; }";

    static List<String> secretValues(Page page) {
        List<String> values = new java.util.ArrayList<>();
        for (com.microsoft.playwright.Frame frame : page.frames()) {
            try {
                Object read = frame.evaluate(SECRET_VALUES_JS);
                if (read instanceof List<?> list) {
                    for (Object item : list) if (item != null && !String.valueOf(item).isEmpty()) values.add(String.valueOf(item));
                }
            } catch (RuntimeException detachedOrBlocked) {
                // A frame that cannot be read still gets the name-based mask below.
            }
        }
        return values;
    }

    private static final java.util.regex.Pattern ARIA_VALUE_LINE = java.util.regex.Pattern.compile(
            "^(\\s*-\\s+(?:textbox|searchbox|combobox|spinbutton)\\b(?:\\s+\"((?:[^\"\\\\]|\\\\.)*)\")?[^:\\n]*?:\\s)(.+)$");
    private static final java.util.regex.Pattern SECRET_NAME = java.util.regex.Pattern.compile(
            "(?i)pass(word|wd|code|phrase)?\\b|secret|token|one[- ]?time|otp\\b|\\bpin\\b|cvv|cvc|security code|card number|api[ _-]?key");

    /**
     * HCR-07: Playwright prints a textbox's value after its name ({@code - textbox "Password" [ref=e3]: secret1}).
     * The value is masked when it is one of the page's secret field values or when the accessible name is a
     * secret name. Other lines are unchanged.
     */
    static String redactAria(String snapshot, java.util.Collection<String> secrets, ArrayNode redacted) {
        if (snapshot == null || snapshot.isEmpty()) return snapshot;
        java.util.Set<String> known = new java.util.HashSet<>();
        if (secrets != null) for (String v : secrets) if (v != null && !v.isEmpty()) known.add(v);
        String[] lines = snapshot.split("\n", -1);
        StringBuilder out = new StringBuilder(snapshot.length());
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            java.util.regex.Matcher m = ARIA_VALUE_LINE.matcher(line);
            if (m.matches()) {
                String name = m.group(2) == null ? "" : m.group(2);
                String value = m.group(3);
                String bare = unquote(value.trim());
                if (!ProtoRedact.MASK.equals(bare) && (known.contains(bare) || known.contains(value.trim()) || SECRET_NAME.matcher(name).find())) {
                    line = m.group(1) + ProtoRedact.MASK;
                    if (redacted != null) redacted.add("aria:" + name);
                }
            } else if (!known.isEmpty()) {
                for (String v : known) {
                    if (v.length() >= 4 && line.contains(v)) line = line.replace(v, ProtoRedact.MASK);
                }
            }
            if (i > 0) out.append('\n');
            out.append(line);
        }
        return out.toString();
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("''", "'");
        }
        return value;
    }

    static ObjectNode ariaAct(ProtoHost host, Map<String, String> f) {
        String ref = f.get("aria-ref");
        if (ref == null || ref.isBlank()) throw new IllegalArgumentException("error: act --aria-ref needs a ref from aria-snap --mode=ai\nfix: aria-snap --mode=ai, then act --aria-ref=e5 --kind=click");
        String kind = f.getOrDefault("kind", "click");
        BrowserProtocols.Served<String> served = host.protocols().serve("aria-snap", playwright(f), backend -> {
            if (backend != Backend.PLAYWRIGHT) throw new UnsupportedProtocolOperation("aria-snap", backend.id(), null);
            return host.pw().call(c -> {
                Locator target = PwBridge.page(c, f.get("page")).getByRef(ref);
                switch (kind) {
                    case "click" -> target.click();
                    case "hover" -> target.hover();
                    case "type" -> target.fill(f.getOrDefault("value", f.getOrDefault("text", "")));
                    case "press" -> target.press(f.getOrDefault("key", f.getOrDefault("value", "Enter")));
                    case "select" -> target.selectOption(f.getOrDefault("value", ""));
                    default -> throw new IllegalArgumentException("error: act --aria-ref supports click, hover, type, press and select\nfix: act --aria-ref=" + ref + " --kind=click");
                }
                return backend.id();
            });
        });
        host.log("act aria-ref=" + ref + " kind=" + kind);
        return ProtoJson.obj().put("op", "act").put("via", served.via().id()).put("ref", ref).put("kind", kind).put("applied", true);
    }
}
