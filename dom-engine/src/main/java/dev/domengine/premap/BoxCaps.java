package dev.domengine.premap;

import dev.domengine.cdp.CdpException;
import dev.domengine.query.Query;
import dev.domengine.tree.DomNode;

import java.util.Locale;
import java.util.Set;

/** Decides whether a node may be a target. It never removes the node from the tree or from sections. */
public record BoxCaps(
        double maxAreaRatio,
        double maxWidthRatio,
        double maxHeightRatio,
        double maxAreaPx,
        double maxWidthPx,
        double maxHeightPx
) {
    public static final String ENV = "DOMENGINE_BOX_CAPS";

    public static BoxCaps defaults() {
        return new BoxCaps(0.60, 0.98, 0.98, 0, 0, 0);
    }

    public record Decision(boolean capped, String reason) {
        public static Decision ok() {
            return new Decision(false, "");
        }
    }

    public Decision assess(DomNode node, double viewportWidth, double viewportHeight, boolean exempt) {
        if (exempt || node == null || !node.hasBox || node.width <= 0 || node.height <= 0) return Decision.ok();
        double vw = viewportWidth <= 0 ? 1 : viewportWidth;
        double vh = viewportHeight <= 0 ? 1 : viewportHeight;
        double areaRatio = (node.width * node.height) / (vw * vh);
        if (maxAreaRatio > 0 && areaRatio > maxAreaRatio) {
            return new Decision(true, String.format(Locale.ROOT, "area %.2f > %.2f", areaRatio, maxAreaRatio));
        }
        double widthRatio = node.width / vw;
        if (maxWidthRatio > 0 && widthRatio > maxWidthRatio) {
            return new Decision(true, String.format(Locale.ROOT, "width %.2f > %.2f", widthRatio, maxWidthRatio));
        }
        double heightRatio = node.height / vh;
        if (maxHeightRatio > 0 && heightRatio > maxHeightRatio) {
            return new Decision(true, String.format(Locale.ROOT, "height %.2f > %.2f", heightRatio, maxHeightRatio));
        }
        if (maxAreaPx > 0 && node.width * node.height > maxAreaPx) {
            return new Decision(true, String.format(Locale.ROOT, "area %.0f > %.0f", node.width * node.height, maxAreaPx));
        }
        if (maxWidthPx > 0 && node.width > maxWidthPx) {
            return new Decision(true, String.format(Locale.ROOT, "width %.0f > %.0f", node.width, maxWidthPx));
        }
        if (maxHeightPx > 0 && node.height > maxHeightPx) {
            return new Decision(true, String.format(Locale.ROOT, "height %.0f > %.0f", node.height, maxHeightPx));
        }
        return Decision.ok();
    }

    public static boolean alwaysExcluded(DomNode node) {
        if (node == null) return true;
        if (node.nodeType == 9 || node.nodeType == 11) return true;
        String tag = node.localName == null ? "" : node.localName;
        if ("html".equals(tag) || "body".equals(tag)) return true;
        String role = Query.roleOf(node);
        if ("desktop".equals(role)) return true;
        if ("window".equals(role)) {
            DomNode parent = node.parent;
            if (parent == null || parent.nodeType == 9 || "desktop".equals(Query.roleOf(parent))) return true;
        }
        return false;
    }

    public static boolean exemptRole(String role, Set<String> asked, boolean sectionClause) {
        if (sectionClause) return true;
        if (role == null || asked == null || asked.isEmpty()) return false;
        return asked.contains(role);
    }

    public static BoxCaps fromFlags(java.util.Map<String, String> flags) {
        BoxCaps caps = fromEnv(System.getenv(ENV));
        if (flags == null) return caps;
        caps = apply(caps, "area", flags.get("box-max-area"));
        caps = apply(caps, "width", flags.get("box-max-width"));
        caps = apply(caps, "height", flags.get("box-max-height"));
        return caps;
    }

    public static BoxCaps fromEnv(String spec) {
        BoxCaps caps = defaults();
        if (spec == null || spec.isBlank()) return caps;
        for (String part : spec.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            int eq = trimmed.indexOf('=');
            if (eq <= 0) throw new CdpException("usage: box cap " + trimmed);
            String key = trimmed.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            caps = apply(caps, key, trimmed.substring(eq + 1).trim());
        }
        return caps;
    }

    private static BoxCaps apply(BoxCaps caps, String key, String raw) {
        if (raw == null || raw.isBlank()) return caps;
        boolean px = raw.endsWith("px");
        String number = px ? raw.substring(0, raw.length() - 2).trim() : raw.trim();
        double value;
        try {
            value = Double.parseDouble(number);
        } catch (NumberFormatException failure) {
            throw new CdpException("usage: box cap " + key);
        }
        return switch (key) {
            case "area", "max-area", "maxarearatio" -> px
                    ? new BoxCaps(caps.maxAreaRatio, caps.maxWidthRatio, caps.maxHeightRatio, value, caps.maxWidthPx, caps.maxHeightPx)
                    : new BoxCaps(value, caps.maxWidthRatio, caps.maxHeightRatio, caps.maxAreaPx, caps.maxWidthPx, caps.maxHeightPx);
            case "width", "max-width", "maxwidthratio" -> px
                    ? new BoxCaps(caps.maxAreaRatio, caps.maxWidthRatio, caps.maxHeightRatio, caps.maxAreaPx, value, caps.maxHeightPx)
                    : new BoxCaps(caps.maxAreaRatio, value, caps.maxHeightRatio, caps.maxAreaPx, caps.maxWidthPx, caps.maxHeightPx);
            case "height", "max-height", "maxheightratio" -> px
                    ? new BoxCaps(caps.maxAreaRatio, caps.maxWidthRatio, caps.maxHeightRatio, caps.maxAreaPx, caps.maxWidthPx, value)
                    : new BoxCaps(caps.maxAreaRatio, caps.maxWidthRatio, value, caps.maxAreaPx, caps.maxWidthPx, caps.maxHeightPx);
            default -> throw new CdpException("usage: unknown box cap: " + key);
        };
    }
}
