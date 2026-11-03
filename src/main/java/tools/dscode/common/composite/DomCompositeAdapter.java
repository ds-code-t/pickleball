package tools.dscode.common.composite;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domcomposite.Client;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The only composite call path for find, click, type, hover, scroll, keys, and desktop. */
public final class DomCompositeAdapter {
    private static final DomCompositeAdapter INSTANCE = new DomCompositeAdapter();
    private final ThreadLocal<ArrayDeque<String>> frames = ThreadLocal.withInitial(ArrayDeque::new);
    private final ThreadLocal<ArrayDeque<String>> shadows = ThreadLocal.withInitial(ArrayDeque::new);

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
        act("click", element, Map.of());
    }

    public void type(CompositeWebElement element, String text) {
        act("type", element, Map.of("text", text == null ? "" : text));
    }

    public void hover(CompositeWebElement element) {
        Map<String, Object> extra = new LinkedHashMap<>();
        String css = element.selector().css();
        extra.put("force_hover", css != null && !css.isBlank() ? css : "true");
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
        ElementPhrase desktop = phrase.source() != null && phrase.source().equals("desktop")
                ? phrase
                : phrase.source("desktop");
        CompositeSelector selector = SelectorTranslator.phrase(desktop);
        CompositeLog.info("adapter", "desktop " + verb + " " + selector.describe());
        dev.domcomposite.Client open = CompositeRuntime.ensureClient();
        if (open == null) throw new IllegalStateException("composite client is not open");
        return open.desktop(verb == null || verb.isBlank() ? "query" : verb, selector.body(List.of(), List.of()));
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
