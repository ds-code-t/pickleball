package tools.dscode.common.seleniumextensions;

import java.util.Map;

/**
 * Immutable description of one element, taken once.
 *
 * <p>Both the Selenium path and the dom-composite path produce this same record.
 * {@code nodeId} and {@code bounds} are present only for a composite hit.
 * Selenium leaves them null. Positions and {@code selectedValue} are real in
 * both modes. Nothing in this record is refreshed in place: a new record
 * replaces it when the wrapper refreshes.
 *
 * <p>{@code value} is the DOM value property (or the value attribute when the
 * property is empty). {@code text} is the historical {@code textContent}
 * (attribute value for input and textarea, selected option text for select,
 * otherwise innerText). {@code selectedValue} is the concatenated value of
 * direct children that carry the {@code selected} attribute, or null when
 * there are none. An empty string means those children exist and their value
 * is empty.
 */
public record ElementSnapshot(
        String nodeId,
        String tag,
        String text,
        String value,
        String selectedValue,
        Map<String, String> attributes,
        Bounds bounds,
        int siblingIndex,
        int sameTagIndex,
        String stableSelector
) {
    /**
     * Border box in CSS pixels. Null on the Selenium path, which does not
     * record geometry on the snapshot.
     */
    public record Bounds(int x, int y, int width, int height) {
    }

    public ElementSnapshot {
        tag = tag == null ? "" : tag;
        text = text == null ? "" : text;
        value = value == null ? "" : value;
        attributes = attributes == null || attributes.isEmpty() ? Map.of() : Map.copyOf(attributes);
        stableSelector = stableSelector == null ? "" : stableSelector;
        if (siblingIndex < 0) siblingIndex = 0;
        if (sameTagIndex < 0) sameTagIndex = 0;
    }

    public static ElementSnapshot empty() {
        return new ElementSnapshot(null, "", "", "", null, Map.of(), null, 0, 0, "");
    }

    public String attribute(String name) {
        if (name == null) return null;
        return attributes.get(name);
    }

    public boolean hasAttribute(String name) {
        return name != null && attributes.containsKey(name);
    }
}
