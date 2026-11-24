package dev.domengine.query;

import dev.domengine.tree.DomNode;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lazy human and full-access views. The human set is computed once per root
 * object and document version. The cache holds at most {@link #CACHE_LIMIT}
 * entries and is keyed by the root object, not its identity hash. A blank
 * document version is stamped from element count and node id and geometry, so
 * an in-place edit is recomputed. It is not a second stored tree. Full-access
 * leaves the set unset.
 */
public final class ViewMask {
    private static final ThreadLocal<Set<String>> ALLOW = new ThreadLocal<>();
    /** Most recent mask last. Each entry is one root object plus one version key. */
    static final int CACHE_LIMIT = 32;
    private static final Object CACHE_LOCK = new Object();
    private static final List<CacheEntry> CACHE = new ArrayList<>();
    private static final AtomicInteger EVALUATIONS = new AtomicInteger();

    private ViewMask() {
    }

    public static void install(DomNode root, Map<String, String> flags) {
        ALLOW.remove();
        if (!active(flags) || root == null) return;
        String versionKey = versionKey(root, flags.get("doc-version"));
        synchronized (CACHE_LOCK) {
            for (int i = 0; i < CACHE.size(); i++) {
                CacheEntry entry = CACHE.get(i);
                if (entry.root == root && entry.versionKey.equals(versionKey)) {
                    CACHE.remove(i);
                    CACHE.add(entry);
                    ALLOW.set(entry.ids);
                    return;
                }
            }
            EVALUATIONS.incrementAndGet();
            Set<String> ids = Set.copyOf(compute(root));
            CACHE.add(new CacheEntry(root, versionKey, ids));
            while (CACHE.size() > CACHE_LIMIT) CACHE.remove(0);
            ALLOW.set(ids);
        }
    }

    public static void clear() {
        ALLOW.remove();
    }

    /** Entries currently held. The list is bounded by {@link #CACHE_LIMIT}. */
    public static int cachedEntries() {
        synchronized (CACHE_LOCK) {
            return CACHE.size();
        }
    }

    public static void resetForTests() {
        synchronized (CACHE_LOCK) {
            CACHE.clear();
        }
        ALLOW.remove();
        EVALUATIONS.set(0);
    }

    public static int evaluations() {
        return EVALUATIONS.get();
    }

    public static boolean allows(DomNode node) {
        Set<String> ids = ALLOW.get();
        if (ids == null || node == null) return true;
        return ids.contains(node.id());
    }

    public static List<DomNode> retain(List<DomNode> hits) {
        Set<String> ids = ALLOW.get();
        if (ids == null || hits == null) return hits;
        List<DomNode> kept = new ArrayList<>();
        for (DomNode hit : hits) {
            if (hit != null && ids.contains(hit.id())) kept.add(hit);
        }
        return kept;
    }

    /**
     * A set document version is the key. A blank version is a stamp of element
     * count, node ids, and geometry, so a later in-place edit misses the cache.
     */
    private static String versionKey(DomNode root, String version) {
        if (version != null && !version.isBlank()) return "v\0" + version;
        return "s\0" + stamp(root);
    }

    private static String stamp(DomNode root) {
        int[] count = new int[1];
        long[] hash = new long[1];
        stampWalk(root, count, hash);
        return count[0] + ":" + Long.toUnsignedString(hash[0]);
    }

    private static void stampWalk(DomNode node, int[] count, long[] hash) {
        if (node == null) return;
        if (node.nodeType == 1 && !node.shadowRoot) {
            count[0]++;
            mix(hash, node.id());
            mix(hash, node.hasBox ? 1 : 0);
            mix(hash, node.x);
            mix(hash, node.y);
            mix(hash, node.width);
            mix(hash, node.height);
            mix(hash, node.hasVisual ? 1 : 0);
            mix(hash, node.visualX);
            mix(hash, node.visualY);
            mix(hash, node.visualWidth);
            mix(hash, node.visualHeight);
        }
        for (DomNode child : node.children) stampWalk(child, count, hash);
        for (DomNode shadow : node.shadowRoots) stampWalk(shadow, count, hash);
    }

    private static void mix(long[] hash, String value) {
        hash[0] = hash[0] * 0x9E3779B97F4A7C15L + (value == null ? 0L : value.hashCode());
    }

    private static void mix(long[] hash, double value) {
        hash[0] = hash[0] * 0x9E3779B97F4A7C15L + Double.doubleToLongBits(value);
    }

    private static void mix(long[] hash, int value) {
        hash[0] = hash[0] * 0x9E3779B97F4A7C15L + value;
    }

    private static final class CacheEntry {
        final DomNode root;
        final String versionKey;
        final Set<String> ids;

        CacheEntry(DomNode root, String versionKey, Set<String> ids) {
            this.root = root;
            this.versionKey = versionKey;
            this.ids = ids;
        }
    }

    static boolean active(Map<String, String> flags) {
        if (flags == null || "off".equals(flags.get("mask"))) return false;
        String exposure = flags.get("exposure");
        if (exposure == null || exposure.isBlank()) return false;
        if ("full".equalsIgnoreCase(exposure) || "full-access".equalsIgnoreCase(exposure)) return false;
        return "human".equalsIgnoreCase(exposure);
    }

    private static Set<String> compute(DomNode root) {
        Viewport viewport = viewport(root);
        List<DomNode> elements = new ArrayList<>();
        walk(root, elements);
        boolean paint = false;
        for (DomNode node : elements) {
            if (paintOrder(node) != null) {
                paint = true;
                break;
            }
        }
        IdentityHashMap<DomNode, Boolean> seen = new IdentityHashMap<>();
        IdentityHashMap<DomNode, Boolean> scrolled = new IdentityHashMap<>();
        for (DomNode node : elements) {
            if (seen(node, root, viewport, paint, elements)) seen.put(node, Boolean.TRUE);
        }
        for (DomNode node : elements) {
            if (scrollReachable(node, root, viewport)) scrolled.put(node, Boolean.TRUE);
        }
        Set<String> ids = new LinkedHashSet<>();
        for (DomNode node : elements) {
            if (seen.containsKey(node) || scrolled.containsKey(node) || expandReachable(node, seen, scrolled)) {
                ids.add(node.id());
            }
        }
        return ids;
    }

    private static boolean seen(DomNode node, DomNode root, Viewport viewport, boolean paint, List<DomNode> elements) {
        if (!inViewport(node, viewport) || !displayed(node) || blocked(node, root)) return false;
        if (!paint) return true;
        return !occluded(node, elements);
    }

    private static boolean inViewport(DomNode node, Viewport viewport) {
        if (!viewport.known()) return false;
        double x = node.hasVisual ? node.visualX : node.x;
        double y = node.hasVisual ? node.visualY : node.y;
        double width = node.hasVisual && node.visualWidth > 0 ? node.visualWidth : node.width;
        double height = node.hasVisual && node.visualHeight > 0 ? node.visualHeight : node.height;
        if (width <= 0 || height <= 0) return false;
        double overlapW = Math.min(x + width, viewport.width()) - Math.max(x, 0);
        double overlapH = Math.min(y + height, viewport.height()) - Math.max(y, 0);
        return overlapW > 0 && overlapH > 0;
    }

    private static boolean displayed(DomNode node) {
        if (!node.displayed || node.states.contains("hidden") || node.attributes.containsKey("hidden")) return false;
        if ("true".equals(node.attributes.get("aria-hidden"))) return false;
        if ("none".equals(style(node, "display"))) return false;
        if ("hidden".equals(style(node, "visibility"))) return false;
        return !zero(style(node, "opacity"));
    }

    private static boolean scrollReachable(DomNode node, DomNode root, Viewport viewport) {
        if (!node.hasBox || blocked(node, root) || hiddenBesidesScroll(node) || inViewport(node, viewport)) return false;
        if (viewport.known() && (viewport.contentWidth() > viewport.width() || viewport.contentHeight() > viewport.height())
                && contains(0, 0, viewport.contentWidth(), viewport.contentHeight(), node)) {
            return true;
        }
        for (DomNode ancestor = node.parent; ancestor != null; ancestor = ancestor.parent) {
            Scrollport port = scrollport(ancestor);
            if (port == null) continue;
            if ((port.contentWidth > port.clientWidth || port.contentHeight > port.clientHeight)
                    && contains(port.x, port.y, port.contentWidth, port.contentHeight, node)) {
                return true;
            }
        }
        return false;
    }

    private static boolean expandReachable(
            DomNode node,
            IdentityHashMap<DomNode, Boolean> seen,
            IdentityHashMap<DomNode, Boolean> scrolled
    ) {
        for (DomNode ancestor = node.parent; ancestor != null; ancestor = ancestor.parent) {
            if (!collapsed(ancestor) || !enabled(ancestor)) continue;
            if (!seen.containsKey(ancestor) && !scrolled.containsKey(ancestor)) continue;
            boolean region = region(ancestor);
            if (!region && !node.hasBox) continue;
            boolean inside = region && node.hasBox && contains(
                    number(ancestor, "data-region-x"),
                    number(ancestor, "data-region-y"),
                    number(ancestor, "data-region-w"),
                    number(ancestor, "data-region-h"),
                    node
            );
            if (inside || onlyCollapsed(node)) return true;
        }
        return false;
    }

    private static boolean hiddenBesidesScroll(DomNode node) {
        return !node.displayed
                || node.states.contains("hidden")
                || node.attributes.containsKey("hidden")
                || "true".equals(node.attributes.get("aria-hidden"))
                || node.attributes.containsKey("inert")
                || "none".equals(style(node, "display"))
                || "hidden".equals(style(node, "visibility"))
                || zero(style(node, "opacity"));
    }

    private static boolean onlyCollapsed(DomNode node) {
        return !node.attributes.containsKey("hidden")
                && !"true".equals(node.attributes.get("aria-hidden"))
                && !node.attributes.containsKey("inert")
                && !"none".equals(style(node, "display"))
                && !"hidden".equals(style(node, "visibility"))
                && !zero(style(node, "opacity"));
    }

    private static boolean blocked(DomNode node, DomNode root) {
        return background(node, root) || closedMenu(node) || hiddenContext(node);
    }

    private static boolean background(DomNode node, DomNode root) {
        for (DomNode cursor = node; cursor != null && cursor != root; cursor = cursor.parent) {
            if (!window(cursor)) continue;
            if ("true".equals(cursor.attributes.get("data-offscreen")) || cursor.attributes.containsKey("offscreen")) {
                return true;
            }
        }
        return false;
    }

    private static boolean closedMenu(DomNode node) {
        for (DomNode cursor = node; cursor != null; cursor = cursor.parent) {
            boolean menu = "menu".equalsIgnoreCase(role(cursor)) || "menu".equals(cursor.localName);
            if (menu && (!cursor.hasBox || cursor.width <= 0 || cursor.height <= 0)) return true;
        }
        return false;
    }

    private static boolean hiddenContext(DomNode node) {
        for (DomNode cursor = node.parent; cursor != null; cursor = cursor.parent) {
            if ("true".equals(cursor.attributes.get("aria-hidden")) || cursor.attributes.containsKey("inert")) return true;
        }
        return false;
    }

    private static boolean occluded(DomNode node, List<DomNode> elements) {
        Integer order = paintOrder(node);
        int mine = order == null ? 0 : order;
        if (!node.hasBox) return false;
        for (DomNode other : elements) {
            if (other == node) continue;
            Integer later = paintOrder(other);
            if (later == null || later <= mine || !"true".equals(other.attributes.get("data-opaque"))) continue;
            if (contains(other.x, other.y, other.width, other.height, node)) return true;
        }
        return false;
    }

    private static boolean collapsed(DomNode node) {
        if ("false".equals(node.attributes.get("aria-expanded"))) return true;
        if ("details".equals(node.localName) && !node.attributes.containsKey("open")) return true;
        return node.states.contains("collapsed");
    }

    private static boolean enabled(DomNode node) {
        return !node.states.contains("disabled")
                && !node.attributes.containsKey("disabled")
                && !"true".equals(node.attributes.get("aria-disabled"));
    }

    private static boolean window(DomNode node) {
        return "window".equalsIgnoreCase(role(node)) || "window".equals(node.localName);
    }

    private static String role(DomNode node) {
        if (node.role != null && !node.role.isBlank()) return node.role;
        String attr = node.attributes.get("role");
        return attr == null ? "" : attr;
    }

    private static boolean region(DomNode node) {
        return number(node, "data-region-w") > 0 && number(node, "data-region-h") > 0;
    }

    private static boolean contains(double x, double y, double width, double height, DomNode node) {
        if (!node.hasBox || width <= 0 || height <= 0) return false;
        return node.x >= x && node.y >= y && node.x + node.width <= x + width && node.y + node.height <= y + height;
    }

    private static Scrollport scrollport(DomNode node) {
        double clientW = number(node, "data-client-w");
        double clientH = number(node, "data-client-h");
        double contentW = number(node, "data-content-w");
        double contentH = number(node, "data-content-h");
        if (clientW <= 0 || clientH <= 0 || contentW <= 0 || contentH <= 0) return null;
        double x = node.attributes.containsKey("data-content-x") ? number(node, "data-content-x") : node.x;
        double y = node.attributes.containsKey("data-content-y") ? number(node, "data-content-y") : node.y;
        return new Scrollport(x, y, clientW, clientH, contentW, contentH);
    }

    private static Viewport viewport(DomNode root) {
        DomNode carrier = root;
        if (root.nodeType == 9) {
            for (DomNode child : root.children) {
                if ("html".equals(child.localName)) {
                    carrier = child;
                    break;
                }
            }
        }
        double width = number(carrier, "data-viewport-w");
        double height = number(carrier, "data-viewport-h");
        if (width <= 0 && carrier.hasLayoutViewport) {
            width = carrier.layoutViewportWidth;
            height = carrier.layoutViewportHeight;
        }
        if (width <= 0 && root.hasLayoutViewport) {
            width = root.layoutViewportWidth;
            height = root.layoutViewportHeight;
        }
        if (width <= 0 || height <= 0) return new Viewport(false, 0, 0, 0, 0);
        double contentW = number(carrier, "data-content-w");
        double contentH = number(carrier, "data-content-h");
        if (contentW <= 0) contentW = width;
        if (contentH <= 0) contentH = height;
        return new Viewport(true, width, height, contentW, contentH);
    }

    private static Integer paintOrder(DomNode node) {
        String raw = node.attributes.get("data-paint-order");
        if (raw == null || raw.isBlank()) return null;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    private static String style(DomNode node, String property) {
        if (node.computed.containsKey(property)) return node.computed.get(property).trim().toLowerCase(Locale.ROOT);
        String style = node.attributes.get("style");
        if (style == null) return "";
        for (String part : style.split(";")) {
            int colon = part.indexOf(':');
            if (colon < 0) continue;
            if (part.substring(0, colon).trim().equalsIgnoreCase(property)) {
                return part.substring(colon + 1).trim().toLowerCase(Locale.ROOT);
            }
        }
        return "";
    }

    private static boolean zero(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            return Double.parseDouble(value) == 0;
        } catch (NumberFormatException failure) {
            return false;
        }
    }

    private static double number(DomNode node, String name) {
        String raw = node.attributes.get(name);
        if (raw == null || raw.isBlank()) return 0;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException failure) {
            return 0;
        }
    }

    private static void walk(DomNode node, List<DomNode> into) {
        if (node == null) return;
        if (node.nodeType == 1 && !node.shadowRoot) into.add(node);
        for (DomNode child : node.children) walk(child, into);
        for (DomNode shadow : node.shadowRoots) walk(shadow, into);
    }

    private record Viewport(boolean known, double width, double height, double contentWidth, double contentHeight) {
    }

    private record Scrollport(double x, double y, double clientWidth, double clientHeight, double contentWidth, double contentHeight) {
    }
}
