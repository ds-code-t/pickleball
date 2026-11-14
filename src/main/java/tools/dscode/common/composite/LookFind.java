package tools.dscode.common.composite;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.query.HtmlTree;
import dev.domengine.query.Query;
import dev.domengine.tree.DomNode;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.WrapsDriver;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Adapter-side bridge: reads page source through WebDriver.
 * Applies a parsed {@code with look} clause on the element-find path.
 * XPath still owns role, text, state, and attribute grammar. The look stage
 * is the same engine string {@link SelectorTranslator} already builds.
 */
public final class LookFind {
    private LookFind() {
    }

    public static boolean present(ElementMatch match) {
        return match != null && match.lookClause != null && !match.lookClause.isBlank();
    }

    /**
     * Keeps candidates the Java engine accepts for this look clause.
     * Unset, {@code auto}, and {@code off} fail. Selenium is not asked to evaluate look.
     */
    public static List<WebElement> retain(SearchContext context, List<WebElement> candidates, ElementMatch match) {
        if (!present(match)) {
            return candidates == null ? List.of() : candidates;
        }
        List<WebElement> found = candidates == null ? List.of() : candidates;
        String pipeline = pipeline(match);
        if (pipeline == null || !pipeline.contains("look=")) {
            throw new IllegalStateException(
                    "error: could not read the look clause. fix: use with look <prop> <predicate>. " + match.lookClause
            );
        }
        EngineQuery query = EngineQuery.of(pipeline);
        if (!CompositeMode.current().required()) {
            String why = CompositeMode.current().disabled()
                    ? "pkb_composite=off cannot evaluate "
                    : "the library path cannot evaluate ";
            throw new IllegalStateException(
                    "error: a with-look step needs the Java engine. fix: set pkb_composite=on. " + why + query.text()
            );
        }
        if (query.seleniumFallback() != null) {
            throw new IllegalStateException(
                    "error: selenium cannot evaluate look. fix: keep the look stage on the Java engine query. " + query.text()
            );
        }
        CompositeLog.info("query", "engine " + query.text());
        CompositeLog.trace("query", "forward selector=" + query.text());
        if (found.isEmpty()) {
            return List.of();
        }
        WebDriver driver = driver(context, match);
        if (driver == null) {
            throw new IllegalStateException(
                    "error: look has no document. fix: open a page before a with-look step. " + query.text()
            );
        }
        String html = ElementOperations.pageSource(driver);
        if (html == null || html.isBlank()) {
            throw new IllegalStateException(
                    "error: look has no document. fix: open a page before a with-look step. " + query.text()
            );
        }
        DomNode root = HtmlTree.parse(html);
        stampComputed(root, found, properties(pipeline));
        JsonNode json = Query.run(root, Map.of("pipeline", query.text()));
        return intersect(found, json.path("nodes"));
    }

    private static ElementPhrase phrase(ElementMatch match) {
        String text = null;
        String mode = "equals";
        if (match.defaultText != null && !match.defaultText.isNullOrBlank()) {
            ExecutionDictionary.Op op = match.defaultTextOp;
            if (op == null || op == ExecutionDictionary.Op.EQUALS || op == ExecutionDictionary.Op.DEFAULT) {
                text = match.defaultText.toString();
            } else if (op == ExecutionDictionary.Op.CONTAINS) {
                text = match.defaultText.toString();
                mode = "containing";
            } else if (op == ExecutionDictionary.Op.STARTS_WITH) {
                text = match.defaultText.toString();
                mode = "starting";
            } else if (op == ExecutionDictionary.Op.MATCHES) {
                text = match.defaultText.toString();
                mode = "matching";
            }
        }
        ElementPhrase built = ElementPhrase.of(match.category, text).look(match.lookClause);
        if (text != null) built = built.match(mode);
        return built;
    }

    private static WebDriver driver(SearchContext context, ElementMatch match) {
        if (match.parentPhrase != null) {
            WebDriver owned = match.parentPhrase.getDriver();
            if (owned != null) return owned;
        }
        if (context instanceof WebDriver web) return web;
        if (context instanceof WrapsDriver wraps) return wraps.getWrappedDriver();
        return null;
    }

    private static List<String> properties(String pipeline) {
        int at = pipeline.toLowerCase(Locale.ROOT).indexOf("look=");
        String body = at < 0 ? "" : pipeline.substring(at + "look=".length());
        int cut = indexOfStageEnd(body);
        if (cut >= 0) body = body.substring(0, cut);
        List<String> names = new ArrayList<>();
        int i = 0;
        while (i < body.length()) {
            int start = i;
            while (i < body.length()) {
                char c = body.charAt(i);
                if (!(Character.isLetterOrDigit(c) || c == '-')) break;
                i++;
            }
            if (i > start && i < body.length() && isOperatorStart(body.charAt(i))) {
                names.add(body.substring(start, i).toLowerCase(Locale.ROOT));
            }
            if (i < body.length()) i++;
            else break;
        }
        if (names.contains("contrast") && !names.contains("color")) names.add("color");
        if (names.contains("contrast") && !names.contains("background-color")) names.add("background-color");
        return names;
    }

    private static int indexOfStageEnd(String body) {
        int depth = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && depth > 0) depth--;
            else if (depth == 0 && c == ')') return i;
        }
        return -1;
    }

    private static boolean isOperatorStart(char c) {
        return c == '=' || c == '>' || c == '<' || c == '!' || c == '~' || c == '^' || c == '$';
    }

    /**
     * A stored computed map wins over the style attribute, so an inline style
     * already on the node is left alone. Class and inherited colors are filled
     * from the live element.
     */
    private static void stampComputed(DomNode root, List<WebElement> candidates, List<String> properties) {
        if (properties.isEmpty()) return;
        Map<String, DomNode> byId = new LinkedHashMap<>();
        index(root, byId);
        for (WebElement candidate : candidates) {
            String id = idOf(candidate);
            if (id.isEmpty()) continue;
            DomNode node = byId.get(id);
            if (node == null || !node.computed.isEmpty()) continue;
            Map<String, String> computed = new LinkedHashMap<>();
            for (String property : properties) {
                if (declares(node, property)) continue;
                String value = css(candidate, property);
                if (value != null && !value.isBlank()) computed.put(property, value.trim());
            }
            if (!computed.isEmpty()) node.computed.putAll(computed);
        }
    }

    private static boolean declares(DomNode node, String property) {
        String style = node.attributes.get("style");
        if (style == null || style.isBlank()) return false;
        for (String piece : style.split(";")) {
            String name = piece.trim().toLowerCase(Locale.ROOT);
            int colon = name.indexOf(':');
            if (colon > 0 && name.substring(0, colon).trim().equals(property)) return true;
        }
        return false;
    }

    private static void index(DomNode node, Map<String, DomNode> byId) {
        if (node == null) return;
        String id = node.attributes.get("id");
        if (id != null && !id.isBlank() && !byId.containsKey(id)) byId.put(id, node);
        for (DomNode child : node.children) index(child, byId);
        for (DomNode shadow : node.shadowRoots) index(shadow, byId);
    }

    private static List<WebElement> intersect(List<WebElement> candidates, JsonNode nodes) {
        Set<String> ids = new LinkedHashSet<>();
        List<String> texts = new ArrayList<>();
        if (nodes != null && nodes.isArray()) {
            for (JsonNode node : nodes) {
                String id = node.path("attributes").path("id").asText("");
                if (!id.isEmpty()) ids.add(id);
                else texts.add(normalize(node.path("text").asText("")));
            }
        }
        List<WebElement> kept = new ArrayList<>();
        for (WebElement candidate : candidates) {
            String id = idOf(candidate);
            if (!id.isEmpty()) {
                if (ids.contains(id)) kept.add(candidate);
                continue;
            }
            String text = normalize(ElementReads.text(candidate));
            if (!text.isEmpty() && texts.contains(text)) kept.add(candidate);
        }
        return kept;
    }

    private static String idOf(WebElement candidate) {
        String id = ElementReads.attribute(candidate, "id");
        if (id == null || id.isBlank()) id = ElementReads.domAttribute(candidate, "id");
        return id == null ? "" : id.trim();
    }

    private static String css(WebElement candidate, String property) {
        try {
            return ElementReads.css(candidate, property);
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    /** The engine string a parsed step forwards. Package-visible for the live-find test. */
    static String pipeline(ElementMatch match) {
        if (!present(match)) return "";
        String pipeline = SelectorTranslator.phrase(phrase(match)).pipeline();
        return pipeline == null ? "" : pipeline;
    }
}
