package tools.dscode.common.agentcontrol;

import java.util.Locale;
import java.util.Map;

/**
 * First-class visual kinds besides overlay. The page, popup, and native paths
 * share this markup and CSS. {@code --width} stays geometry, not border thickness.
 */
public final class VisualKinds {
    private VisualKinds() {
    }

    public static boolean is(String kind) {
        return switch (kind == null ? "" : kind) {
            case "label", "border", "arrow", "step", "magnifier", "spotlight" -> true;
            default -> false;
        };
    }

    /** Kind for a CLI verb, or null when the verb is not a visual component. */
    public static String kind(String op) {
        return switch (op == null ? "" : op) {
            case "label", "unlabel" -> "label";
            case "border", "unborder" -> "border";
            case "arrow", "unarrow" -> "arrow";
            case "step-badge", "unstep-badge" -> "step";
            case "magnifier", "unmagnifier" -> "magnifier";
            case "spotlight", "unspotlight" -> "spotlight";
            default -> null;
        };
    }

    public static boolean command(String op) {
        return kind(op) != null;
    }

    public static boolean removal(String op) {
        return op != null && op.startsWith("un") && kind(op) != null;
    }

    /** Width and height actually painted. Null when the kind stays unsized. */
    public static int[] paintedSize(String kind, SurfaceBox.View box) {
        if (box != null && box.sized() && !"label".equals(kind) && !"step".equals(kind)) {
            return new int[]{box.width(), box.height()};
        }
        return switch (kind == null ? "" : kind) {
            case "border" -> new int[]{48, 24};
            case "magnifier" -> new int[]{96, 96};
            case "spotlight" -> new int[]{80, 36};
            default -> box != null && box.sized() ? new int[]{box.width(), box.height()} : null;
        };
    }

    public static String css(String kind, int x, int y, SurfaceBox.View box, Map<String, String> args) {
        int[] size = paintedSize(kind, box);
        String positioned = "position:fixed;z-index:2147483646;left:" + x + "px;top:" + y + "px;";
        return switch (kind == null ? "" : kind) {
            case "label" -> positioned + "background:#111;color:#fff;font:12px sans-serif;padding:2px 6px;border-radius:3px;";
            case "border" -> "position:fixed;z-index:2147483645;left:" + x + "px;top:" + y + "px;background:transparent;"
                    + "pointer-events:none;box-sizing:border-box;border:" + borderValue(args) + ";" + sizeCss(size);
            case "arrow" -> positioned + "pointer-events:none;" + sizeCss(size);
            case "step" -> positioned + "pointer-events:none;";
            case "magnifier" -> positioned + "border-radius:50%;overflow:hidden;border:3px solid #222;background:#fff;"
                    + sizeCss(size == null ? new int[]{96, 96} : size);
            case "spotlight" -> spotlightCss(x, y, size == null ? new int[]{80, 36} : size, args);
            default -> "";
        };
    }

    public static String stepMarkup(String number, String text) {
        if (number == null || number.isBlank()) {
            throw new IllegalArgumentException("step-badge requires --number");
        }
        int parsed;
        try {
            parsed = Integer.parseInt(number.trim());
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("step-badge --number must be a whole number");
        }
        if (parsed < 0) throw new IllegalArgumentException("step-badge --number must be a whole number");
        String badge = "<span style=\"display:inline-block;min-width:18px;height:18px;line-height:18px;text-align:center;"
                + "border-radius:9px;background:#ff2d55;color:#fff;font:700 12px sans-serif;padding:0 4px;\">"
                + parsed + "</span>";
        if (text != null && !text.isBlank()) {
            badge += "<span style=\"margin-left:4px;font:12px sans-serif;color:#111;background:#fff;padding:1px 4px;\">"
                    + escape(text) + "</span>";
        }
        return badge;
    }

    public static String magnifierMarkup(String text, String zoomRaw) {
        String zoom = zoom(zoomRaw);
        String body = text == null || text.isBlank() ? "magnifier" : escape(text);
        return "<div data-pkb-zoom=\"" + zoom + "\" style=\"transform:scale(" + zoom + ");transform-origin:0 0;\">"
                + body + "</div>";
    }

    public static String zoom(String zoomRaw) {
        if (zoomRaw == null || zoomRaw.isBlank()) return "2";
        double parsed;
        try {
            parsed = Double.parseDouble(zoomRaw.trim());
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("magnifier --zoom must be a number");
        }
        if (parsed < 1 || parsed > 8) throw new IllegalArgumentException("magnifier --zoom must be from 1 to 8");
        return Double.toString(parsed);
    }

    public static String dim(String raw) {
        if (raw == null || raw.isBlank()) return "0.55";
        double parsed;
        try {
            parsed = Double.parseDouble(raw.trim());
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("spotlight --dim must be a number from 0 to 1");
        }
        if (parsed < 0 || parsed > 1) throw new IllegalArgumentException("spotlight --dim must be a number from 0 to 1");
        return Double.toString(parsed);
    }

    /** Arrow from remembered endpoints, or from a target plus a direction. */
    public static Arrow arrow(Map<String, String> args) {
        Map<String, String> values = args == null ? Map.of() : args;
        boolean resolved;
        int[] start;
        int[] end;
        if (present(values.get("from")) && present(values.get("to"))) {
            start = endpoint(values.get("from"));
            end = endpoint(values.get("to"));
            if (start == null || end == null) {
                throw new IllegalArgumentException("arrow needs remembered geometry for --from and --to");
            }
            resolved = true;
        } else {
            String direction = values.get("direction");
            if (!present(direction)) {
                throw new IllegalArgumentException("arrow needs --from and --to, or a target and --direction");
            }
            start = present(values.get("handle")) ? endpoint(values.get("handle")) : null;
            resolved = start != null;
            if (start == null && present(values.get("x")) && present(values.get("y"))) {
                start = new int[]{whole(values.get("x"), "x"), whole(values.get("y"), "y")};
            }
            if (start == null) {
                throw new IllegalArgumentException("arrow needs --from and --to, or a target and --direction");
            }
            end = offset(start, direction.trim().toLowerCase(Locale.ROOT));
        }
        return between(start, end, resolved);
    }

    private static Arrow between(int[] start, int[] end, boolean resolved) {
        int pad = 8;
        int minX = Math.min(start[0], end[0]) - pad;
        int minY = Math.min(start[1], end[1]) - pad;
        int maxX = Math.max(start[0], end[0]) + pad;
        int maxY = Math.max(start[1], end[1]) + pad;
        int width = Math.max(1, maxX - minX);
        int height = Math.max(1, maxY - minY);
        int x1 = start[0] - minX;
        int y1 = start[1] - minY;
        int x2 = end[0] - minX;
        int y2 = end[1] - minY;
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + width + "\" height=\"" + height
                + "\" viewBox=\"0 0 " + width + " " + height + "\">"
                + "<line x1=\"" + x1 + "\" y1=\"" + y1 + "\" x2=\"" + x2 + "\" y2=\"" + y2
                + "\" stroke=\"#222\" stroke-width=\"2\" marker-end=\"url(#pkb-arrowhead)\"/>"
                + "<defs><marker id=\"pkb-arrowhead\" markerWidth=\"8\" markerHeight=\"8\" refX=\"6\" refY=\"3\" orient=\"auto\">"
                + "<path d=\"M0,0 L8,3 L0,6 Z\" fill=\"#222\"/></marker></defs></svg>";
        return new Arrow(svg, minX, minY, width, height, resolved);
    }

    private static int[] endpoint(String handle) {
        SurfaceBox.View box = SurfaceBox.choose(handle, null, null, null, null, null);
        if (box == null) return null;
        if (box.sized()) return new int[]{box.x() + box.width() / 2, box.y() + box.height() / 2};
        return new int[]{box.x(), box.y()};
    }

    private static int[] offset(int[] origin, String direction) {
        int dx = switch (direction) {
            case "left", "west" -> -64;
            case "right", "east" -> 64;
            case "up", "north", "down", "south" -> 0;
            default -> throw new IllegalArgumentException("arrow direction must be up, down, left, or right");
        };
        int dy = switch (direction) {
            case "up", "north" -> -64;
            case "down", "south" -> 64;
            case "left", "west", "right", "east" -> 0;
            default -> throw new IllegalArgumentException("arrow direction must be up, down, left, or right");
        };
        return new int[]{origin[0] + dx, origin[1] + dy};
    }

    private static String spotlightCss(int x, int y, int[] size, Map<String, String> args) {
        String dim = dim(args == null ? null : args.get("dim"));
        return "position:fixed;z-index:2147483644;left:" + x + "px;top:" + y + "px;background:transparent;"
                + "pointer-events:none;box-shadow:0 0 0 9999px rgba(0,0,0," + dim + ");" + sizeCss(size);
    }

    private static String sizeCss(int[] size) {
        if (size == null) return "";
        return "width:" + size[0] + "px;height:" + size[1] + "px;";
    }

    private static String borderValue(Map<String, String> args) {
        String style = args == null ? null : args.get("style");
        if (safeDeclaration(style)) return style.trim();
        String color = args == null ? null : args.get("color");
        if (color != null && color.trim().matches("#[0-9a-fA-F]{3,8}|[A-Za-z]{1,20}")) {
            return "3px solid " + color.trim();
        }
        return "3px solid #0a7a32";
    }

    private static boolean safeDeclaration(String value) {
        if (value == null || value.isBlank() || value.length() > 80) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return !lower.contains("url") && !lower.contains("expression") && !lower.contains(";")
                && !lower.contains("{") && !lower.contains("}") && !lower.contains("<")
                && !lower.contains("javascript");
    }

    static String escape(String text) {
        return text.replace("&", "&").replace("<", "<").replace(">", ">");
    }

    private static int whole(String value, String name) {
        try {
            return Integer.parseInt(value.trim());
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("arrow " + name + " must be a whole number");
        }
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    public record Arrow(String svg, int x, int y, int width, int height, boolean resolved) {
    }
}
