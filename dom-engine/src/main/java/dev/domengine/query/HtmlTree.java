package dev.domengine.query;

import dev.domengine.tree.ContextWalker;
import dev.domengine.tree.DomNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Offline HTML fixture reader. Declarative shadow roots and iframe {@code srcdoc} become contexts. */
public final class HtmlTree {
    private static final Set<String> VOID = Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr"
    );

    private final String html;
    private int index;
    private long nextId = 1;

    private HtmlTree(String html) {
        this.html = html == null ? "" : html;
    }

    public static DomNode parse(String html) {
        DomNode root = read(html);
        root.linkParents();
        DomNode.renumber(root);
        ContextWalker.place(root);
        return root;
    }

    private static DomNode read(String html) {
        HtmlTree reader = new HtmlTree(html);
        return reader.document();
    }

    private DomNode document() {
        DomNode root = new DomNode();
        root.nodeType = 9;
        root.nodeName = "#document";
        root.backendNodeId = nextId++;
        root.nodeId = root.backendNodeId;
        while (index < html.length()) {
            DomNode child = readNode();
            if (child != null) root.children.add(child);
        }
        return root;
    }

    private DomNode readNode() {
        skipSpace();
        if (index >= html.length()) return null;
        if (startsWith("<!--")) {
            int end = html.indexOf("-->", index);
            index = end < 0 ? html.length() : end + 3;
            return null;
        }
        if (startsWith("<!")) {
            int end = html.indexOf('>', index);
            index = end < 0 ? html.length() : end + 1;
            return null;
        }
        if (html.charAt(index) != '<') return text();
        if (startsWith("</")) {
            return null;
        }
        index++;
        String name = readName();
        DomNode node = new DomNode();
        node.nodeType = 1;
        node.nodeName = name.toUpperCase(Locale.ROOT);
        node.localName = name.toLowerCase(Locale.ROOT);
        node.backendNodeId = nextId++;
        node.nodeId = node.backendNodeId;
        readAttributes(node);
        skipSpace();
        boolean selfClose = index < html.length() && html.charAt(index) == '/';
        if (selfClose) index++;
        if (index < html.length() && html.charAt(index) == '>') index++;
        if (selfClose || VOID.contains(node.localName)) {
            if ("iframe".equals(node.localName)) adoptSrcdoc(node);
            return node;
        }
        while (index < html.length()) {
            if (startsWith("</")) {
                int mark = index;
                index += 2;
                String close = readName();
                int end = html.indexOf('>', index);
                index = end < 0 ? html.length() : end + 1;
                if (close.equalsIgnoreCase(name)) break;
                index = mark;
                break;
            }
            DomNode child = readNode();
            if (child == null) {
                if (startsWith("</")) continue;
                break;
            }
            if ("template".equals(child.localName) && child.attributes.containsKey("shadowrootmode")) {
                DomNode shadow = new DomNode();
                shadow.shadowRoot = true;
                shadow.shadowMode = child.attributes.getOrDefault("shadowrootmode", "open");
                shadow.nodeType = 11;
                shadow.nodeName = "#document-fragment";
                shadow.backendNodeId = child.backendNodeId;
                shadow.nodeId = child.nodeId;
                shadow.children.addAll(child.children);
                node.shadowRoots.add(shadow);
            } else {
                node.children.add(child);
            }
        }
        if ("iframe".equals(node.localName)) adoptSrcdoc(node);
        node.iframe = "iframe".equals(node.localName) || "frame".equals(node.localName);
        return node;
    }

    private void adoptSrcdoc(DomNode iframe) {
        iframe.iframe = true;
        String srcdoc = iframe.attributes.get("srcdoc");
        if (srcdoc == null || srcdoc.isBlank() || !iframe.children.isEmpty()) return;
        DomNode nested = read(srcdoc);
        iframe.children.addAll(nested.children);
    }

    private DomNode text() {
        int start = index;
        while (index < html.length() && html.charAt(index) != '<') index++;
        String value = html.substring(start, index);
        if (value.isBlank()) return null;
        DomNode node = new DomNode();
        node.nodeType = 3;
        node.nodeName = "#text";
        node.nodeValue = value;
        node.backendNodeId = nextId++;
        node.nodeId = node.backendNodeId;
        return node;
    }

    private void readAttributes(DomNode node) {
        while (index < html.length()) {
            skipSpace();
            if (index >= html.length()) return;
            char c = html.charAt(index);
            if (c == '>' || c == '/') return;
            String attr = readName();
            if (attr.isEmpty()) {
                index++;
                continue;
            }
            skipSpace();
            String value = "";
            if (index < html.length() && html.charAt(index) == '=') {
                index++;
                skipSpace();
                value = readValue();
            }
            node.attributes.put(attr.toLowerCase(Locale.ROOT), value);
        }
    }

    private String readValue() {
        if (index >= html.length()) return "";
        char quote = html.charAt(index);
        if (quote == '"' || quote == '\'') {
            index++;
            int start = index;
            while (index < html.length() && html.charAt(index) != quote) index++;
            String value = html.substring(start, index);
            if (index < html.length()) index++;
            return value;
        }
        int start = index;
        while (index < html.length() && !Character.isWhitespace(html.charAt(index)) && html.charAt(index) != '>' && html.charAt(index) != '/') {
            index++;
        }
        return html.substring(start, index);
    }

    private String readName() {
        int start = index;
        while (index < html.length()) {
            char c = html.charAt(index);
            if (Character.isLetterOrDigit(c) || c == '-' || c == ':' || c == '_') index++;
            else break;
        }
        return html.substring(start, index);
    }

    private void skipSpace() {
        while (index < html.length() && Character.isWhitespace(html.charAt(index))) index++;
    }

    private boolean startsWith(String token) {
        return html.regionMatches(true, index, token, 0, token.length());
    }

    public static String textOf(DomNode node) {
        StringBuilder builder = new StringBuilder();
        appendText(node, builder);
        return builder.toString().replaceAll("\\s+", " ").trim();
    }

    private static void appendText(DomNode node, StringBuilder builder) {
        if (node.nodeType == 3) {
            builder.append(node.nodeValue);
            return;
        }
        for (DomNode child : node.children) appendText(child, builder);
        for (DomNode shadow : node.shadowRoots) appendText(shadow, builder);
    }

    public static void link(DomNode node) {
        node.linkParents();
    }

    public static List<DomNode> elements(DomNode node) {
        List<DomNode> list = new ArrayList<>();
        if (node.nodeType == 1 && !node.shadowRoot) list.add(node);
        return list;
    }
}
