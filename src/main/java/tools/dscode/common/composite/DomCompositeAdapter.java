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

/** The only composite call path for find, click, type, hover, scroll, keys, and desktop. */
public final class DomCompositeAdapter {
    /** Desktop verbs composite 2.4.0 reports. Click and type are not in this set. */
    public static final List<String> DESKTOP_VERBS_2_4_0 = List.of(
            "list", "attach", "walk", "query", "resolve", "close"
    );

    private static final DomCompositeAdapter INSTANCE = new DomCompositeAdapter();
    private final ThreadLocal<ArrayDeque<String>> frames = ThreadLocal.withInitial(ArrayDeque::new);
    private final ThreadLocal<ArrayDeque<String>> shadows = ThreadLocal.withInitial(ArrayDeque::new);
    private volatile Client verbClient;
    private volatile Set<String> desktopVerbs;

    private DomCompositeAdapter() {
    }

    public static DomCompositeAdapter get() {
        return INSTANCE;
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

    public void drag(CompositeWebElement source, CompositeWebElement target, int xOffset, int yOffset) {
        CompositeLog.info("adapter", "drag " + source.selector().describe());
        click(source);
        scroll(source, xOffset, yOffset == 0 && target != null ? target.rectHeight() : yOffset);
        if (target != null) click(target);
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
        Set<String> cached = desktopVerbs;
        if (open == verbClient && cached != null) return cached;
        Set<String> loaded = loadDesktopVerbs(open);
        verbClient = open;
        desktopVerbs = loaded;
        return loaded;
    }

    private static Set<String> loadDesktopVerbs(Client open) {
        try {
            JsonNode caps = open.capabilities();
            JsonNode list = caps == null ? null : caps.get("desktop");
            if (list == null || !list.isArray()) return Set.copyOf(DESKTOP_VERBS_2_4_0);
            Set<String> verbs = new LinkedHashSet<>();
            for (JsonNode item : list) {
                String name = item.asText("").trim().toLowerCase(Locale.ROOT);
                if (!name.isEmpty()) verbs.add(name);
            }
            return Set.copyOf(verbs);
        } catch (RuntimeException ignored) {
            return Set.copyOf(DESKTOP_VERBS_2_4_0);
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
        Client open = CompositeRuntime.ensureClient();
        if (open == null) throw new IllegalStateException("composite client is not open");
        Map<String, Object> body = new LinkedHashMap<>(element.actionBody(list(frames), list(shadows)));
        body.putAll(extra);
        CompositeLog.info("adapter", command + " " + element.selector().describe());
        switch (command) {
            case "click" -> open.click(body);
            case "type" -> open.type(body);
            case "scroll" -> open.scroll(body);
            case "query" -> open.query(body);
            default -> open.command(command, body);
        }
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
