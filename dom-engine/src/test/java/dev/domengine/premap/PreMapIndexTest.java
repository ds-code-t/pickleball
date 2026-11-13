package dev.domengine.premap;

import dev.domengine.affinity.AffinityScorer;
import dev.domengine.image.TemplateMatch;
import dev.domengine.query.HtmlTree;
import dev.domengine.query.Query;
import dev.domengine.snapshot.SnapshotContext;
import dev.domengine.snapshot.SnapshotOptions;
import dev.domengine.snapshot.XPathIndex;
import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreMapIndexTest {
    private static final String[] FIXTURES = {
            """
            <html><body>
              <form><label for="email">Email</label><input id="email" class="field" type="email">
              <button class="primary">Go</button></form>
            </body></html>
            """,
            """
            <html><body>
              <a href="/save" class="link">Save</a>
              <p class="note">Hello there</p>
              <button id="cancel">Cancel</button>
            </body></html>
            """,
            """
            <html><body>
              <main><h1>Inbox</h1><button>Open</button></main>
              <nav><a href="/n">Nav</a></nav>
            </body></html>
            """,
            """
            <html><body>
              <label>Agree<input id="ok" type="checkbox"></label>
              <input id="name" type="text" placeholder="Name">
            </body></html>
            """,
            """
            <html><body>
              <section aria-label="Tools"><button class="tool" id="a">Cut</button><button class="tool" id="b">Copy</button></section>
            </body></html>
            """
    };

    @Test
    void candidatesMatchLiveEvaluation() {
        String[] kinds = {"role", "role-name", "text", "text-exact", "tag", "id", "class", "css-simple", "xpath-abs", "section"};
        for (String html : FIXTURES) {
            DomNode root = HtmlTree.parse(html);
            PreMapLookup lookup = PreMapLookup.forTree(root);
            XPathIndex.assign(root);
            for (String kind : kinds) {
                for (String value : samples(root, kind)) {
                    List<String> expected = live(root, kind, value);
                    var actual = lookup.candidates(kind, value);
                    assertTrue(actual.isPresent(), kind + " " + value);
                    assertEquals(expected, actual.get(), kind + " = " + value);
                }
            }
        }
    }

    @Test
    void labelLookupReturnsTheBoundControl() {
        DomNode root = HtmlTree.parse(FIXTURES[0]);
        PreMap map = PreMapBuilder.build(root, SnapshotContext.offline(root, new SnapshotOptions(), FIXTURES[0]));
        assertFalseEmpty(map.labels());
        PreMap.LabelRef label = map.labels().get(0);
        PreMapLookup lookup = PreMapLookup.forTree(root);
        assertEquals(List.of(label.target()), lookup.candidates("label", label.label()).orElseThrow());
    }

    @Test
    void imagePrefilterKeepsTheTrueWidget() throws Exception {
        java.nio.file.Path png = java.nio.file.Files.createTempFile("widgets", ".png");
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(120, 20, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < 6; i++) {
            for (int y = 0; y < 20; y++) {
                for (int x = 0; x < 20; x++) {
                    int on = switch (i) {
                        case 0 -> (y / 2) % 2 == 0 ? 20 : 220;
                        case 1 -> (x / 2) % 2 == 0 ? 20 : 220;
                        case 2 -> ((x / 4) + (y / 4)) % 2 == 0 ? 30 : 200;
                        case 3 -> ((x + y) / 3) % 2 == 0 ? 10 : 240;
                        case 4 -> 15;
                        default -> 240;
                    };
                    image.setRGB(i * 20 + x, y, (on << 16) | (on << 8) | on);
                }
            }
        }
        javax.imageio.ImageIO.write(image, "png", png.toFile());
        StringBuilder html = new StringBuilder("<html><body data-x=\"0\" data-y=\"0\" data-w=\"120\" data-h=\"20\">");
        for (int i = 0; i < 6; i++) {
            html.append("<button data-x=\"").append(i * 20).append("\" data-y=\"0\" data-w=\"20\" data-h=\"20\">B").append(i).append("</button>");
        }
        html.append("</body></html>");
        DomNode root = HtmlTree.parse(html.toString());
        PreMap map = PreMapBuilder.build(root, SnapshotContext.offline(root, SnapshotOptions.fromFlags(java.util.Map.of("screenshot", png.toString())), html.toString()));
        var nodes = map.image().path("nodes");
        assertTrue(nodes.size() >= 6, nodes.toString());
        var fields = new java.util.ArrayList<java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>>();
        nodes.fields().forEachRemaining(fields::add);
        var first = fields.get(0).getValue();
        TemplateMatch.Features template = new TemplateMatch.Features(
                Long.parseUnsignedLong(first.path("dhash").asText(), 16),
                first.path("mean").asDouble(),
                first.path("stddev").asDouble()
        );
        int rejected = 0;
        for (int i = 1; i < fields.size(); i++) {
            var other = fields.get(i).getValue();
            TemplateMatch.Features candidate = new TemplateMatch.Features(
                    Long.parseUnsignedLong(other.path("dhash").asText(), 16),
                    other.path("mean").asDouble(),
                    other.path("stddev").asDouble()
            );
            if (!TemplateMatch.prefilter(candidate, template)) rejected++;
        }
        assertTrue(rejected * 5 >= (fields.size() - 1) * 4, "rejected " + rejected + " of " + (fields.size() - 1));
        String key = first.path("dhash").asText() + "@" + first.path("mean").asDouble();
        assertTrue(PreMapLookup.bound(map).candidates("image", key).orElseThrow().contains(fields.get(0).getKey()));
    }

    @Test
    void compoundSelectorThatIsNotSimpleCannotAnswer() {
        DomNode root = HtmlTree.parse(FIXTURES[1]);
        PreMapLookup lookup = PreMapLookup.forTree(root);
        assertTrue(lookup.candidates("css-simple", "div > span").isEmpty());
        assertTrue(lookup.candidates("css-simple", "button.primary, a").isEmpty());
    }

    private static void assertFalseEmpty(List<?> values) {
        assertTrue(values != null && !values.isEmpty());
    }

    private static List<String> samples(DomNode root, String kind) {
        List<String> values = new ArrayList<>();
        List<DomNode> nodes = elements(root);
        switch (kind) {
            case "role" -> {
                for (DomNode node : nodes) {
                    String role = Query.roleOf(node).toLowerCase(Locale.ROOT);
                    if (!role.isBlank() && !values.contains(role)) values.add(role);
                }
                values.add("missing-role");
            }
            case "role-name" -> {
                for (DomNode node : nodes) {
                    String role = Query.roleOf(node).toLowerCase(Locale.ROOT);
                    String name = node.accessibleName == null ? "" : node.accessibleName.trim().toLowerCase(Locale.ROOT);
                    if (!role.isBlank() && !name.isBlank()) values.add(role + "\t" + name);
                }
                if (values.isEmpty()) values.add("button\tgo");
            }
            case "text" -> values.addAll(List.of("email", "go", "save", "hello", "inbox", "missing"));
            case "text-exact" -> {
                for (DomNode node : nodes) {
                    String text = text(node).toLowerCase(Locale.ROOT);
                    if (!text.isBlank() && text.length() < 40 && !values.contains(text)) values.add(text);
                }
            }
            case "tag" -> {
                for (DomNode node : nodes) if (!values.contains(node.tag())) values.add(node.tag());
            }
            case "id" -> {
                for (DomNode node : nodes) {
                    String id = node.attributes.get("id");
                    if (id != null && !id.isBlank()) values.add(id);
                }
                values.add("missing-id");
            }
            case "class" -> values.addAll(List.of("field", "primary", "link", "note", "tool", "missing"));
            case "css-simple" -> values.addAll(List.of("button", "button.primary", "input#email", "a.link", "#cancel", ".tool"));
            case "xpath-abs" -> {
                for (DomNode node : nodes) if (node.xpath != null && !node.xpath.isBlank()) values.add(node.xpath);
            }
            case "section" -> {
                for (Section section : SectionDetector.detect(root, Platform.BROWSER)) {
                    if (!section.title().isBlank()) values.add(section.title());
                    values.add(section.id());
                }
            }
            default -> {
            }
        }
        return values;
    }

    private static List<String> live(DomNode root, String kind, String value) {
        List<String> hits = new ArrayList<>();
        String needle = value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        for (DomNode node : elements(root)) {
            boolean keep = switch (kind) {
                case "role" -> Query.roleOf(node).equalsIgnoreCase(value.trim());
                case "role-name" -> roleName(node, value);
                case "text" -> tokens(text(node).toLowerCase(Locale.ROOT)).contains(needle);
                case "text-exact" -> text(node).equalsIgnoreCase(needle) || name(node).equals(needle);
                case "tag" -> node.tag().equals(needle);
                case "id" -> value.trim().equals(attr(node, "id"));
                case "class" -> classes(node).contains(needle);
                case "css-simple" -> css(node, value);
                case "xpath-abs" -> value.trim().equals(node.xpath);
                case "section" -> section(root, node, value);
                default -> false;
            };
            if (keep) hits.add(node.id());
        }
        return hits;
    }

    private static boolean roleName(DomNode node, String value) {
        int split = value.indexOf('\t');
        String role = split < 0 ? value : value.substring(0, split);
        String expected = split < 0 ? "" : value.substring(split + 1).trim().toLowerCase(Locale.ROOT);
        if (!Query.roleOf(node).equalsIgnoreCase(role.trim())) return false;
        String accessible = node.accessibleName == null ? "" : node.accessibleName.trim().toLowerCase(Locale.ROOT);
        if (!accessible.isBlank()) return accessible.equals(expected);
        for (AffinityScorer.Binding binding : AffinityScorer.bind(node == null ? null : top(node)).labels()) {
            if (binding.target() == node && text(binding.label()).equalsIgnoreCase(expected)) return true;
        }
        return expected.isBlank();
    }

    private static boolean css(DomNode node, String selector) {
        String raw = selector.trim();
        if (raw.contains(" ") || raw.contains(">") || raw.contains(",") || raw.contains("[")) return false;
        String tag = "";
        String id = "";
        List<String> classes = new ArrayList<>();
        int i = 0;
        if (Character.isLetter(raw.charAt(0)) || raw.charAt(0) == '*') {
            int j = 1;
            while (j < raw.length() && (Character.isLetterOrDigit(raw.charAt(j)) || raw.charAt(j) == '-' || raw.charAt(j) == '_')) j++;
            tag = raw.substring(0, j).toLowerCase(Locale.ROOT);
            i = j;
        }
        while (i < raw.length()) {
            char mark = raw.charAt(i);
            if (mark != '#' && mark != '.') return false;
            int j = i + 1;
            while (j < raw.length() && (Character.isLetterOrDigit(raw.charAt(j)) || raw.charAt(j) == '-' || raw.charAt(j) == '_')) j++;
            if (j == i + 1) return false;
            if (mark == '#') id = raw.substring(i + 1, j);
            else classes.add(raw.substring(i + 1, j).toLowerCase(Locale.ROOT));
            i = j;
        }
        if (!tag.isBlank() && !"*".equals(tag) && !node.tag().equals(tag)) return false;
        if (!id.isBlank() && !id.equals(attr(node, "id"))) return false;
        for (String token : classes) if (!classes(node).contains(token)) return false;
        return true;
    }

    private static boolean section(DomNode root, DomNode node, String value) {
        if (node.sectionPath == null) return false;
        String needle = value.trim().toLowerCase(Locale.ROOT);
        for (Section section : SectionDetector.detect(root, Platform.BROWSER)) {
            boolean named = section.id().equals(value.trim()) || section.title().trim().toLowerCase(Locale.ROOT).equals(needle);
            if (!named) continue;
            for (String id : node.sectionPath.split("/")) if (id.equals(section.id())) return true;
        }
        return false;
    }

    private static DomNode top(DomNode node) {
        DomNode cursor = node;
        while (cursor.parent != null) cursor = cursor.parent;
        return cursor;
    }

    private static String name(DomNode node) {
        return node.accessibleName == null ? "" : node.accessibleName.trim().toLowerCase(Locale.ROOT);
    }

    private static String text(DomNode node) {
        String value = HtmlTree.textOf(node);
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder one = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isLetterOrDigit(ch)) one.append(ch);
            else if (one.length() > 0) {
                tokens.add(one.toString());
                one.setLength(0);
            }
        }
        if (one.length() > 0) tokens.add(one.toString());
        return tokens;
    }

    private static List<String> classes(DomNode node) {
        List<String> tokens = new ArrayList<>();
        String raw = attr(node, "class").toLowerCase(Locale.ROOT);
        for (String token : raw.split("\\s+")) if (!token.isBlank()) tokens.add(token);
        return tokens;
    }

    private static String attr(DomNode node, String name) {
        String value = node.attributes.get(name);
        return value == null ? "" : value.trim();
    }

    private static List<DomNode> elements(DomNode node) {
        List<DomNode> nodes = new ArrayList<>();
        walk(node, nodes);
        return nodes;
    }

    private static void walk(DomNode node, List<DomNode> nodes) {
        if (node == null) return;
        if (node.nodeType == 1) nodes.add(node);
        for (DomNode child : node.children) walk(child, nodes);
        for (DomNode shadow : node.shadowRoots) walk(shadow, nodes);
    }
}
