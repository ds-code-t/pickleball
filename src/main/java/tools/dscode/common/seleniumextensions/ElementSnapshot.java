package tools.dscode.common.seleniumextensions;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Immutable description of one element, taken once.
 *
 * <p>Both the Selenium path and the dom-composite path produce this same record
 * from one shared script. Read it from {@code ElementWrapper.snapshot()}.
 * Do not parse two XPaths, and do not treat {@code siblingIndex} or
 * {@code sameTagIndex} as 0 unless this element really is the first sibling.
 * Positions and {@code selectedValue} are real in both modes.
 * {@code nodeId} and {@code bounds} are present only for a composite hit.
 * Selenium leaves them null. {@code geometry} is the richer view (five spaces)
 * when the query listed {@code geometry}. Selenium leaves it null, and so does
 * a composite hit whose payload has no {@code geometry} object. Nothing in this
 * record is refreshed in place: a new record replaces it when the wrapper calls
 * {@code refreshSnapshot()}. A node id is stable only until the element is re-rendered.
 *
 * <p>{@code value} is the DOM value property (or the value attribute when the
 * property is empty). {@code text} is the historical {@code textContent}
 * (attribute value for input and textarea, selected option text for select,
 * otherwise innerText). {@code selectedValue} is the concatenated value of
 * direct children that carry the {@code selected} attribute, or null when
 * there are none. An empty string means those children exist and their value
 * is empty. {@code elementState} is the enabled, blank, required, expanded,
 * and on predicates, taken in the same script. Null means the driver could
 * not evaluate them, and a later check may fall back to one find.
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
        String stableSelector,
        tools.dscode.common.composite.ElementState elementState,
        Geometry geometry
) {
    /**
     * Border box in CSS pixels. Null on the Selenium path, which does not
     * record geometry on the snapshot. This stays the integer AABB. Quads live
     * on {@link Geometry}.
     */
    public record Bounds(int x, int y, int width, int height) {
    }

    /** One corner in CSS pixels. */
    public record Corner(double x, double y) {
    }

    /**
     * One box in one coordinate space. {@code kind} is {@code quad} or {@code aabb}.
     * {@code aabb} means the stored source was an axis-aligned snapshot, not a rotated quad.
     */
    public record Space(String kind, List<Corner> corners, double x, double y, double width, double height) {
        public Space {
            kind = kind == null || kind.isBlank() ? "quad" : kind;
            corners = corners == null || corners.isEmpty() ? List.of() : List.copyOf(corners);
        }

        public boolean aabb() {
            return "aabb".equals(kind);
        }
    }

    /** The scrollport this element's nearest-scrollport space is measured from. */
    public record NearestScrollport(String kind, String id, String origin) {
        public NearestScrollport {
            kind = kind == null ? "" : kind;
            id = id == null ? "" : id;
            origin = origin == null ? "" : origin;
        }
    }

    /**
     * The five spaces for one mapped box. Absent (null) on Selenium and when
     * the binary does not list {@code geometry}. Closed-shadow page script
     * hit-testing retargets to the host. These corners are the CDP or stored
     * quads. They are not a page-JS {@code elementFromPoint} result.
     */
    public record Geometry(
            Space documentCssPx,
            Space visualViewportRelative,
            Space layoutViewportRelative,
            Space nearestScrollportRelative,
            Space frameDocumentRelative,
            boolean closedShadow,
            String closedShadowLimit,
            NearestScrollport nearestScrollport
    ) {
        public Geometry {
            closedShadowLimit = closedShadowLimit == null ? "" : closedShadowLimit;
        }

        /** True when the document space's axis-aligned box is the same integer AABB as {@code bounds}. */
        public boolean matches(Bounds bounds) {
            if (bounds == null || documentCssPx == null) return false;
            return bounds.x() == (int) documentCssPx.x()
                    && bounds.y() == (int) documentCssPx.y()
                    && bounds.width() == (int) documentCssPx.width()
                    && bounds.height() == (int) documentCssPx.height();
        }

        /** Null when the node has no geometry object. Does not invent a box. */
        public static Geometry parse(JsonNode node) {
            if (node == null || node.isNull() || !node.isObject() || !node.has("document_css_px")) return null;
            return new Geometry(
                    space(node.get("document_css_px")),
                    space(node.get("visual_viewport_relative")),
                    space(node.get("layout_viewport_relative")),
                    space(node.get("nearest_scrollport_relative")),
                    space(node.get("frame_document_relative")),
                    node.path("closed_shadow").asBoolean(false),
                    node.path("closed_shadow_limit").asText(""),
                    port(node.get("nearest_scrollport"))
            );
        }

        private static Space space(JsonNode node) {
            if (node == null || !node.isObject()) return null;
            List<Corner> corners = new ArrayList<>();
            JsonNode raw = node.get("corners");
            if (raw != null && raw.isArray()) {
                for (JsonNode corner : raw) {
                    if (corner != null && corner.isArray() && corner.size() >= 2) {
                        corners.add(new Corner(corner.get(0).asDouble(), corner.get(1).asDouble()));
                    }
                }
            }
            return new Space(
                    node.path("kind").asText("quad"),
                    corners,
                    node.path("x").asDouble(),
                    node.path("y").asDouble(),
                    node.path("width").asDouble(),
                    node.path("height").asDouble()
            );
        }

        private static NearestScrollport port(JsonNode node) {
            if (node == null || !node.isObject()) return null;
            return new NearestScrollport(
                    node.path("kind").asText(""),
                    node.path("id").asText(""),
                    node.path("origin").asText("")
            );
        }
    }

    /** Older callers omit geometry. Selenium and a bounds-only hit stay null. */
    public ElementSnapshot(
            String nodeId,
            String tag,
            String text,
            String value,
            String selectedValue,
            Map<String, String> attributes,
            Bounds bounds,
            int siblingIndex,
            int sameTagIndex,
            String stableSelector,
            tools.dscode.common.composite.ElementState elementState
    ) {
        this(nodeId, tag, text, value, selectedValue, attributes, bounds, siblingIndex, sameTagIndex,
                stableSelector, elementState, null);
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
        return new ElementSnapshot(null, "", "", "", null, Map.of(), null, 0, 0, "", null);
    }

    public String attribute(String name) {
        if (name == null) return null;
        return attributes.get(name);
    }

    public boolean hasAttribute(String name) {
        return name != null && attributes.containsKey(name);
    }
}