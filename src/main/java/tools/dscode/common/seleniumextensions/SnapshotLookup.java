package tools.dscode.common.seleniumextensions;

import tools.dscode.common.composite.CompositeSelector;
import tools.dscode.common.composite.CompositeWebElement;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Selectors used to reach a composite element so the shared snapshot script
 * can run against the live node.
 *
 * <p>This lookup is the fallback for an engine without {@code eval}, such as
 * the Rust 2.4.0 override. The Java engine lists {@code eval}, so a composite
 * snapshot is one {@code eval} when {@code capabilities.actions} lists it.
 * A {@link CompositeWebElement} is not a WebDriver node, so it cannot be passed
 * as {@code arguments[0]}. The script locates the node through one of these
 * selectors and then uses the same function the Selenium producer runs on a
 * live element. The snapshot stores the selector that function derives, which
 * is what refresh looks up later.
 */
public final class SnapshotLookup {
    private static final String[] ATTRS = {
            "data-testid",
            "data-test",
            "data-user-id",
            "name",
            "title",
            "aria-label"
    };

    private SnapshotLookup() {
    }

    public static List<String> candidates(CompositeWebElement element) {
        Set<String> ordered = new LinkedHashSet<>();
        if (element == null) return List.of();
        String tag = element.nodeTag() == null ? "" : element.nodeTag().toLowerCase(Locale.ROOT);
        Map<String, String> attributes = element.attributes();
        addAttributeCandidates(ordered, tag, attributes);
        addTextCandidate(ordered, tag, element.nodeText());
        CompositeSelector selector = element.selector();
        if (selector != null) {
            addIfLocator(ordered, selector.css());
            addIfLocator(ordered, selector.xpath());
        }
        return List.copyOf(ordered);
    }

    static void addAttributeCandidates(Set<String> ordered, String tag, Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) return;
        String id = attributes.get("id");
        if (cssIdent(id)) ordered.add("#" + id);
        if (attrSafe(id)) {
            ordered.add(attr(tag, "id", id));
            ordered.add("[id=\"" + id + "\"]");
        }
        for (String name : ATTRS) {
            String value = attributes.get(name);
            if (!attrSafe(value)) continue;
            ordered.add(attr(tag, name, value));
            ordered.add("[" + name + "=\"" + value + "\"]");
        }
        String name = attributes.get("name");
        String value = attributes.get("value");
        if (attrSafe(name) && attrSafe(value)) {
            ordered.add(attr(tag, "name", name) + "[value=\"" + value + "\"]");
        }
    }

    private static void addTextCandidate(Set<String> ordered, String tag, String text) {
        if (text == null) return;
        String shown = text.trim().replaceAll("\\s+", " ");
        if (shown.isEmpty() || shown.length() > 80) return;
        if (shown.indexOf('\'') >= 0 || shown.indexOf('"') >= 0) return;
        String node = tag == null || tag.isBlank() || !tag.matches("[a-z][a-z0-9]*") ? "*" : tag;
        ordered.add("//" + node + "[normalize-space(.)='" + shown + "']");
    }

    private static void addIfLocator(Set<String> ordered, String value) {
        if (value == null || value.isBlank()) return;
        String trimmed = value.trim();
        if (trimmed.startsWith("/") || trimmed.startsWith("(") || trimmed.startsWith("#")
                || trimmed.startsWith(".") || trimmed.startsWith("[")
                || trimmed.matches("[A-Za-z][A-Za-z0-9]*([.#\\[]|$).*")) {
            ordered.add(trimmed);
        }
    }

    private static String attr(String tag, String name, String value) {
        String node = tag == null || tag.isBlank() || !tag.matches("[a-z][a-z0-9]*") ? "" : tag;
        return node + "[" + name + "=\"" + value + "\"]";
    }

    static boolean cssIdent(String value) {
        return value != null && value.matches("[A-Za-z_][-A-Za-z0-9_]*");
    }

    static boolean attrSafe(String value) {
        if (value == null || value.isBlank()) return false;
        return value.indexOf('"') < 0 && value.indexOf('\\') < 0 && value.indexOf(']') < 0;
    }

    static List<String> copy(List<String> candidates) {
        return candidates == null ? List.of() : new ArrayList<>(candidates);
    }
}
