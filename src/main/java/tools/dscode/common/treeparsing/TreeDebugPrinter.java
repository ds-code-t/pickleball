package tools.dscode.common.treeparsing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pretty printers:
 *  • Definition tree (ParseNode)
 *  • Match tree (runtime) with original, modified, masked, and fully-resolved strings
 *
 * Fully-resolved = global token expansion (iteratively replaces all tokens found anywhere
 * with their locally-resolved contents until no tokens remain).
 */
public final class TreeDebugPrinter {
    private TreeDebugPrinter() {}

    // ===== Definition tree =====
    public static void printDefinitionTree(ParseNode root) {
        System.out.println("Definition Tree");
        System.out.println("________________");
        printDefRec(root, "", true);
    }

    private static void printDefRec(ParseNode n, String prefix, boolean last) {
        String branch = last ? "`-- " : "|-- ";
        String next   = prefix + (last ? "    " : "|   ");
        String rx     = (n.hasRegex() ? " regex=\"" + n.getRegexPattern() + "\"" : "");
        System.out.println(prefix + branch + n.getName() + rx);

        List<ParseNode> kids = n.parseChildren;
        if (kids.isEmpty()) {
            System.out.println(next + "`-- (no children)");
            return;
        }
        for (int i = 0; i < kids.size(); i++) {
            printDefRec(kids.get(i), next, i == kids.size() - 1);
        }
    }

    // ===== Match tree =====
    public static void printMatchTree(MatchNode top) {
        System.out.println("Match Tree");
        System.out.println("__________");
        Map<String, String> global = buildTokenResolutionMap(top);
        printMatchRec(top, "", true, global);
    }

    private static void printMatchRec(MatchNode m, String prefix, boolean last,
                                      Map<String, String> global) {
        String branch = last ? "`-- " : "|-- ";
        String next   = prefix + (last ? "    " : "|   ");
        String rx     = (m.regex() == null) ? "" : (" regex=\"" + m.regex() + "\"");

        System.out.println(prefix + branch + m.name() + rx);

        String original = quote(m.originalText());
        String modified = quote(m.modifiedText());
        String masked   = quote(m.maskedText());
//        String resolved = quote(applyGlobalResolution(safe(m.maskedText()), global));
        String resolved = m.toString();

        System.out.println(next + "|  original: " + original);
        System.out.println(next + "|  modified: " + modified);
        System.out.println(next + "|  masked:   " + masked);
        System.out.println(next + "|  resolved: " + resolved);
        System.out.println(next +"|");

        List<MatchNode> kids = m.children();
        for (int i = 0; i < kids.size(); i++) {
            printMatchRec(kids.get(i), next, i == kids.size() - 1, global);
        }
    }

    /**
     * Build a global map: token -> locally-resolved text for every node with a token.
     * Locally-resolved = this node's maskedText with each DIRECT child's token replaced
     * by that child's own locally-resolved content (computed recursively).
     */
    private static Map<String, String> buildTokenResolutionMap(MatchNode root) {
        Map<String, String> map = new LinkedHashMap<>();
        fillTokenMap(root, map);
        return map;
    }

    private static void fillTokenMap(MatchNode m, Map<String, String> map) {
        String local = locallyResolvedText(m);
        if (m.token() != null && !m.token().isEmpty()) {
            map.put(m.token(), local);
        }
        for (MatchNode c : m.children()) {
            fillTokenMap(c, map);
        }
    }

    private static String locallyResolvedText(MatchNode m) {
        String out = safe(m.maskedText());
        if (out.isEmpty()) return out;

        for (MatchNode c : m.children()) {
            String token = c.token();
            if (token == null || token.isEmpty()) continue;
            String childLocal = locallyResolvedText(c);
            out = out.replaceAll(
                    Pattern.quote(token),
                    Matcher.quoteReplacement(childLocal)
            );
        }
        return out;
    }



    // ---- helpers ----
    private static String quote(String s) {
        if (s == null) return "\"\"";
        String t = s.replace("\n", "\\n");
        if (t.length() > 800) t = t.substring(0, 797) + "...";
        return "\"" + t + "\"";
    }
    private static String safe(String s) { return (s == null) ? "" : s; }
}
