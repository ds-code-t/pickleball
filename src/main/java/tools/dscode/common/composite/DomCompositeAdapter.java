package tools.dscode.common.composite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domcomposite.Client;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import tools.dscode.common.domoperations.PositionSelection;
import tools.dscode.common.seleniumextensions.ElementSnapshot;

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
    private volatile Set<String> queryFlags;
    private volatile Set<String> engines;
    private volatile boolean multiEvalPinned;
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
        queryFlags = null;
        engines = null;
        multiEvalPinned = false;
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

    /** True when {@code capabilities.query_flags} lists {@code within}, {@code nesting}, or a later flag. */
    public boolean queryFlag(String flag) {
        if (flag == null || flag.isBlank()) return false;
        return queryFlags().contains(flag.toLowerCase(Locale.ROOT));
    }

    /** True when {@code capabilities.eval_nodes} is present. One eval can take several node ids. */
    public boolean multiEval() {
        if (verbsForTests != null) return multiEvalPinned;
        if (!CompositeRuntime.primary()) return false;
        queryFlags();
        return multiEvalPinned;
    }

    /**
     * One query under {@code within}, with {@code nesting} after {@code filter}.
     * Null pieces are omitted. The caller already checked the capability.
     */
    public List<WebElement> findScoped(By by, String within, String nesting, String filter) {
        return findScoped(by, within, nesting, filter, null, false);
    }

    /**
     * Same query, with {@code --nth} / {@code --every} after nesting.
     * The caller already checked the capability and that no regex remains.
     */
    public List<WebElement> findScoped(
            By by,
            String within,
            String nesting,
            String filter,
            tools.dscode.common.domoperations.PositionSelection.EnginePosition position
    ) {
        return findScoped(by, within, nesting, filter, position, false);
    }

    /**
     * One query that returns the positioned count and no nodes.
     * A binary without {@code count-only} counts the nodes in Java.
     */
    public int countQuery(
            By by,
            String within,
            String nesting,
            String filter,
            tools.dscode.common.domoperations.PositionSelection.EnginePosition position
    ) {
        if (!queryFlag("count-only") || !positionReady(position)) {
            CompositeLog.info("adapter", "position java");
            return slice(findScoped(by, within, nesting, filter), position).size();
        }
        JsonNode json = queryScoped(by, within, nesting, filter, position, true);
        int count = json == null ? 0 : json.path("count").asInt(0);
        CompositeLog.info("adapter", "count " + count);
        return count;
    }

    private List<WebElement> findScoped(
            By by,
            String within,
            String nesting,
            String filter,
            tools.dscode.common.domoperations.PositionSelection.EnginePosition position,
            boolean countOnly
    ) {
        org.openqa.selenium.WebDriver owner = dispatchOwner();
        String tab = tools.dscode.common.agentcontrol.AgentControl.queryTab(owner);
        JsonNode json = queryScoped(by, within, nesting, filter, position, countOnly);
        if (countOnly) return List.of();
        return hits(json, SelectorTranslator.fromBy(by), owner, tab);
    }

    private JsonNode queryScoped(
            By by,
            String within,
            String nesting,
            String filter,
            tools.dscode.common.domoperations.PositionSelection.EnginePosition position,
            boolean countOnly
    ) {
        CompositeSelector selector = SelectorTranslator.fromBy(by);
        Map<String, Object> body = new LinkedHashMap<>(selector.body(list(frames), list(shadows)));
        if (within != null && !within.isBlank()) body.put("within", within);
        if (nesting != null && !nesting.isBlank()) body.put("nesting", nesting);
        if (filter != null && !filter.isBlank()) body.put("filter", filter);
        if (position != null) {
            body.put("nth", position.nth());
            if (position.every() != null) body.put("every", position.every());
        }
        if (countOnly) body.put("count_only", true);
        record("query");
        if (within != null && !within.isBlank()) CompositeLog.info("adapter", "within " + within);
        if (nesting != null && !nesting.isBlank()) CompositeLog.info("adapter", "nesting " + nesting);
        if (position != null) CompositeLog.info("adapter", position.logLine());
        attachGeometry(body);
        if (dryRun) return null;
        Client open = CompositeRuntime.ensureClient();
        if (open == null) throw new IllegalStateException("composite client is not open");
        CompositeLog.debug("adapter", "find " + selector.describe());
        return open.query(body);
    }

    private static boolean positionReady(
            tools.dscode.common.domoperations.PositionSelection.EnginePosition position
    ) {
        DomCompositeAdapter adapter = get();
        if (position == null) return true;
        if (!adapter.queryFlag("nth")) return false;
        return position.every() == null || adapter.queryFlag("every");
    }

    private static <T> List<T> slice(
            List<T> items,
            tools.dscode.common.domoperations.PositionSelection.EnginePosition position
    ) {
        if (position == null) return items == null ? List.of() : items;
        if (position.every() == null) return PositionSelection.applyOrdinal(items, position.nth());
        return PositionSelection.every(items, Math.max(0, position.nth()), position.every());
    }

    /**
     * One eval for every node. A binary without {@code eval_nodes} evaluates the first node only.
     */
    public JsonNode evalAll(List<CompositeWebElement> elements, String source) {
        List<CompositeWebElement> present = new ArrayList<>();
        if (elements != null) {
            for (CompositeWebElement element : elements) {
                if (element != null && element.nodeId() != null && !element.nodeId().isBlank()) present.add(element);
            }
        }
        if (present.size() <= 1 || !multiEval()) {
            CompositeLog.info("adapter", "eval fallback");
            if (present.isEmpty()) return null;
            return eval(present.getFirst(), source);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", source == null ? "" : source);
        StringBuilder ids = new StringBuilder();
        for (CompositeWebElement element : present) {
            if (!ids.isEmpty()) ids.append(',');
            ids.append(element.nodeId());
        }
        body.put("node_ids", ids.toString());
        record("eval");
        CompositeLog.info("adapter", "eval nodes=" + present.size());
        if (dryRun) return null;
        Client open = CompositeRuntime.ensureClient();
        if (open == null) throw new IllegalStateException("composite client is not open");
        return open.eval(body);
    }

    /**
     * Forward one engine query object. {@code selector} is the pipeline or
     * expression. Exposure and limit are sent only when capabilities list them.
     */
    public List<WebElement> findEngine(EngineQuery query) {
        org.openqa.selenium.WebDriver owner = dispatchOwner();
        String tab = tools.dscode.common.agentcontrol.AgentControl.queryTab(owner);
        JsonNode json = queryEngine(query);
        if (json == null) return List.of();
        logExplain(json);
        List<WebElement> found = hits(json, new CompositeSelector(null, null, query.text(), "dom", null, query.text()), owner, tab);
        for (WebElement element : found) {
            if (element instanceof CompositeWebElement hit && hit.visualOnly()) {
                CompositeLog.info("query", "visual-only " + hit.handle());
            }
        }
        return found;
    }

    public JsonNode queryEngine(EngineQuery query) {
        if (query == null || query.text().isBlank()) {
            throw new IllegalArgumentException("engine query must not be blank");
        }
        requireVisual(query.text());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("selector", query.text());
        body.put("mode", "composite");
        if (query.exposure() != null) {
            if (queryFlag("exposure")) {
                body.put("exposure", query.exposure());
                CompositeLog.info("query", "exposure " + query.exposure());
            } else {
                CompositeLog.info("query", "exposure fallback");
            }
        }
        if (query.limit() != null) {
            if (queryFlag("limit")) {
                body.put("limit", query.limit());
                CompositeLog.info("query", "limit " + query.limit());
            } else {
                CompositeLog.info("query", "limit fallback");
            }
        }
        if (query.explain()) {
            body.put("explain", true);
            CompositeLog.info("query", "explain");
        }
        if (query.promoteVisual()) {
            body.put("promote_visual", true);
            CompositeLog.info("query", "promote visual");
        }
        attachGeometry(body);
        CompositeLog.info("query", "engine " + query.text());
        CompositeLog.trace("query", "forward selector=" + query.text());
        record("query");
        if (dryRun) return null;
        Client open = CompositeRuntime.ensureClient();
        if (open == null) throw new IllegalStateException("composite client is not open");
        return open.query(body);
    }

    /** One click or type. Human exposure is whatever the query already carries. */
    public ElementView act(EngineQuery query, String verb, String typed) {
        EngineQuery used = query.interact() ? query : query.interact(true);
        requireVisual(used.text());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("selector", used.text());
        body.put("mode", "composite");
        if (used.exposure() != null && queryFlag("exposure")) {
            body.put("exposure", used.exposure());
        }
        if (used.explain()) body.put("explain", true);
        String action = verb == null ? "click" : verb.trim().toLowerCase(java.util.Locale.ROOT);
        CompositeLog.info("query", "act " + action + " " + used.text());
        if ("lower".equals(used.exposure())) {
            CompositeLog.info("exposure", "act lower " + used.text());
        }
        org.openqa.selenium.WebDriver owner = dispatchOwner();
        String tab = tools.dscode.common.agentcontrol.AgentControl.queryTab(owner);
        if (dryRun) return null;
        Client open = CompositeRuntime.ensureClient();
        if (open == null) throw new IllegalStateException("composite client is not open");
        JsonNode json = "type".equals(action)
                ? open.type(withText(body, typed))
                : open.click(body);
        JsonNode node = json == null ? null : json.get("node");
        if (node == null || node.isNull()) {
            throw new org.openqa.selenium.NoSuchElementException("no match for " + used.text());
        }
        node = actionLabel(json, node);
        CompositeWebElement element = new CompositeWebElement(
                new CompositeSelector(null, null, used.text(), "dom", null, used.text()),
                node,
                0,
                owner,
                tab
        );
        return ElementReads.fromComposite(element);
    }

    private static Map<String, Object> withText(Map<String, Object> body, String typed) {
        body.put("text", typed == null ? "" : typed);
        return body;
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
        Integer ordinal = selector.ordinal();
        boolean engineNth = ordinal != null && queryFlag("nth");
        CompositeSelector used = engineNth
                ? new CompositeSelector(
                        selector.xpath(), selector.css(), selector.pipeline(), selector.source(), null, selector.phrase())
                : selector;
        Map<String, Object> body = new LinkedHashMap<>(used.body(list(frames), list(shadows)));
        if (engineNth) {
            body.put("nth", ordinal);
            CompositeLog.info("adapter", "nth " + ordinal);
        } else if (ordinal != null && CompositeRuntime.primary()) {
            CompositeLog.info("adapter", "position java");
        }
        attachGeometry(body);
        CompositeLog.debug("adapter", "find " + used.describe());
        org.openqa.selenium.WebDriver owner = dispatchOwner();
        String tab = tools.dscode.common.agentcontrol.AgentControl.queryTab(owner);
        JsonNode json = open.query(body);
        return hits(json, used, owner, tab);
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
        attachGeometry(body);
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

    /**
     * The scroll chain already on the hit. Empty when geometry did not carry one.
     * Does not call {@code resolve --scroll} and does not start a second query.
     */
    public List<ElementSnapshot.ScrollLink> scrollChain(CompositeWebElement element) {
        if (element == null || element.geometry() == null || element.geometry().scrollChain().isEmpty()) {
            CompositeLog.info("geometry", "scroll-chain fallback");
            return List.of();
        }
        CompositeLog.info("geometry", "scroll-chain " + element.geometry().scrollChain().size());
        return element.geometry().scrollChain();
    }

    /**
     * One query for the topmost node at a document CSS point. Null when
     * {@code point} is not listed. Does not invent a node.
     */
    public JsonNode hitAt(double x, double y) {
        if (!queryFlag("point")) {
            CompositeLog.info("geometry", "point fallback");
            return null;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("point", x + "," + y);
        body.put("mode", "composite");
        attachGeometry(body);
        record("query");
        CompositeLog.info("geometry", "point");
        if (dryRun) return null;
        Client open = CompositeRuntime.ensureClient();
        if (open == null) return null;
        return open.query(body);
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
        String error = "DOM engine has no desktop " + verb + " verb (capabilities.desktop is " + shown + ")";
        String fix = "The in-repo Java engine supports desktop click and desktop type. "
                + "Unset pkb_composite_engine=rust and any explicit binary to use it. "
                + "For the Rust override, expected verbs are `desktop click` and `desktop type`. "
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

    /**
     * Visual relatives use the engine geometry view. An engine that lacks the
     * flag or the engine name does not get a box invented here.
     */
    private void requireVisual(String selector) {
        String relative = EngineQuery.visualRelative(selector);
        if (relative == null) return;
        String canonical = EngineQuery.canonicalRelative(relative);
        boolean ready = queryFlag("geometry") && (engineListed(canonical) || engineListed(relative));
        if (!ready) {
            CompositeLog.info("geometry", "rel fallback " + relative);
            throw new IllegalStateException(
                    "visual relative " + relative + " needs the geometry view; no boxes were invented");
        }
        CompositeLog.info("geometry", "rel forward " + relative);
    }

    private boolean engineListed(String name) {
        if (name == null || name.isBlank()) return false;
        return engines().contains(name.toLowerCase(Locale.ROOT));
    }

    private Set<String> engines() {
        if (verbsForTests != null) return engines == null ? Set.of() : engines;
        if (!CompositeRuntime.primary()) return Set.of();
        Client open = CompositeRuntime.ensureClient();
        if (open == null) return Set.of();
        pinClient(open);
        Set<String> cached = engines;
        if (cached != null) return cached;
        Set<String> loaded = loadNamedVerbs(open, "engines", List.of());
        engines = loaded;
        return loaded;
    }

    /**
     * Ask for the five-space geometry view when {@code capabilities.query_flags}
     * lists {@code geometry}. A binary that does not list it keeps the AABB.
     */
    private void attachGeometry(Map<String, Object> body) {
        if (queryFlag("geometry")) {
            body.put("geometry", true);
            CompositeLog.trace("geometry", "request");
            CompositeLog.info("geometry", "real");
            CompositeLog.info("adapter", "geometry real");
            return;
        }
        if (CompositeRuntime.primary()) {
            CompositeLog.info("geometry", "fallback");
            CompositeLog.info("adapter", "geometry fallback");
        }
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
        queryFlags = null;
        engines = null;
        multiEvalPinned = false;
    }

    private Set<String> queryFlags() {
        if (verbsForTests != null) return queryFlags == null ? Set.of() : queryFlags;
        if (!CompositeRuntime.primary()) return Set.of();
        // Capabilities do not need a browser. ensureClient() would launch Chrome.
        Client open = CompositeRuntime.client();
        if (open == null) return Set.of();
        pinClient(open);
        Set<String> cached = queryFlags;
        if (cached != null) return cached;
        Set<String> loaded = loadNamedVerbs(open, "query_flags", List.of());
        queryFlags = loaded;
        loadMultiEval(open);
        return loaded;
    }

    private void loadMultiEval(Client open) {
        try {
            JsonNode caps = open.capabilities();
            JsonNode node = caps == null ? null : caps.get("eval_nodes");
            multiEvalPinned = node != null && !node.isNull() && !node.asText("").isBlank();
        } catch (RuntimeException ignored) {
            multiEvalPinned = false;
        }
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

    List<WebElement> hits(JsonNode json, CompositeSelector selector) {
        org.openqa.selenium.WebDriver owner = dispatchOwner();
        return hits(json, selector, owner, dispatchTab(owner));
    }

    private static org.openqa.selenium.WebDriver dispatchOwner() {
        return tools.dscode.common.agentcontrol.AgentControl.queryOwner();
    }

    private static String dispatchTab(org.openqa.selenium.WebDriver owner) {
        return tools.dscode.common.agentcontrol.AgentControl.queryTab(owner);
    }

    List<WebElement> hits(JsonNode json, CompositeSelector selector, org.openqa.selenium.WebDriver owner, String tab) {
        JsonNode composite = array(json, "composite");
        JsonNode nodes = array(json, "nodes");
        JsonNode list = composite != null ? composite : nodes;
        if (list == null) list = array(json, "matches");
        List<WebElement> hits = new ArrayList<>();
        if (list != null) {
            int index = 0;
            for (JsonNode node : list) {
                JsonNode used = composite != null && nodes != null
                        ? overlayFindFields(node, matchNode(node, nodes, index))
                        : node;
                hits.add(new CompositeWebElement(selector, used, index++, owner, tab));
            }
        }
        return PositionSelection.applyOrdinal(hits, selector.ordinal());
    }

    /**
     * The engine writes rank, score, and human_limits on legacy {@code nodes}.
     * The composite row is the same hit and often omits them. Copy the missing
     * fields. Do not invent a value the node row does not have.
     */
    private static JsonNode overlayFindFields(JsonNode composite, JsonNode node) {
        if (!(composite instanceof ObjectNode) || node == null || !node.isObject()) return composite;
        ObjectNode copy = ((ObjectNode) composite).deepCopy();
        copyMissing(copy, node, "handle");
        copyMissing(copy, node, "rank");
        copyMissing(copy, node, "score");
        copyMissing(copy, node, "human_limits");
        copyMissing(copy, node, "geometry");
        return copy;
    }

    /** Click and type put the prefixed sentence on the action, not only on the node. */
    private static JsonNode actionLabel(JsonNode json, JsonNode node) {
        JsonNode labeled = json.get("human_limits");
        if (!(node instanceof ObjectNode) || labeled == null || !labeled.isObject()) return node;
        ObjectNode copy = ((ObjectNode) node).deepCopy();
        copy.set("human_limits", labeled);
        return copy;
    }

    private static void copyMissing(ObjectNode onto, JsonNode from, String field) {
        JsonNode present = onto.get(field);
        if (present != null && !present.isNull()) return;
        JsonNode value = from.get(field);
        if (value != null && !value.isNull()) onto.set(field, value);
    }

    private static JsonNode matchNode(JsonNode composite, JsonNode nodes, int index) {
        String id = composite == null ? "" : composite.path("id").asText("");
        if (!id.isBlank()) {
            for (JsonNode node : nodes) {
                String handle = node.path("handle").asText("");
                String built = node.path("frame_id").asText("") + ":" + node.path("backend_node_id").asText("");
                if (id.equals(handle) || id.equals(built)) return node;
            }
        }
        return index < nodes.size() ? nodes.get(index) : null;
    }

    private static void logExplain(JsonNode json) {
        JsonNode explain = json.get("explain");
        if (explain == null || !explain.isArray()) return;
        for (JsonNode trace : explain) {
            String why = trace.path("why").asText("");
            if (!why.isBlank()) CompositeLog.info("query", "why " + why);
        }
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
