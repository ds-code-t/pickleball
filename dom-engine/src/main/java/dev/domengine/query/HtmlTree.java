package dev.domengine.query;

import dev.domengine.tree.ContextWalker;
import dev.domengine.tree.DomNode;

import java.util.ArrayList;
import java.util.HashSet;
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
    /** Offsets of `<` for void starts that a matching end tag closes. Computed once per parse. */
    private Set<Integer> voidClosed;

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
        prepareVoidEnds();
        DomNode root = new DomNode();
        root.nodeType = 9;
        root.nodeName = "#document";
        root.backendNodeId = nextId++;
        root.nodeId = root.backendNodeId;
        while (index < html.length()) {
            if (startsWith("</")) {
                skipEnd();
                continue;
            }
            int mark = index;
            DomNode child = readNode();
            if (child != null) root.children.add(child);
            else if (index == mark) index++;
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
        int startAt = index;
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
        if (selfClose || (VOID.contains(node.localName) && !explicitEnd(startAt))) {
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
            if ("template".equals(child.localName)
                    && (child.attributes.containsKey("shadowrootmode") || child.attributes.containsKey("data-pkb-shadow"))) {
                DomNode shadow = new DomNode();
                shadow.shadowRoot = true;
                shadow.shadowMode = child.attributes.getOrDefault(
                        "data-pkb-shadow", child.attributes.getOrDefault("shadowrootmode", "open"));
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

    private void skipEnd() {
        index += 2;
        readName();
        int end = html.indexOf('>', index);
        index = end < 0 ? html.length() : end + 1;
    }

    /**
     * A void tag with a matching end tag is a container. Desktop HTML-ish writes
     * {@code <input>} and {@code <img>} that way when the control has children.
     * A void tag with no matching end tag stays empty, so a following sibling is not swallowed.
     * One stack pass records that answer for every void start. {@link #explicitEnd} is a lookup.
     */
    private void prepareVoidEnds() {
        List<String> opens = new ArrayList<>();
        List<VoidStart> pending = new ArrayList<>();
        Set<Integer> closed = new HashSet<>();
        int i = 0;
        while (i < html.length()) {
            int lt = html.indexOf('<', i);
            if (lt < 0) break;
            if (region(lt, "<!--")) {
                int end = html.indexOf("-->", lt + 4);
                i = end < 0 ? html.length() : end + 3;
                continue;
            }
            if (region(lt, "</")) {
                int nameStart = lt + 2;
                int nameEnd = nameStart;
                while (nameEnd < html.length() && isName(html.charAt(nameEnd))) nameEnd++;
                String close = html.substring(nameStart, nameEnd).toLowerCase(Locale.ROOT);
                int gt = html.indexOf('>', nameEnd);
                i = gt < 0 ? html.length() : gt + 1;
                closeVoid(opens, pending, closed, close);
                continue;
            }
            if (region(lt, "<!")) {
                int gt = html.indexOf('>', lt + 2);
                i = gt < 0 ? html.length() : gt + 1;
                continue;
            }
            int nameStart = lt + 1;
            int nameEnd = nameStart;
            while (nameEnd < html.length() && isName(html.charAt(nameEnd))) nameEnd++;
            String open = html.substring(nameStart, nameEnd).toLowerCase(Locale.ROOT);
            int j = nameEnd;
            boolean self = false;
            while (j < html.length()) {
                char c = html.charAt(j);
                if (c == '"' || c == '\'') {
                    int quote = html.indexOf(c, j + 1);
                    j = quote < 0 ? html.length() : quote + 1;
                    continue;
                }
                if (c == '>') {
                    self = j > nameEnd && html.charAt(j - 1) == '/';
                    j++;
                    break;
                }
                j++;
            }
            i = j;
            if (open.isEmpty() || self) continue;
            opens.add(open);
            if (VOID.contains(open)) pending.add(new VoidStart(open, opens.size(), lt));
        }
        voidClosed = closed;
    }

    /**
     * The same answer as a forward scan that starts empty at this void tag.
     * An end tag seen while that scan's stack is empty decides the tag.
     * An end tag seen while the stack is not empty pops until a name matches, or drops the close.
     */
    private static void closeVoid(List<String> opens, List<VoidStart> pending, Set<Integer> closed, String close) {
        int depth = opens.size();
        for (int q = pending.size() - 1; q >= 0; q--) {
            VoidStart start = pending.get(q);
            if (start.base != depth) continue;
            if (start.name.equals(close)) closed.add(start.offset);
            pending.remove(q);
        }
        for (int q = pending.size() - 1; q >= 0; q--) {
            int base = pending.get(q).base;
            if (opens.size() <= base) continue;
            boolean matched = false;
            while (opens.size() > base) {
                String top = opens.remove(opens.size() - 1);
                if (top.equals(close)) {
                    matched = true;
                    break;
                }
            }
            if (matched) break;
        }
    }

    private boolean explicitEnd(int startAt) {
        return voidClosed != null && voidClosed.contains(startAt);
    }

    /** A void start waiting for the end tag that its own forward scan would see first. */
    private record VoidStart(String name, int base, int offset) {
    }

    private boolean region(int at, String token) {
        return html.regionMatches(true, at, token, 0, token.length());
    }

    private static boolean isName(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == ':' || c == '_';
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
        int mark = builder.length();
        for (DomNode child : node.children) appendText(child, builder);
        if (node.shadowRoots.isEmpty()) return;
        String light = builder.substring(mark).replaceAll("\\s+", " ").trim();
        for (DomNode shadow : node.shadowRoots) {
            StringBuilder extra = new StringBuilder();
            appendText(shadow, extra);
            String shadowText = extra.toString().replaceAll("\\s+", " ").trim();
            if (shadowText.isEmpty() || shadowText.equals(light)) continue;
            builder.append(extra);
        }
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
