package tools.dscode.common.composite;

import java.util.Map;

/**
 * One element's read-only description from a single batched lookup.
 * Composite mode copies the query payload, including {@code geometry} when the
 * hit carried it. Selenium mode comes from one script, or from the legacy
 * property reads inside {@link ElementReads} when the driver cannot run that
 * script. Selenium leaves {@code geometry} null.
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
        String outerHtml,
        ElementState elementState,
        tools.dscode.common.seleniumextensions.ElementSnapshot.Geometry geometry,
        String handle,
        Integer rank,
        Double score,
        java.util.List<String> humanCodes,
        String humanSentence,
        boolean visualOnly
) {
    /** Selenium and a bounds-only composite hit leave geometry and labels null. */
    public ElementView(
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
            String outerHtml,
            ElementState elementState,
            tools.dscode.common.seleniumextensions.ElementSnapshot.Geometry geometry
    ) {
        this(
                tag, text, displayed, enabled, selected, attributes, x, y, width, height,
                value, outerHtml, elementState, geometry, null, null, null, java.util.List.of(), "", false
        );
    }

    /** Selenium and a bounds-only composite hit leave geometry null. */
    public ElementView(
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
            String outerHtml,
            ElementState elementState
    ) {
        this(tag, text, displayed, enabled, selected, attributes, x, y, width, height, value, outerHtml, elementState, null);
    }

    public ElementView {
        tag = tag == null ? "" : tag;
        text = text == null ? "" : text;
        attributes = attributes == null || attributes.isEmpty() ? Map.of() : Map.copyOf(attributes);
        value = value == null ? "" : value;
        outerHtml = outerHtml == null ? "" : outerHtml;
        handle = handle == null ? "" : handle;
        humanCodes = humanCodes == null || humanCodes.isEmpty() ? java.util.List.of() : java.util.List.copyOf(humanCodes);
        humanSentence = humanSentence == null ? "" : humanSentence;
    }

    public String attribute(String name) {
        if (name == null) return "";
        String found = attributes.get(name);
        return found == null ? "" : found;
    }
}
