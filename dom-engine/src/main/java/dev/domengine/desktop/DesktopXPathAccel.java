package dev.domengine.desktop;

import dev.domengine.log.EngineLog;
import dev.domengine.tree.DomNode;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Driver-native XPath as an optional accelerator (D10). {@code pkb_desktop_xpath_accel=on} lets a role-and-name
 * lookup ask the driver for candidates by name ({@code //*[@Name='...']} on Windows drivers, {@code //*[@title or
 * @label]} on Mac2), maps each returned driver XPath back to the canonical node that carries it in
 * {@code grid-xpath}, and confirms role and name on the canonical tree. The canonical tree stays authoritative:
 * candidates that do not confirm are dropped, and when the driver returns nothing usable the canonical walk runs,
 * so the result is the same with the accelerator on or off. Default off.
 */
public final class DesktopXPathAccel {
    public static final String KEY = "pkb_desktop_xpath_accel";

    private DesktopXPathAccel() {
    }

    public static boolean enabled(Map<String, String> env) {
        String value = System.getProperty(KEY, env == null ? null : env.get(KEY));
        return "on".equalsIgnoreCase(value == null ? "" : value.trim());
    }

    /** The driver XPath that finds candidates for an accessible name, or null when the name cannot be quoted. */
    public static String driverXPath(String driver, String name) {
        if (name == null || name.isEmpty() || (name.contains("'") && name.contains("\""))) return null;
        String quoted = name.contains("'") ? "\"" + name + "\"" : "'" + name + "'";
        return "mac2".equals(driver)
                ? "//*[@title=" + quoted + " or @label=" + quoted + "]"
                : "//*[@Name=" + quoted + "]";
    }

    /** Canonical walk: every node with that role (blank matches any) and exact accessible name, in document order. */
    public static List<DomNode> canonical(DomNode root, String role, String name) {
        List<DomNode> out = new ArrayList<>();
        walk(root, role, name, out);
        return out;
    }

    /**
     * Role and name lookup with the accelerator. {@code driver} returns driver XPaths (positional, as in
     * {@code grid-xpath}) for one driver XPath expression; it may throw or return nothing.
     */
    public static List<DomNode> find(DomNode root, String driverName, String role, String name, Function<String, List<String>> driver) {
        String expression = driverXPath(driverName, name);
        if (expression == null) return canonical(root, role, name);
        List<String> paths;
        try {
            paths = driver.apply(expression);
        } catch (RuntimeException failure) {
            EngineLog.info("desktop", "xpath accel failed (" + failure.getMessage() + "); canonical walk");
            return canonical(root, role, name);
        }
        if (paths == null || paths.isEmpty()) return canonical(root, role, name);
        Map<String, DomNode> byPath = new LinkedHashMap<>();
        index(root, byPath);
        Map<DomNode, Boolean> confirmed = new IdentityHashMap<>();
        for (String path : paths) {
            DomNode node = byPath.get(path);
            if (node != null && matches(node, role, name)) confirmed.put(node, true);
        }
        List<DomNode> ordered = new ArrayList<>();
        for (DomNode node : canonical(root, role, name)) if (confirmed.containsKey(node)) ordered.add(node);
        List<DomNode> full = canonical(root, role, name);
        if (ordered.size() != full.size()) {
            // A driver that misses a confirmed match must not change the answer: the canonical result wins.
            EngineLog.info("desktop", "xpath accel candidates=" + paths.size() + " confirmed=" + ordered.size() + " canonical=" + full.size());
            return full;
        }
        EngineLog.info("desktop", "xpath accel candidates=" + paths.size() + " confirmed=" + ordered.size());
        return ordered;
    }

    private static boolean matches(DomNode node, String role, String name) {
        return name.equals(node.accessibleName) && (role == null || role.isBlank() || role.equals(node.role));
    }

    private static void walk(DomNode node, String role, String name, List<DomNode> out) {
        if (node == null) return;
        if (matches(node, role, name)) out.add(node);
        for (DomNode child : node.children) walk(child, role, name, out);
    }

    private static void index(DomNode node, Map<String, DomNode> into) {
        if (node == null) return;
        String path = node.attributes.get("grid-xpath");
        if (path != null) into.put(path, node);
        for (DomNode child : node.children) index(child, into);
    }
}
