package dev.domengine.snapshot;

import dev.domengine.desktop.NativeDesktop;
import dev.domengine.tree.DomNode;

import java.util.Locale;
import java.util.Set;

/**
 * Synthesizes the v2 HTML-ish document from a projected accessibility tree.
 * The original a11y-xml stays side data. This class does not recapture.
 */
public final class CanonicalDesktop {
    private static final Set<String> CONSUMED = Set.of(
            "automation-id", "class-name", "help-text", "platform-role", "value", "text",
            "uia-runtime-id", "ax-path", "atspi-path", "desktop-backend"
    );

    private CanonicalDesktop() {
    }

    public static String html(DomNode root, String platform) {
        String backend = backendOf(platform);
        StringBuilder out = new StringBuilder();
        out.append("<html><body>");
        if (root != null) {
            String role = NativeDesktop.mapRole(nodeBackend(root, backend), root.role);
            if ("desktop".equals(role)) {
                for (DomNode child : root.children) write(child, backend, out);
            } else {
                write(root, backend, out);
            }
        }
        out.append("</body></html>");
        return out.toString();
    }

    private static void write(DomNode node, String fallbackBackend, StringBuilder out) {
        if (node == null || node.nodeType != 1) return;
        String backend = nodeBackend(node, fallbackBackend);
        String role = NativeDesktop.mapRole(backend, node.role);
        Mapped mapped = map(role);
        String name = node.accessibleName == null ? "" : node.accessibleName;
        String separate = node.attributes.getOrDefault("text", "");
        String value = valueOf(node);
        String content = "";
        String ariaLabel = "";
        if (nameOnAria(role)) {
            ariaLabel = name;
        } else if (nameAsText(role)) {
            if (!separate.isBlank()) content = separate;
            else if (value.isBlank()) content = name;
            if (!name.isBlank() && !name.equals(content)) ariaLabel = name;
        } else {
            ariaLabel = name;
        }
        out.append('<').append(mapped.tag);
        if (mapped.type != null && !"combobox".equals(role)) {
            attr(out, "type", mapped.type);
        }
        if (mapped.role != null && !mapped.role.isBlank()) attr(out, "role", mapped.role);
        if ("heading".equals(role)) attr(out, "aria-level", "2");
        if ("dialog".equals(mapped.tag) && node.displayed) attr(out, "open", "open");
        String automationId = node.attributes.get("automation-id");
        if ("uia".equals(backend) && automationId != null && htmlId(automationId)) attr(out, "id", automationId);
        String className = "uia".equals(backend) ? node.attributes.get("class-name") : null;
        if (className != null && !className.isBlank()) attr(out, "class", className);
        if (!ariaLabel.isBlank()) attr(out, "aria-label", ariaLabel);
        if (!value.isBlank()) attr(out, "value", value);
        boolean checked = node.states.contains("checked");
        if (checked && ("checkbox".equals(role) || "radio".equals(role))) attr(out, "checked", "checked");
        else if (checked) attr(out, "aria-checked", "true");
        if (node.states.contains("expanded")) attr(out, "aria-expanded", "true");
        else if ("combobox".equals(role)) attr(out, "aria-expanded", "false");
        if (node.states.contains("disabled")) {
            attr(out, "disabled", "disabled");
            attr(out, "aria-disabled", "true");
        }
        String help = node.attributes.get("help-text");
        if (help != null && !help.isBlank()) attr(out, "title", help);
        if ("uia".equals(backend) && automationId != null && !automationId.isBlank()) {
            attr(out, "data-uia-automation-id", automationId);
        }
        if (className != null && !className.isBlank()) attr(out, "data-uia-class", className);
        String platformRole = node.attributes.get("platform-role");
        if (platformRole != null && !platformRole.isBlank()) attr(out, "data-platform-role", platformRole);
        if (node.states.contains("focused")) attr(out, "data-pkb-focused", "true");
        if (offscreen(node)) attr(out, "data-pkb-offscreen", "true");
        String nativePath = nativePath(node);
        if (!nativePath.isBlank()) attr(out, "data-pkb-native-path", nativePath);
        attr(out, "data-pkb-backend", backend);
        if (node.hasBox) {
            attr(out, "data-x", num(node.x));
            attr(out, "data-y", num(node.y));
            attr(out, "data-w", num(node.width));
            attr(out, "data-h", num(node.height));
        }
        node.attributes.forEach((key, raw) -> {
            if (key == null || key.isBlank() || CONSUMED.contains(key)) return;
            if (raw == null || raw.isBlank()) return;
            attr(out, "data-pkb-attr-" + attrName(key), raw);
        });
        boolean voidTag = ("input".equals(mapped.tag) || "img".equals(mapped.tag))
                && content.isBlank() && !hasElementChild(node);
        if (voidTag) {
            out.append('>');
            return;
        }
        out.append('>');
        out.append(escapeText(content));
        for (DomNode child : node.children) write(child, backend, out);
        out.append("</").append(mapped.tag).append('>');
    }

    private static boolean nameOnAria(String role) {
        return switch (role) {
            case "textbox", "checkbox", "radio", "combobox", "img" -> true;
            default -> false;
        };
    }

    private static boolean nameAsText(String role) {
        return switch (role) {
            case "button", "link", "label", "listitem", "heading", "menuitem" -> true;
            default -> false;
        };
    }

    private static boolean offscreen(DomNode node) {
        return !node.displayed || node.states.contains("offscreen") || node.states.contains("hidden");
    }

    private static boolean hasElementChild(DomNode node) {
        for (DomNode child : node.children) {
            if (child != null && child.nodeType == 1) return true;
        }
        return false;
    }

    private static String valueOf(DomNode node) {
        if (node.nodeValue != null && !node.nodeValue.isBlank()) return node.nodeValue;
        String value = node.attributes.get("value");
        return value == null ? "" : value;
    }

    private static String nativePath(DomNode node) {
        for (String key : new String[] {"uia-runtime-id", "ax-path", "atspi-path"}) {
            String value = node.attributes.get(key);
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static Mapped map(String role) {
        return switch (role) {
            case "button" -> new Mapped("button", null, null);
            case "textbox" -> new Mapped("input", "text", null);
            case "label" -> new Mapped("label", null, null);
            case "checkbox" -> new Mapped("input", "checkbox", null);
            case "radio" -> new Mapped("input", "radio", null);
            case "combobox" -> new Mapped("input", null, "combobox");
            case "window" -> new Mapped("section", null, "window");
            case "application" -> new Mapped("div", null, "application");
            case "dialog" -> new Mapped("dialog", null, null);
            case "img" -> new Mapped("img", null, null);
            case "link" -> new Mapped("a", null, null);
            case "heading" -> new Mapped("h2", null, "heading");
            case "list" -> new Mapped("ul", null, null);
            case "listitem" -> new Mapped("li", null, null);
            case "menu" -> new Mapped("div", null, "menu");
            case "menuitem" -> new Mapped("div", null, "menuitem");
            case "tab" -> new Mapped("div", null, "tab");
            case "desktop" -> new Mapped("div", null, "desktop");
            default -> new Mapped("div", null, "unknown".equals(role) ? null : role);
        };
    }

    private static String nodeBackend(DomNode node, String fallback) {
        String raw = node.attributes.get("desktop-backend");
        if (raw == null || raw.isBlank()) return fallback;
        return backendOf(raw);
    }

    private static String backendOf(String platform) {
        String value = platform == null ? "" : platform.toLowerCase(Locale.ROOT);
        if (value.contains("uia") || value.contains("windows")) return "uia";
        if (value.contains("mac") || value.contains("ax")) return "ax";
        return "atspi";
    }

    private static boolean htmlId(String value) {
        return value.matches("[A-Za-z][A-Za-z0-9_:-]*");
    }

    private static String attrName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            out.append(Character.isLetterOrDigit(ch) ? ch : '-');
        }
        return out.toString();
    }

    private static void attr(StringBuilder out, String name, String value) {
        out.append(' ').append(name).append("=\"").append(escapeAttr(value)).append('"');
    }

    private static String num(double value) {
        if (Double.isFinite(value) && value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    private static String escapeText(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String escapeAttr(String value) {
        return escapeText(value).replace("\"", "&quot;");
    }

    private record Mapped(String tag, String type, String role) {
    }
}
