package tools.dscode.common.composite;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domcomposite.Client;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** The only composite call path for find, click, type, hover, scroll, keys, and desktop. */
public final class DomCompositeAdapter {
    /** Desktop verbs composite 2.4.0 reports. Click and type are not in this set. */
    public static final List<String> DESKTOP_VERBS_2_4_0 = List.of(
            "list", "attach", "walk", "query", "resolve", "close"
    );

    private static final DomCompositeAdapter INSTANCE = new DomCompositeAdapter();
    private final ThreadLocal<ArrayDeque<String>> frames = ThreadLocal.withInitial(ArrayDeque::new);
    private final ThreadLocal<ArrayDeque<String>> shadows = ThreadLocal.withInitial(ArrayDeque::new);
    private final List<String> recorded = new CopyOnWriteArrayList<>();
    private final AtomicInteger commands = new AtomicInteger();
    private volatile Client verbClient;
    private volatile Set<String> desktopVerbs;
    private volatile Set<String> actionVerbs;
    private volatile Set<String> verbsForTests;
    private volatile boolean dryRun;

    private DomCompositeAdapter() {
    }

    public static DomCompositeAdapter get() {
        return INSTANCE;
    }

    /** Record commands and skip the binary. {@code actions} is the capability set under test. */
    public void dryRunForTests(Set<String> actions) {
        verbsForTests = actions == null ? Set.of() : Set.copyOf(actions);
        dryRun = true;
        recorded.clear();
        commands.set(0);
    }

    public boolean dryRun() {
        return dryRun;
    }

    public void resetDryRunForTests() {
        verbsForTests = null;
        dryRun = false;
        actionVerbs = null;
        desktopVerbs = null;
        verbClient = null;
        recorded.clear();
        commands.set(0);
    }

    /** Action commands since the last reset, in order. One entry is one round trip. */
    public List<String> commandsForTests() {
        return List.copyOf(recorded);
    }

    public void resetCommandsForTests() {
        recorded.clear();
        commands.set(0);
    }

    public boolean actionListed(String verb) {
        return VerbPlan.listed(actionVerbs(), verb);
    }

    public List<WebElement> find(By by) {
        return find(SelectorTranslator.fromBy(by));
    }

    public List<WebElement> find(ElementPhrase phrase) {
        return find(SelectorTranslator.phrase(phrase));
    }

    public List<WebElement> find(CompositeSelector selector) {
        Client open = CompositeRuntime.ensureClient();
        if (open == null) {
            throw new IllegalStateException("composite client is not open");
        }
        CompositeLog.debug("adapter", "find " + selector.describe());
        JsonNode json = open.query(selector.body(list(frames), list(shadows)));
        return hits(json, selector);
    }

    /**
     * One all-frames query with open-shadow pierce.
     * A null result means the caller keeps the Java walk (composite off, dry-run, or a binary that rejects pierce).
     */
    public JsonNode queryAllContexts(String xpath) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("xpath", xpath == null ? "" : xpath);
        body.put("mode", "composite");
        body.put("all_frames", true);
        body.put("pierce", "open");
        record("query");
        CompositeLog.info("adapter", "scan all-contexts");
        if (dryRun) {
            return null;
        }
        Client open = CompositeRuntime.ensureClient();
        if (open == null) {
            return null;
        }
        try {
            return open.query(body);
        } catch (RuntimeException ignored) {
            CompositeLog.info("adapter", "scan fallback");
            return null;
        }
    }

    public void click(CompositeWebElement element) {
        if (desktopSource(element)) {
            desktopAct("click", element);
            return;
        }
        act("click", element, Map.of());
    }

    public void type(CompositeWebElement element, String text) {
        if (desktopSource(element)) {
            desktopAct("type", element, Map.of("text", text == null ? "" : text));
            return;
        }
        act("type", element, Map.of("text", text == null ? "" : text));
    }

    public void hover(CompositeWebElement element) {
        Map<String, Object> extra = new LinkedHashMap<>();
        String id = element.attributes().get("id");
        String css = element.selector().css();
        String hoverCss = id != null && !id.isBlank() ? "#" + id : css;
        if (hoverCss != null && !hoverCss.isBlank()) extra.put("force_hover", hoverCss);
        act("query", element, extra);
    }

    public void scroll(CompositeWebElement element, int dx, int dy) {
        act("scroll", element, Map.of("dx", dx, "dy", dy));
    }

    public void submit(CompositeWebElement element) {
        if (desktopSource(element) || !VerbPlan.listed(actionVerbs(), "submit")) {
            CompositeLog.info("adapter", "verb submit fallback");
            click(element);
            return;
        }
        CompositeLog.info("adapter", "verb submit real");
        act("submit", element, Map.of());
    }

    public void doubleClick(CompositeWebElement element) {
        if (desktopSource(element) || !VerbPlan.listed(actionVerbs(), "dblclick")) {
            CompositeLog.info("adapter", "verb dblclick fallback");
            click(element);
            click(element);
            return;
        }
        CompositeLog.info("adapter", "verb dblclick real");
        act("dblclick", element, Map.of());
    }

    public void contextClick(CompositeWebElement element) {
        if (desktopSource(element) || !VerbPlan.listed(actionVerbs(), "contextclick")) {
            CompositeLog.info("adapter", "verb contextclick fallback");
            click(element);
            return;
        }
        CompositeLog.info("adapter", "verb contextclick real");
        act("contextclick", element, Map.of());
    }

    public void drag(CompositeWebElement source, CompositeWebElement target, int xOffset, int yOffset) {
        boolean real = !desktopSource(source)
                && VerbPlan.drag(actionVerbs(), target != null, xOffset, yOffset).equals(List.of("drag"));
        if (!real) {
            CompositeLog.info("adapter", "verb drag fallback");
            click(source);
            scroll(source, xOffset, yOffset == 0 && target != null ? target.rectHeight() : yOffset);
            if (target != null) click(target);
            return;
        }
        CompositeLog.info("adapter", "verb drag real");
        Client open = clientOrDry();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", source.actionBody(list(frames), list(shadows)));
        body.put("target", target.actionBody(list(frames), list(shadows)));
        record("drag");
        if (dryRun) return;
        open.drag(body);
    }

    /**
     * One composite {@code key} chord. False when the verb is absent or this hit is a desktop node.
     * The engine focuses the node, so the caller does not click first.
     */
    public boolean key(CompositeWebElement element, String chord) {
        if (chord == null || chord.isBlank() || desktopSource(element) || !VerbPlan.listed(actionVerbs(), "key")) {
            return false;
        }
        CompositeLog.info("adapter", "verb key real");
        act("key", element, Map.of("key", chord));
        return true;
    }

    /**
     * One element-scoped {@code eval}. The source is caller JavaScript.
     * {@code node_id} addresses the hit so the engine does not search again.
     */
    public JsonNode eval(CompositeWebElement element, String source) {
        Map<String, Object> body = new LinkedHashMap<>(element.actionBody(list(frames), list(shadows)));
        body.put("source", source == null ? "" : source);
        String nodeId = element.nodeId();
        if (nodeId != null && !nodeId.isBlank()) body.put("node_id", nodeId);
        record("eval");
        CompositeLog.info("adapter", "eval " + element.selector().describe());
        if (dryRun) return null;
        Client open = CompositeRuntime.ensureClient();
        if (open == null) throw new IllegalStateException("composite client is not open");
        return open.eval(body);
    }

    /**
     * One live wait for this node. The poll is 150 ms, matching {@code LeanWaits}.
     * False when the binary rejects the wait flags, so the caller keeps its own loop.
     * A timeout is a Selenium {@code TimeoutException}.
     */
    public boolean waitOn(CompositeWebElement element, String rules, long timeoutMs) {
        Map<String, Object> body = new LinkedHashMap<>(element.actionBody(list(frames), list(shadows)));
        body.put("wait_for", rules == null ? "visible" : rules);
        body.put("timeout_ms", Math.max(0, timeoutMs));
        body.put("poll_ms", 150);
        body.put("stability_ms", 0);
        record("query");
        CompositeLog.info("adapter", "wait " + rules + " poll=150");
        if (dryRun) return true;
        Client open = CompositeRuntime.ensureClient();
        if (open == null) return false;
        try {
            open.query(body);
            return true;
        } catch (RuntimeException ex) {
            String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("unexpected") || message.contains("unrecognized") || message.contains("poll-ms") || message.contains("poll_ms")) {
                CompositeLog.info("adapter", "wait fallback");
                return false;
            }
            if (message.contains("timeout") || message.contains("timed out")) {
                throw new org.openqa.selenium.TimeoutException(ex.getMessage());
            }
            throw ex;
        }
    }

    /**
     * True when {@code eval} says the node is still connected. False when the verb is absent or the call fails.
     */
    public boolean live(CompositeWebElement element) {
        if (!actionListed("eval")) return false;
        CompositeLog.info("adapter", "live " + element.nodeId());
        try {
            JsonNode result = eval(element, "function(){ return !!(this && this.isConnected); }");
            if (dryRun) return true;
            JsonNode value = result == null ? null : result.get("value");
            return value != null && value.asBoolean(false);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public void keys(String text) {
        Client open = CompositeRuntime.ensureClient();
        if (open == null) throw new IllegalStateException("composite client is not open");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text == null ? "" : text);
        body.put("css", "body");
        CompositeLog.info("adapter", "keys");
        open.type(body);
    }

    public JsonNode desktop(String verb, ElementPhrase phrase) {
        String name = verb == null || verb.isBlank() ? "query" : verb.trim();
        if (desktopMutation(name)) requireDesktopVerb(name);
        ElementPhrase desktop = phrase.source() != null && phrase.source().equals("desktop")
                ? phrase
                : phrase.source("desktop");
        CompositeSelector selector = SelectorTranslator.phrase(desktop);
        CompositeLog.info("adapter", "desktop " + name + " " + selector.describe());
        dev.domcomposite.Client open = CompositeRuntime.ensureClient();
        if (open == null) throw new IllegalStateException("composite client is not open");
        return open.desktop(name, selector.body(List.of(), List.of()));
    }

    private void desktopAct(String verb, CompositeWebElement element) {
        desktopAct(verb, element, Map.of());
    }

    private void desktopAct(String verb, CompositeWebElement element, Map<String, Object> extra) {
        requireDesktopVerb(verb);
        Client open = CompositeRuntime.ensureClient();
        if (open == null) throw new IllegalStateException("composite client is not open");
        Map<String, Object> body = new LinkedHashMap<>(element.actionBody(list(frames), list(shadows)));
        if (extra != null) body.putAll(extra);
        CompositeLog.info("adapter", "desktop " + verb + " " + element.selector().describe());
        open.desktop(verb, body);
    }

    private void requireDesktopVerb(String verb) {
        Set<String> reported = desktopVerbs();
        if (reported.contains(verb.toLowerCase(Locale.ROOT))) return;
        String shown = reported.isEmpty() ? "(none)" : String.join(", ", reported);
        String error = "dom-composite " + CompositeBinaryLocator.VERSION
                + " has no desktop " + verb + " verb (capabilities.desktop is " + shown + ")";
        String fix = "Upstream must add desktop verb `" + verb
                + "`. Expected verbs are `desktop click` and `desktop type`. "
                + "2.4.0 desktop verbs are " + String.join(", ", DESKTOP_VERBS_2_4_0) + ". "
                + "Drop in dev.domcomposite:composite-native:" + CompositeBinaryLocator.VERSION
                + " classifiers " + String.join(", ", CompositeBinaryLocator.CLASSIFIERS)
                + ". The binary name is composite, or composite.exe on windows-x64. "
                + "Classpath entry: dev/domcomposite/natives/<classifier>/composite[.exe]. "
                + "Maven jar: ~/.m2/repository/dev/domcomposite/composite-native/"
                + CompositeBinaryLocator.VERSION + "/composite-native-" + CompositeBinaryLocator.VERSION
                + "-<classifier>.jar. When capabilities.desktop lists `" + verb
                + "`, this adapter forwards it. No Pickleball change is required.";
        CompositeLog.always(error, fix);
        throw new IllegalStateException("error: " + error);
    }

    private Set<String> desktopVerbs() {
        Client open = CompositeRuntime.ensureClient();
        if (open == null) return Set.copyOf(DESKTOP_VERBS_2_4_0);
        pinClient(open);
        Set<String> cached = desktopVerbs;
        if (cached != null) return cached;
        Set<String> loaded = loadNamedVerbs(open, "desktop", DESKTOP_VERBS_2_4_0);
        desktopVerbs = loaded;
        return loaded;
    }

    private Set<String> actionVerbs() {
        if (verbsForTests != null) return verbsForTests;
        Client open = CompositeRuntime.ensureClient();
        if (open == null) return Set.copyOf(VerbPlan.ACTIONS_2_4_0);
        pinClient(open);
        Set<String> cached = actionVerbs;
        if (cached != null) return cached;
        Set<String> loaded = loadNamedVerbs(open, "actions", VerbPlan.ACTIONS_2_4_0);
        actionVerbs = loaded;
        return loaded;
    }

    private void pinClient(Client open) {
        if (open == verbClient) return;
        verbClient = open;
        desktopVerbs = null;
        actionVerbs = null;
    }

    private static Set<String> loadNamedVerbs(Client open, String field, List<String> fallback) {
        try {
            JsonNode caps = open.capabilities();
            JsonNode list = caps == null ? null : caps.get(field);
            if (list == null || !list.isArray()) return Set.copyOf(fallback);
            Set<String> verbs = new LinkedHashSet<>();
            for (JsonNode item : list) {
                String name = item.asText("").trim().toLowerCase(Locale.ROOT);
                if (!name.isEmpty()) verbs.add(name);
            }
            return Set.copyOf(verbs);
        } catch (RuntimeException ignored) {
            return Set.copyOf(fallback);
        }
    }

    private static boolean desktopMutation(String verb) {
        return "click".equalsIgnoreCase(verb) || "type".equalsIgnoreCase(verb);
    }

    private static boolean desktopSource(CompositeWebElement element) {
        if (element == null || element.selector() == null) return false;
        return "desktop".equalsIgnoreCase(element.selector().source());
    }

    public void pushFrame(String id) {
        if (id != null && !id.isBlank()) frames.get().addLast(id);
        CompositeLog.debug("session", "frame " + id);
    }

    public void pushShadow(String id) {
        if (id != null && !id.isBlank()) shadows.get().addLast(id);
        CompositeLog.debug("session", "shadow " + id);
    }

    public List<WebElement> findInFrame(String frameId, By by) {
        pushFrame(frameId);
        try {
            return find(by);
        } finally {
            if (!frames.get().isEmpty()) frames.get().removeLast();
        }
    }

    private void act(String command, CompositeWebElement element, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>(element.actionBody(list(frames), list(shadows)));
        body.putAll(extra);
        record(command);
        CompositeLog.info("adapter", command + " " + element.selector().describe());
        if (dryRun) return;
        Client open = CompositeRuntime.ensureClient();
        if (open == null) throw new IllegalStateException("composite client is not open");
        switch (command) {
            case "click" -> open.click(body);
            case "type" -> open.type(body);
            case "scroll" -> open.scroll(body);
            case "submit" -> open.submit(body);
            case "key" -> open.key(body);
            case "dblclick" -> open.dblclick(body);
            case "contextclick" -> open.contextclick(body);
            case "query" -> open.query(body);
            default -> open.command(command, body);
        }
    }

    private Client clientOrDry() {
        if (dryRun) return null;
        Client open = CompositeRuntime.ensureClient();
        if (open == null) throw new IllegalStateException("composite client is not open");
        return open;
    }

    private void record(String command) {
        commands.incrementAndGet();
        recorded.add(command);
    }

    private List<WebElement> hits(JsonNode json, CompositeSelector selector) {
        JsonNode list = array(json, "composite");
        if (list == null) list = array(json, "nodes");
        if (list == null) list = array(json, "matches");
        List<WebElement> hits = new ArrayList<>();
        if (list != null) {
            int index = 0;
            for (JsonNode node : list) {
                hits.add(new CompositeWebElement(selector, node, index++));
            }
        }
        Integer ordinal = selector.ordinal();
        if (ordinal == null || hits.isEmpty()) return hits;
        int at = ordinal < 0 ? hits.size() - 1 : ordinal;
        if (at < 0 || at >= hits.size()) return List.of();
        return List.of(hits.get(at));
    }

    private static JsonNode array(JsonNode json, String name) {
        if (json == null) return null;
        JsonNode node = json.get(name);
        return node != null && node.isArray() && !node.isEmpty() ? node : null;
    }

    private static List<String> list(ThreadLocal<ArrayDeque<String>> local) {
        return List.copyOf(local.get());
    }
}
