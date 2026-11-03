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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One composite hit. Element methods call {@link DomCompositeAdapter} and do not call WebDriver. */
public final class CompositeWebElement implements WebElement {
    private final CompositeSelector selector;
    private final String nodeId;
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

    CompositeWebElement(CompositeSelector selector, JsonNode node, int index) {
        this.selector = selector;
        JsonNode value = node == null ? null : node;
        this.nodeId = text(value, "id", "element-" + index);
        this.role = text(value, "role", text(value, "tag", "element"));
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

    int rectHeight() {
        return height;
    }

    Map<String, Object> actionBody(List<String> frames, List<String> shadows) {
        String id = attributes.get("id");
        CompositeSelector action = id != null && !id.isBlank()
                ? new CompositeSelector(null, "#" + id, null, selector.source(), null, selector.phrase())
                : selector;
        return action.body(frames, shadows);
    }

    @Override
    public void click() {
        DomCompositeAdapter.get().click(this);
    }

    @Override
    public void submit() {
        click();
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
        return role == null ? "" : role;
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
        return DomCompositeAdapter.get().find(by);
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
