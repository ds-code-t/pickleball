package tools.dscode.common.composite;

import com.fasterxml.jackson.databind.JsonNode;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.Point;
import org.openqa.selenium.Rectangle;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;

import tools.dscode.common.seleniumextensions.ElementSnapshot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** One composite hit. Element methods call {@link DomCompositeAdapter} and do not call WebDriver. */
public final class CompositeWebElement implements WebElement {
    private final CompositeSelector selector;
    private final String nodeId;
    private final String tag;
    private final String role;
    private final String text;
    private final String name;
    private final Map<String, String> attributes;
    private final boolean displayed;
    private final boolean enabled;
    private final boolean selected;
    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private final ElementSnapshot.Geometry geometry;
    private final String handle;
    private final Integer rank;
    private final Double score;
    private final java.util.List<String> humanCodes;
    private final String humanSentence;
    private final boolean visualOnly;

    /** One node from an all-contexts query. The id is the frame and backend id. */
    public static WebElement fromQuery(String xpath, JsonNode node, int index) {
        return new CompositeWebElement(new CompositeSelector(xpath, null, null, "dom", null, null), node, index);
    }

    CompositeWebElement(CompositeSelector selector, JsonNode node, int index) {
        this.selector = selector;
        JsonNode value = node == null ? null : node;
        this.nodeId = first(
                text(value, "id", ""),
                text(value, "element_id", ""),
                joinId(text(value, "frame_id", ""), text(value, "backend_node_id", "")),
                "element-" + index
        );
        String nodeName = text(value, "node_name", text(value, "tag", ""));
        this.tag = (nodeName.isBlank() ? text(value, "role", "element") : nodeName).toLowerCase(Locale.ROOT);
        this.role = text(value, "role", this.tag);
        this.text = text(value, "text", text(value, "name", ""));
        this.name = text(value, "name", this.text);
        this.attributes = attributes(value);
        this.displayed = !state(value, "hidden");
        this.enabled = !state(value, "disabled");
        this.selected = state(value, "checked") || state(value, "selected");
        JsonNode bounds = value == null ? null : value.get("bounds");
        this.x = bounds == null ? 0 : bounds.path("x").asInt(0);
        this.y = bounds == null ? 0 : bounds.path("y").asInt(0);
        this.width = bounds == null ? 0 : bounds.path("width").asInt(0);
        this.height = bounds == null ? 0 : bounds.path("height").asInt(0);
        this.geometry = ElementSnapshot.Geometry.parse(value == null ? null : value.get("geometry"));
        this.handle = first(text(value, "handle", ""), this.nodeId);
        this.rank = value != null && value.has("rank") && value.get("rank").canConvertToInt()
                ? value.get("rank").asInt()
                : null;
        this.score = value != null && value.has("score") && value.get("score").isNumber()
                ? value.get("score").asDouble()
                : null;
        JsonNode limits = value == null ? null : value.get("human_limits");
        java.util.ArrayList<String> codes = new java.util.ArrayList<>();
        if (limits != null && limits.path("codes").isArray()) {
            for (JsonNode code : limits.path("codes")) {
                String name = code.asText("");
                if (!name.isBlank()) codes.add(name);
            }
        }
        this.humanCodes = java.util.List.copyOf(codes);
        this.humanSentence = limits == null ? "" : limits.path("sentence").asText("");
        String source = text(value, "source", "");
        this.visualOnly = "visual".equalsIgnoreCase(source)
                || "desktop".equalsIgnoreCase(source)
                || codes.contains("source_only");
        if (this.geometry != null
                && this.geometry.visualViewportRelative() != null
                && this.handle != null
                && !this.handle.isBlank()) {
            tools.dscode.common.agentcontrol.AgentControl.rememberGeometry(
                    this.handle,
                    this.geometry.visualViewportRelative().x(),
                    this.geometry.visualViewportRelative().y()
            );
        }
        if (this.geometry != null && this.geometry.scrollport() != null) {
            boolean content = this.geometry.scrollport().content() != null;
            CompositeLog.info("geometry", content ? "scrollport client+content" : "scrollport client");
        }
        if (this.geometry != null && this.geometry.expanded() != null) {
            CompositeLog.info(
                    "geometry",
                    (this.geometry.expanded() ? "expanded" : "collapsed")
                            + (this.geometry.revealed() != null ? " revealed" : "")
            );
        }
        if (this.geometry != null) {
            long hits = this.geometry.corners().stream().filter(corner -> Boolean.TRUE.equals(corner.hit())).count();
            long misses = this.geometry.corners().stream().filter(corner -> Boolean.FALSE.equals(corner.hit())).count();
            if (hits + misses > 0) {
                CompositeLog.info("geometry", "corners " + hits + "hit/" + misses + "miss");
            }
        }
    }

    public String nodeTag() {
        return getTagName();
    }

    public String nodeText() {
        return text == null ? "" : text;
    }

    public CompositeSelector selector() {
        return selector;
    }

    public String nodeId() {
        return nodeId;
    }

    public Map<String, String> attributes() {
        return attributes;
    }

    /** Five-space view from the query payload. Null when the hit had no geometry object. */
    public ElementSnapshot.Geometry geometry() {
        return geometry;
    }

    public String handle() {
        return handle == null ? "" : handle;
    }

    public Integer rank() {
        return rank;
    }

    public Double score() {
        return score;
    }

    public java.util.List<String> humanCodes() {
        return humanCodes == null ? java.util.List.of() : humanCodes;
    }

    public String humanSentence() {
        return humanSentence == null ? "" : humanSentence;
    }

    public boolean visualOnly() {
        return visualOnly;
    }

    int rectHeight() {
        return height;
    }

    Map<String, Object> actionBody(List<String> frames, List<String> shadows) {
        String id = attributes.get("id");
        CompositeSelector action;
        if (id != null && !id.isBlank() && usableToken(id)) {
            action = new CompositeSelector(null, "#" + id, null, selector.source(), null, selector.phrase());
        } else {
            String local = localXPath();
            action = local != null
                    ? new CompositeSelector(local, null, null, selector.source(), null, selector.phrase())
                    : selector;
        }
        return action.body(frames, shadows);
    }

    /** A locator for this node alone. The lookup selector can match more than one node, and click rejects that. */
    private String localXPath() {
        if (tag == null || !tag.matches("[A-Za-z][A-Za-z0-9]*")) return null;
        String name = attributes.get("name");
        String value = attributes.get("value");
        if (usableToken(name) && usableToken(value)) {
            return "//*[@name='" + name + "' and @value='" + value + "']";
        }
        if (usableToken(name) && ("input".equals(tag) || "select".equals(tag) || "textarea".equals(tag) || "button".equals(tag) || "radio".equals(tag) || "checkbox".equals(tag) || "textbox".equals(tag))) {
            return "//*[@name='" + name + "']";
        }
        String shown = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        if (!shown.isEmpty() && shown.length() <= 80 && usableToken(shown)) {
            return "//*[normalize-space(.)='" + shown + "']";
        }
        return null;
    }

    @Override
    public void click() {
        DomCompositeAdapter.get().click(this);
    }

    @Override
    public void submit() {
        DomCompositeAdapter.get().submit(this);
    }

    @Override
    public void sendKeys(CharSequence... keysToSend) {
        StringBuilder text = new StringBuilder();
        if (keysToSend != null) {
            for (CharSequence key : keysToSend) {
                if (key != null) text.append(key);
            }
        }
        DomCompositeAdapter.get().type(this, text.toString());
    }

    @Override
    public void clear() {
        DomCompositeAdapter.get().type(this, "");
    }

    @Override
    public String getTagName() {
        return tag == null || tag.isBlank() ? role : tag;
    }

    @Override
    public String getDomProperty(String name) {
        return attribute(name);
    }

    @Override
    public String getDomAttribute(String name) {
        return attribute(name);
    }

    @Override
    public String getAttribute(String name) {
        return attribute(name);
    }

    @Override
    public String getAriaRole() {
        return role;
    }

    @Override
    public String getAccessibleName() {
        return name;
    }

    @Override
    public boolean isSelected() {
        return selected;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String getText() {
        return text == null ? "" : text;
    }

    @Override
    public List<WebElement> findElements(By by) {
        if (by == null) return List.of();
        DomCompositeAdapter adapter = DomCompositeAdapter.get();
        if (adapter.queryFlag("within") && nodeId != null && !nodeId.isBlank()) {
            return adapter.findScoped(by, nodeId, null, null);
        }
        CompositeLog.info("adapter", "within fallback");
        By scoped = scope(by);
        if (scoped == null) return List.of();
        return adapter.find(scoped);
    }

    @Override
    public WebElement findElement(By by) {
        List<WebElement> found = findElements(by);
        if (found.isEmpty()) throw new NoSuchElementException("No composite match for " + by);
        return found.get(0);
    }

    @Override
    public SearchContext getShadowRoot() {
        String id = nodeId;
        return new SearchContext() {
            @Override
            public List<WebElement> findElements(By by) {
                DomCompositeAdapter.get().pushShadow(id);
                return DomCompositeAdapter.get().find(by);
            }

            @Override
            public WebElement findElement(By by) {
                List<WebElement> found = findElements(by);
                if (found.isEmpty()) throw new NoSuchElementException("No composite shadow match for " + by);
                return found.get(0);
            }
        };
    }

    @Override
    public boolean isDisplayed() {
        return displayed;
    }

    @Override
    public Point getLocation() {
        return new Point(x, y);
    }

    @Override
    public Dimension getSize() {
        return new Dimension(width, height);
    }

    @Override
    public Rectangle getRect() {
        return new Rectangle(x, y, height, width);
    }

    @Override
    public String getCssValue(String propertyName) {
        return "";
    }

    @Override
    public <X> X getScreenshotAs(OutputType<X> target) throws WebDriverException {
        throw new WebDriverException("error: composite element has no screenshot. fix: use the driver screenshot or composite screenshot.");
    }

    public void enterFrame() {
        String id = attributes.get("id");
        DomCompositeAdapter.get().pushFrame(id == null || id.isBlank() ? nodeId : id);
    }

    /** Ancestors of this node. Empty when the node has no stable xpath anchor. */
    public List<WebElement> ancestorElements() {
        String anchor = xpathAnchor();
        if (anchor == null) return List.of();
        try {
            return DomCompositeAdapter.get().find(By.xpath(anchor + "/ancestor::*"));
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    By scope(By by) {
        String raw = by.toString();
        String prefix = "By.xpath: ";
        if (!raw.startsWith(prefix)) return null;
        String xp = raw.substring(prefix.length()).trim();
        if (xp.startsWith("//") || xp.startsWith("(//") || xp.startsWith("(/")) return by;
        String anchor = xpathAnchor();
        if (anchor == null) return null;
        if (xp.startsWith("(.//")) return By.xpath("(" + anchor + xp.substring(2));
        if (xp.startsWith(".//")) return By.xpath(anchor + xp.substring(1));
        if (xp.startsWith("./")) return By.xpath(anchor + xp.substring(1));
        if (xp.startsWith(".")) return By.xpath(anchor + xp.substring(1));
        if (xp.startsWith("/") || xp.startsWith("(")) return by;
        return By.xpath(anchor + "/" + xp);
    }

    /** XPath that finds this node again. Null when the node has no id or name anchor. */
    public String anchor() {
        return xpathAnchor();
    }

    private String xpathAnchor() {
        String id = attributes.get("id");
        if (usableToken(id)) return "//*[@id='" + id + "']";
        String name = attributes.get("name");
        if (usableToken(name) && tag != null && tag.matches("[A-Za-z][A-Za-z0-9]*")) {
            return "//" + tag.toLowerCase(Locale.ROOT) + "[@name='" + name + "']";
        }
        return null;
    }

    private static boolean usableToken(String value) {
        return value != null && !value.isBlank() && value.indexOf('\'') < 0 && value.indexOf('"') < 0;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CompositeWebElement element)) return false;
        return Objects.equals(nodeId, element.nodeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeId);
    }

    @Override
    public String toString() {
        return "composite:" + nodeId;
    }

    private String attribute(String key) {
        if (key == null) return null;
        if ("id".equals(key) && attributes.containsKey("id")) return attributes.get("id");
        if ("role".equals(key)) return role;
        return attributes.get(key);
    }

    private static String first(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static String joinId(String frame, String backend) {
        if (frame.isBlank() && backend.isBlank()) return "";
        return frame + ":" + backend;
    }

    private static String text(JsonNode node, String field, String fallback) {
        if (node == null || node.get(field) == null || node.get(field).isNull()) return fallback;
        String value = node.get(field).asText("");
        return value.isEmpty() ? fallback : value;
    }

    private static boolean state(JsonNode node, String name) {
        if (node == null) return false;
        JsonNode states = node.get("states");
        if (states != null && states.isArray()) {
            for (JsonNode state : states) {
                if (name.equalsIgnoreCase(state.asText(""))) return true;
            }
        }
        return node.path(name).asBoolean(false);
    }

    private static Map<String, String> attributes(JsonNode node) {
        Map<String, String> map = new LinkedHashMap<>();
        if (node == null) return map;
        JsonNode raw = node.get("attributes");
        if (raw != null && raw.isObject()) {
            raw.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isNull()) map.put(entry.getKey(), entry.getValue().asText(""));
            });
        }
        return map;
    }
}
