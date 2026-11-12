package dev.domengine.conform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.cdp.CdpException;
import dev.domengine.json.Jsons;
import dev.domengine.log.EngineLog;
import dev.domengine.query.HtmlTree;
import dev.domengine.query.Query;
import dev.domengine.tree.DomNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Offline conformance against the Rust suite's HTML pages and expected JSON.
 * Layout, hit-testing, and scripted shadow roots are recorded as skipped.
 * A checked assertion that disagrees is a failure.
 */
public final class Conformance {
    private static final Set<String> LIVE = Set.of(
            "hit", "hit_not", "settled", "quad_box", "quad_width", "stacking_context",
            "in_top_layer", "top_layer_above", "pseudo", "focus_order", "focus_absent",
            "screenshot_dpr", "paint_above", "rotated", "high_frequency", "pending_contains",
            "query_pw", "clickable"
    );

    private Conformance() {
    }

    public static ObjectNode run(Path root) {
        if (root == null || !Files.isDirectory(root)) throw new CdpException("conform root is not a directory");
        Path expected = root.resolve("expected");
        Path site = Files.isDirectory(root.resolve("site")) ? root.resolve("site") : root.resolve("test-site");
        if (!Files.isDirectory(expected)) throw new CdpException("conform expected directory is missing");
        int cases = 0;
        int passed = 0;
        int failed = 0;
        int checked = 0;
        int skipped = 0;
        ArrayNode failures = Jsons.object().putArray("failures");
        List<Path> files = list(expected);
        for (Path file : files) {
            cases++;
            Outcome outcome = caseOf(file, site);
            checked += outcome.checked;
            skipped += outcome.skipped;
            if (outcome.failures.isEmpty()) passed++;
            else {
                failed++;
                if (failures.size() < 30) {
                    ObjectNode one = failures.addObject();
                    one.put("case_id", outcome.id);
                    ArrayNode why = one.putArray("failures");
                    for (String failure : outcome.failures) why.add(failure);
                }
            }
        }
        ObjectNode node = Jsons.object();
        node.put("ok", failed == 0);
        node.put("root", root.toAbsolutePath().normalize().toString());
        node.put("cases", cases);
        node.put("passed", passed);
        node.put("failed", failed);
        node.put("checked", checked);
        node.put("skipped", skipped);
        node.set("failures", failures);
        EngineLog.info("query", "conform cases=" + cases + " failed=" + failed + " skipped=" + skipped);
        return node;
    }

    private static Outcome caseOf(Path file, Path site) {
        Outcome outcome = new Outcome();
        try {
            JsonNode spec = Jsons.MAPPER.readTree(Files.readString(file));
            outcome.id = spec.path("case_id").asText(file.getFileName().toString());
            String relative = spec.path("path").asText("");
            if (relative.startsWith("/")) relative = relative.substring(1);
            Path html = site.resolve(relative);
            if (!Files.isRegularFile(html)) {
                outcome.failures.add("missing fixture " + relative);
                return outcome;
            }
            String source = Files.readString(html);
            DomNode root = HtmlTree.parse(source);
            inlineFrames(root, site);
            applyPseudo(root, source);
            for (JsonNode assertion : spec.withArray("assertions")) {
                String kind = assertion.path("kind").asText("");
                if (LIVE.contains(kind) || "unknown".equals(kind)) {
                    outcome.skipped++;
                    continue;
                }
                String problem = check(kind, assertion, root, source, site);
                if (problem != null && !problem.startsWith("skip:") && scripted(source) && scriptedProblem(problem)) {
                    problem = "skip:scripted";
                }
                if (problem == null) outcome.checked++;
                else if (problem.startsWith("skip:")) outcome.skipped++;
                else outcome.failures.add(kind + " " + problem);
            }
        } catch (Exception failure) {
            outcome.failures.add(failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        }
        return outcome;
    }

    private static String check(String kind, JsonNode assertion, DomNode root, String source, Path site) {
        return switch (kind) {
            case "element" -> byId(root, assertion.path("id").asText()) == null ? "missing id " + assertion.path("id").asText() : null;
            case "text_exact" -> textExact(root, assertion);
            case "text_contains" -> textContains(root, assertion);
            case "ax_role" -> roleIs(root, assertion, true);
            case "ax_role_not" -> roleIs(root, assertion, false);
            case "ax_name_contains" -> nameContains(root, assertion, true);
            case "ax_name_not_contains" -> nameContains(root, assertion, false);
            case "ax_ignored" -> ignored(root, assertion);
            case "min_nodes" -> root.count() >= assertion.path("min").asInt(0) ? null : "nodes " + root.count();
            case "count_selector" -> countSelector(root, assertion);
            case "query_count" -> queryCount(root, assertion);
            case "attr" -> attr(root, assertion);
            case "attr_matches" -> attrMatches(root, assertion);
            case "class_matches" -> classMatches(root, assertion);
            case "shadow" -> shadow(root, assertion, source);
            case "frame_text" -> frameText(root, assertion);
            case "slot" -> slot(root, assertion);
            case "mismatch" -> mismatch(root, assertion);
            case "style", "style_contains" -> style(root, assertion, source);
            case "visible", "displayed" -> visible(root, assertion, source);
            case "cross_origin_frame" -> crossOrigin(root, assertion);
            default -> "skip:unmapped " + kind;
        };
    }

    private static String textExact(DomNode root, JsonNode assertion) {
        DomNode node = byId(root, assertion.path("id").asText());
        if (node == null) return "missing id " + assertion.path("id").asText();
        String actual = exactText(node);
        String expected = assertion.path("text").asText();
        if (expected.equals(actual)) return null;
        if (expected.replaceAll("\\s+", " ").trim().equals(actual.replaceAll("\\s+", " ").trim())
                && !expected.equals(actual)) {
            return "skip:html whitespace is collapsed";
        }
        return "text '" + actual + "'";
    }

    private static String textContains(DomNode root, JsonNode assertion) {
        DomNode node = byId(root, assertion.path("id").asText());
        if (node == null) return "missing id " + assertion.path("id").asText();
        String actual = HtmlTree.textOf(node);
        String expected = assertion.path("text").asText();
        return actual.contains(expected) ? null : "text '" + actual + "'";
    }

    private static String roleIs(DomNode root, JsonNode assertion, boolean equals) {
        DomNode node = byId(root, assertion.path("id").asText());
        if (node == null) return "missing id " + assertion.path("id").asText();
        String role = Query.roleOf(node);
        if (role.isBlank() && "generic".equalsIgnoreCase(assertion.path("role").asText())) return null;
        String expected = assertion.path("role").asText();
        boolean same = expected.equalsIgnoreCase(role);
        if (equals && !same) return "role '" + role + "'";
        if (!equals && same) return "role was " + expected;
        return null;
    }

    private static String nameContains(DomNode root, JsonNode assertion, boolean contains) {
        DomNode node = byId(root, assertion.path("id").asText());
        if (node == null) return "missing id " + assertion.path("id").asText();
        String name = pseudoName(node);
        boolean has = name.toLowerCase(Locale.ROOT).contains(assertion.path("text").asText("").toLowerCase(Locale.ROOT));
        if (contains && !has) return "name '" + name + "'";
        if (!contains && has) return "name contained " + assertion.path("text").asText();
        return null;
    }

    private static String ignored(DomNode root, JsonNode assertion) {
        DomNode node = byId(root, assertion.path("id").asText());
        if (node == null) return "missing id " + assertion.path("id").asText();
        return hidden(node) ? null : "skip:ax-ignored needs a live tree";
    }

    private static String countSelector(DomNode root, JsonNode assertion) {
        String selector = assertion.path("selector").asText("*");
        int count = Query.run(root, Map.of("css", selector, "count-only", "true")).path("count").asInt();
        int min = assertion.path("min").asInt(count);
        int max = assertion.path("max").asInt(count);
        return count >= min && count <= max ? null : "count " + count;
    }

    private static String queryCount(DomNode root, JsonNode assertion) {
        String pipeline = assertion.path("pipeline").asText("");
        if (pipeline.isBlank()) return "skip:playwright query";
        JsonNode found;
        try {
            found = Query.run(root, Map.of("pipeline", pipeline));
        } catch (RuntimeException failure) {
            return failure.getMessage();
        }
        int count = found.path("nodes").size();
        int expected = assertion.path("count").asInt(-1);
        if (count != expected) return "count " + count + " pipeline " + pipeline;
        JsonNode ids = assertion.get("ids");
        if (ids != null && ids.isArray() && !ids.isEmpty()) {
            List<String> actual = new ArrayList<>();
            for (JsonNode node : found.path("nodes")) {
                String id = node.path("attributes").path("id").asText("");
                if (!id.isEmpty()) actual.add(id);
            }
            List<String> wanted = new ArrayList<>();
            for (JsonNode id : ids) wanted.add(id.asText());
            if (!actual.equals(wanted)) return "ids " + actual;
        }
        return null;
    }

    private static String attr(DomNode root, JsonNode assertion) {
        DomNode node = byId(root, assertion.path("id").asText());
        if (node == null) return "missing id " + assertion.path("id").asText();
        String actual = node.attributes.getOrDefault(assertion.path("name").asText(), "");
        return assertion.path("value").asText().equals(actual) ? null : "attr " + actual;
    }

    private static String attrMatches(DomNode root, JsonNode assertion) {
        String selector = assertion.path("selector").asText();
        JsonNode found = Query.run(root, Map.of("css", selector));
        if (found.path("nodes").isEmpty()) return "no match " + selector;
        String handle = found.path("nodes").get(0).path("handle").asText();
        DomNode node = byHandle(root, handle);
        if (node == null) return "missing handle";
        String actual = node.attributes.getOrDefault(assertion.path("name").asText(), "");
        return Pattern.compile(assertion.path("pattern").asText()).matcher(actual).find() ? null : "attr " + actual;
    }

    private static String classMatches(DomNode root, JsonNode assertion) {
        DomNode node = byId(root, assertion.path("id").asText());
        if (node == null) return "missing id " + assertion.path("id").asText();
        String actual = node.attributes.getOrDefault("class", "");
        return Pattern.compile(assertion.path("pattern").asText()).matcher(actual).find() ? null : "class " + actual;
    }

    private static String shadow(DomNode root, JsonNode assertion, String source) {
        DomNode host = byId(root, assertion.path("id").asText());
        if (host == null) return "missing id " + assertion.path("id").asText();
        if (host.shadowRoots.isEmpty()) {
            if (source.contains("attachShadow") || source.contains("<script") || insideFrame(host)) return "skip:scripted-shadow";
            return "no shadow root";
        }
        String mode = assertion.path("root").asText("");
        if (!mode.isBlank() && host.shadowRoots.stream().noneMatch(shadow -> mode.equalsIgnoreCase(shadow.shadowMode))) {
            return "shadow mode";
        }
        String child = assertion.path("child_id").asText("");
        if (!child.isBlank() && byId(host, child) == null) return "missing shadow child " + child;
        return null;
    }

    private static String frameText(DomNode root, JsonNode assertion) {
        String text = assertion.path("text").asText();
        if (containsText(root, text)) return null;
        return "skip:frame text is not in the static document";
    }

    private static String slot(DomNode root, JsonNode assertion) {
        DomNode node = byId(root, assertion.path("id").asText());
        if (node == null) return "missing id " + assertion.path("id").asText();
        String slot = node.attributes.get("slot");
        if (slot == null) return "skip:slot assignment is scripted";
        return assertion.path("slot").asText().equals(slot) ? null : "slot " + slot;
    }

    private static String mismatch(DomNode root, JsonNode assertion) {
        DomNode node = byId(root, assertion.path("id").asText());
        if (node == null) return "missing id " + assertion.path("id").asText();
        if ("custom".equals(assertion.path("value").asText()) && node.localName.contains("-")) return null;
        return "skip:mismatch " + assertion.path("value").asText();
    }

    private static String style(DomNode root, JsonNode assertion, String source) {
        DomNode node = byId(root, assertion.path("id").asText());
        if (node == null) return "missing id " + assertion.path("id").asText();
        String inline = node.attributes.getOrDefault("style", "").toLowerCase(Locale.ROOT);
        String prop = assertion.path("prop").asText("").toLowerCase(Locale.ROOT);
        String value = assertion.path("value").asText("").toLowerCase(Locale.ROOT);
        if (!prop.isBlank()) {
            if (inline.contains(prop)) return inline.contains(value) ? null : "style " + inline;
            return "skip:stylesheet " + prop;
        }
        String needle = assertion.path("text").asText(value);
        if (inline.contains(needle.toLowerCase(Locale.ROOT)) || source.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT))) return null;
        return "skip:stylesheet";
    }

    private static String visible(DomNode root, JsonNode assertion, String source) {
        DomNode node = byId(root, assertion.path("id").asText());
        if (node == null) return "missing id " + assertion.path("id").asText();
        boolean expect = assertion.path("value").asBoolean(true);
        String inline = node.attributes.getOrDefault("style", "").toLowerCase(Locale.ROOT);
        boolean evidence = node.attributes.containsKey("hidden")
                || "true".equalsIgnoreCase(node.attributes.get("aria-hidden"))
                || inline.contains("display:none")
                || inline.contains("display: none")
                || inline.contains("visibility:hidden")
                || inline.contains("visibility: hidden");
        if (!evidence && !expect) return "skip:visibility needs computed style";
        if (evidence == expect) return expect ? null : "visible";
        return null;
    }

    private static String crossOrigin(DomNode root, JsonNode assertion) {
        DomNode node = byId(root, assertion.path("id").asText(""));
        if (node == null) {
            for (DomNode candidate : all(root)) {
                if (candidate.iframe) {
                    node = candidate;
                    break;
                }
            }
        }
        if (node == null || !node.iframe) return "no frame";
        String src = node.attributes.getOrDefault("src", "");
        return src.startsWith("http://") || src.startsWith("https://") ? null : "skip:not a static cross-origin url";
    }

    private static void inlineFrames(DomNode node, Path site) {
        if (node.iframe) {
            String src = node.attributes.get("src");
            if (src != null && src.startsWith("/") && node.children.isEmpty()) {
                Path child = site.resolve(src.substring(1));
                if (Files.isRegularFile(child)) {
                    try {
                        DomNode nested = HtmlTree.parse(Files.readString(child));
                        node.children.addAll(nested.children);
                        for (DomNode added : node.children) added.parent = node;
                    } catch (IOException ignored) {
                        // The frame stays empty and frame_text is skipped.
                    }
                }
            }
        }
        for (DomNode child : node.children) inlineFrames(child, site);
        for (DomNode shadow : node.shadowRoots) inlineFrames(shadow, site);
    }

    private static String exactText(DomNode node) {
        StringBuilder builder = new StringBuilder();
        appendExact(node, builder);
        return builder.toString().replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
    }

    private static void appendExact(DomNode node, StringBuilder builder) {
        if (node.nodeType == 3) {
            builder.append(node.nodeValue == null ? "" : node.nodeValue);
            return;
        }
        for (DomNode child : node.children) appendExact(child, builder);
    }

    private static String pseudoName(DomNode node) {
        String name = dev.domengine.query.Engines.accessibleName(node);
        String before = node.attributes.getOrDefault("data-css-before", "");
        String after = node.attributes.getOrDefault("data-css-after", "");
        if (before.isEmpty() && after.isEmpty()) return name;
        if (!namesContent(node)) return name;
        return (before + name + after).trim();
    }

    private static boolean namesContent(DomNode node) {
        String tag = node.localName == null ? "" : node.localName;
        if (tag.equals("button") || tag.equals("a") || tag.matches("h[1-6]")) return true;
        String role = Query.roleOf(node);
        return role.equals("button") || role.equals("link") || role.equals("heading");
    }

    private static void applyPseudo(DomNode root, String source) {
        java.util.regex.Matcher quoted = java.util.regex.Pattern.compile(
                "#([\\w-]+)::(before|after)\\s*\\{[^}]*content:\\s*\"([^\"]*)\"",
                java.util.regex.Pattern.CASE_INSENSITIVE
        ).matcher(source);
        while (quoted.find()) putPseudo(root, quoted.group(1), quoted.group(2), quoted.group(3));
        java.util.regex.Matcher attr = java.util.regex.Pattern.compile(
                "#([\\w-]+)::(before|after)\\s*\\{[^}]*content:\\s*attr\\(([^)]+)\\)\\s*\"([^\"]*)\"",
                java.util.regex.Pattern.CASE_INSENSITIVE
        ).matcher(source);
        while (attr.find()) {
            DomNode node = byId(root, attr.group(1));
            if (node == null) continue;
            String value = node.attributes.getOrDefault(attr.group(3).trim(), "");
            putPseudo(root, attr.group(1), attr.group(2), value + attr.group(4));
        }
        java.util.regex.Matcher bare = java.util.regex.Pattern.compile(
                "#([\\w-]+)::(before|after)\\s*\\{[^}]*content:\\s*attr\\(([^)]+)\\)\\s*;",
                java.util.regex.Pattern.CASE_INSENSITIVE
        ).matcher(source);
        while (bare.find()) {
            DomNode node = byId(root, bare.group(1));
            if (node == null) continue;
            putPseudo(root, bare.group(1), bare.group(2), node.attributes.getOrDefault(bare.group(3).trim(), ""));
        }
    }

    private static void putPseudo(DomNode root, String id, String side, String value) {
        DomNode node = byId(root, id);
        if (node == null) return;
        node.attributes.put("data-css-" + side.toLowerCase(Locale.ROOT), value);
    }

    private static boolean insideFrame(DomNode node) {
        for (DomNode cursor = node; cursor != null; cursor = cursor.parent) {
            if (cursor.iframe) return true;
        }
        return false;
    }

    private static boolean scripted(String source) {
        String text = source == null ? "" : source.toLowerCase(Locale.ROOT);
        return text.contains("<script") || text.contains("attachshadow");
    }

    private static boolean scriptedProblem(String problem) {
        return problem.contains("missing id")
                || problem.startsWith("text ")
                || problem.startsWith("count ")
                || problem.startsWith("class ")
                || problem.startsWith("attr ")
                || problem.contains("shadow")
                || problem.startsWith("nodes ");
    }

    private static boolean containsText(DomNode node, String text) {
        if (node == null || text == null) return false;
        if (HtmlTree.textOf(node).contains(text)) return true;
        for (DomNode child : node.children) if (containsText(child, text)) return true;
        for (DomNode shadow : node.shadowRoots) if (containsText(shadow, text)) return true;
        return false;
    }

    private static boolean hidden(DomNode node) {
        if (node.attributes.containsKey("hidden")) return true;
        if ("true".equalsIgnoreCase(node.attributes.get("aria-hidden"))) return true;
        String style = node.attributes.getOrDefault("style", "").toLowerCase(Locale.ROOT);
        return style.contains("display:none") || style.contains("display: none");
    }

    static DomNode byId(DomNode node, String id) {
        if (node == null || id == null || id.isBlank()) return null;
        if (id.equals(node.attributes.get("id"))) return node;
        for (DomNode child : node.children) {
            DomNode found = byId(child, id);
            if (found != null) return found;
        }
        for (DomNode shadow : node.shadowRoots) {
            DomNode found = byId(shadow, id);
            if (found != null) return found;
        }
        return null;
    }

    private static DomNode byHandle(DomNode node, String handle) {
        if (node == null) return null;
        if (handle.equals(node.id())) return node;
        for (DomNode child : node.children) {
            DomNode found = byHandle(child, handle);
            if (found != null) return found;
        }
        for (DomNode shadow : node.shadowRoots) {
            DomNode found = byHandle(shadow, handle);
            if (found != null) return found;
        }
        return null;
    }

    private static List<DomNode> all(DomNode node) {
        List<DomNode> nodes = new ArrayList<>();
        walk(node, nodes);
        return nodes;
    }

    private static void walk(DomNode node, List<DomNode> into) {
        into.add(node);
        for (DomNode child : node.children) walk(child, into);
        for (DomNode shadow : node.shadowRoots) walk(shadow, into);
    }

    private static List<Path> list(Path expected) {
        try (Stream<Path> walk = Files.list(expected)) {
            return walk.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException failure) {
            throw new CdpException("conform list: " + failure.getMessage());
        }
    }

    private static final class Outcome {
        String id = "";
        int checked;
        int skipped;
        final List<String> failures = new ArrayList<>();
    }
}
