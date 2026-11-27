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
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phrase path for the Control Engine {@code between} relation.
 * The candidate center must lie in the box of the two anchor centers.
 * A missing second anchor is an error. Selenium is not asked to invent that box.
 */
public final class BetweenFind {
    private static final Pattern QUOTED_CATEGORY = Pattern.compile("([\"'])([^\"']+)\\1\\s+([A-Z][A-Za-z]+)");

    private BetweenFind() {
    }

    private record Label(String category, String text) {
    }

    public static List<WebElement> retain(
            SearchContext context,
            List<WebElement> candidates,
            ElementMatch target,
            PhraseData between
    ) {
        if (between == null) {
            return candidates == null ? List.of() : candidates;
        }
        List<ElementMatch> anchors = between.getElementMatches();
        if (anchors == null || anchors.size() < 2) {
            throw new IllegalStateException(
                    "error: between requires two anchors. fix: name both anchors, between the \"A\" Button and the \"B\" Button"
            );
        }
        String pipeline = pipeline(target, between);
        if (pipeline == null || !pipeline.contains("between(")) {
            throw new IllegalStateException(
                    "error: between requires two anchors. fix: name both anchors, between the \"A\" Button and the \"B\" Button"
            );
        }
        EngineQuery query = EngineQuery.of(pipeline);
        if (!CompositeMode.current().required()) {
            String why = CompositeMode.current().disabled()
                    ? "pkb_composite=off cannot evaluate "
                    : "the library path cannot evaluate ";
            throw new IllegalStateException(
                    "error: a between step needs the Java engine. fix: set pkb_composite=on. " + why + query.text()
            );
        }
        if (query.seleniumFallback() != null) {
            throw new IllegalStateException(
                    "error: selenium cannot evaluate between. fix: keep both anchors on the Java engine query. " + query.text()
            );
        }
        CompositeLog.info("query", "engine " + query.text());
        List<WebElement> found = candidates == null ? List.of() : candidates;
        WebDriver driver = driver(context, target);
        if (driver == null) {
            throw new IllegalStateException(
                    "error: between has no document. fix: open a page before a between step. " + query.text()
            );
        }
        String html = ElementOperations.pageSource(driver);
        if (html == null || html.isBlank()) {
            throw new IllegalStateException(
                    "error: between has no document. fix: open a page before a between step. " + query.text()
            );
        }
        DomNode root = HtmlTree.parse(html);
        JsonNode json = Query.run(root, Map.of("pipeline", query.text()));
        if (found.isEmpty()) return List.of();
        return intersect(found, json.path("nodes"));
    }

    /** The engine string a parsed between step forwards. Package-visible for the live-find test. */
    static String pipeline(ElementMatch target, PhraseData between) {
        String pipeline = SelectorTranslator.phrase(phrase(target, between)).pipeline();
        return pipeline == null ? "" : pipeline;
    }

    private static ElementPhrase phrase(ElementMatch target, PhraseData between) {
        List<ElementMatch> anchors = between.getElementMatches();
        ElementMatch first = anchors.get(0);
        ElementMatch second = anchors.get(1);
        ElementPhrase built = described(target)
                .context("between", textOf(first), first.category)
                .anchor(textOf(second), second.category);
        return built;
    }

    private static ElementPhrase described(ElementMatch match) {
        Label label = label(match);
        String text = label.text();
        String mode = "equals";
        if (match != null && match.defaultText != null && !match.defaultText.isNullOrBlank()) {
            ExecutionDictionary.Op op = match.defaultTextOp;
            if (op == ExecutionDictionary.Op.CONTAINS) mode = "containing";
            else if (op == ExecutionDictionary.Op.STARTS_WITH) mode = "starting";
            else if (op == ExecutionDictionary.Op.MATCHES) mode = "matching";
        }
        ElementPhrase built = ElementPhrase.of(label.category(), text);
        if (text != null) built = built.match(mode);
        return built;
    }

    private static String textOf(ElementMatch match) {
        return label(match).text();
    }

    /**
     * The element grammar can take a quoted word as the category and leave the
     * category word that follows the quote off the match. {@code the "Save" Button}
     * is then {@code desc="Save"} with no text. Read the quote back as the text
     * and the following word as the category. A category that is already the
     * word after the quote keeps that category and takes the quote as its text.
     */
    private static Label label(ElementMatch match) {
        String category = match == null ? null : match.category;
        String text = rawText(match);
        if (match == null || text != null) return new Label(category, text);
        List<Label> quoted = quotedLabels(source(match));
        for (Label candidate : quoted) {
            if (candidate.text() != null && candidate.text().equals(category)) return candidate;
        }
        List<Label> sameCategory = new ArrayList<>();
        for (Label candidate : quoted) {
            if (candidate.category() != null && candidate.category().equals(category)) sameCategory.add(candidate);
        }
        if (sameCategory.isEmpty()) return new Label(category, null);
        int ordinal = siblingOrdinal(match, category);
        if (ordinal < 0 || ordinal >= sameCategory.size()) return new Label(category, null);
        return sameCategory.get(ordinal);
    }

    private static List<Label> quotedLabels(String source) {
        List<Label> found = new ArrayList<>();
        if (source == null || source.isBlank()) return found;
        Matcher matcher = QUOTED_CATEGORY.matcher(source);
        while (matcher.find()) {
            found.add(new Label(matcher.group(3), matcher.group(2)));
        }
        return found;
    }

    private static String source(ElementMatch match) {
        if (match.parentPhrase != null && match.parentPhrase.originalText != null
                && !match.parentPhrase.originalText.isBlank()) {
            return match.parentPhrase.originalText;
        }
        return match.fullText == null ? "" : match.fullText;
    }

    private static int siblingOrdinal(ElementMatch match, String category) {
        if (match.parentPhrase == null || match.parentPhrase.getElementMatches() == null) return 0;
        int ordinal = 0;
        for (ElementMatch sibling : match.parentPhrase.getElementMatches()) {
            if (sibling == match) return ordinal;
            if (category != null && category.equals(sibling.category) && rawText(sibling) == null) ordinal++;
        }
        return 0;
    }

    private static String rawText(ElementMatch match) {
        if (match == null || match.defaultText == null || match.defaultText.isNullOrBlank()) return null;
        return match.defaultText.toString();
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

    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
