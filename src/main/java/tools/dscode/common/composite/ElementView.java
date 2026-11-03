package tools.dscode.common.composite;

import java.util.Map;

/**
 * One element's read-only description from a single batched lookup.
 * Composite mode copies the query payload. Selenium mode comes from one script,
 * or from the legacy property reads inside {@link ElementReads} when the driver
 * cannot run that script.
 */
public record ElementView(
        String tag,
        String text,
        boolean displayed,
        boolean enabled,
        boolean selected,
        Map<String, String> attributes,
        int x,
        int y,
        int width,
        int height,
        String value,
        String outerHtml
) {
    public ElementView {
        tag = tag == null ? "" : tag;
        text = text == null ? "" : text;
        attributes = attributes == null || attributes.isEmpty() ? Map.of() : Map.copyOf(attributes);
        value = value == null ? "" : value;
        outerHtml = outerHtml == null ? "" : outerHtml;
    }

    public String attribute(String name) {
        if (name == null) return "";
        String found = attributes.get(name);
        return found == null ? "" : found;
    }
}
