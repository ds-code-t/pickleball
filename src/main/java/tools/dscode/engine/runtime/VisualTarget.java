package tools.dscode.engine.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.query.HtmlTree;
import dev.domengine.snapshot.IdentityChain;
import dev.domengine.tree.DomNode;
import tools.dscode.engine.match.GlueStep;
import tools.dscode.engine.match.StepMatch;
import tools.dscode.engine.match.StepRegistry;
import tools.dscode.store.StoreDatabase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A step with {@code steps.visual_ref} must match its regex and the artifact's
 * identity chain. A missing artifact is an error, not a skip.
 */
public final class VisualTarget {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z0-9_.-]+)}}");

    private VisualTarget() {
    }

    public static void beforeRun(Path project, String uri, int line, String stepText, StepRegistry registry) {
        String ref = StepRows.visualRef(project, uri, line);
        if (ref == null || ref.isBlank()) return;
        play(project, ref, stepText, registry, null);
    }

    public static void play(Path project, String artifactId, String stepText, StepRegistry registry, Map<String, String> values) {
        String substituted = substitute(stepText, values);
        String pattern = null;
        if (registry != null) {
            StepMatch match = registry.matchNative(substituted);
            if (match == null || !match.matched()) throw mismatch();
            GlueStep step = match.step();
            if (step != null) pattern = step.pattern();
        }
        if (pattern != null && !pattern.isBlank() && !matches(pattern, substituted)) throw mismatch();
        replay(project, artifactId);
    }

    /** Explicit pattern. Used when the caller already has the regex and a value map. */
    public static void play(Path project, String artifactId, String stepText, String pattern, Map<String, String> values) {
        String substituted = substitute(stepText, values);
        if (pattern != null && !pattern.isBlank() && !matches(pattern, substituted)) throw mismatch();
        replay(project, artifactId);
    }

    static String substitute(String text, Map<String, String> values) {
        if (text == null || text.isEmpty()) return "";
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = null;
            if (values != null && values.containsKey(name)) replacement = values.get(name);
            if (replacement == null) replacement = fromParsingMap(name);
            if (replacement == null) replacement = matcher.group(0);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String fromParsingMap(String name) {
        try {
            Object value = tools.dscode.common.mappings.ParsingMap.getFromRunningParsingMapCaseInsensitive(name);
            return value == null ? null : String.valueOf(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean matches(String pattern, String text) {
        try {
            return Pattern.compile(pattern).matcher(text).find();
        } catch (PatternSyntaxException ignored) {
            return false;
        }
    }

    private static void replay(Path project, String artifactId) {
        Artifact artifact = load(project, artifactId);
        if (artifact == null || artifact.path == null || artifact.path.isBlank()) {
            throw failure(
                    "error: visual artifact " + artifactId + " is missing",
                    "fix: keep the dom or screenshot artifact and set steps.visual_ref to that artifact id");
        }
        String kind = artifact.kind == null ? "" : artifact.kind.trim().toLowerCase(Locale.ROOT);
        if (!"dom".equals(kind) && !"screenshot".equals(kind)) {
            throw failure(
                    "error: visual artifact " + artifactId + " must be dom or screenshot",
                    "fix: store an artifact whose kind is dom or screenshot");
        }
        Path file = Path.of(artifact.path);
        if (!file.isAbsolute() && project != null) file = project.resolve(artifact.path);
        if (!Files.isRegularFile(file)) {
            throw failure(
                    "error: visual artifact " + artifactId + " is missing",
                    "fix: keep the dom or screenshot artifact and set steps.visual_ref to that artifact id");
        }
        String body;
        try {
            body = Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            throw failure(
                    "error: visual artifact " + artifactId + " has no identity chain",
                    "fix: record the identity chain from the artifact's document");
        }
        JsonNode node;
        try {
            node = JSON.readTree(body);
        } catch (Exception ignored) {
            throw failure(
                    "error: visual artifact " + artifactId + " has no identity chain",
                    "fix: record the identity chain from the artifact's document");
        }
        JsonNode chain = IdentityChain.chainOf(node);
        String html = node.path("html").asText("");
        if (html.isBlank()) html = node.path("source").path("text").asText("");
        if (chain == null || html.isBlank()) {
            throw failure(
                    "error: visual artifact " + artifactId + " has no identity chain",
                    "fix: record the identity chain from the artifact's document");
        }
        DomNode root = HtmlTree.parse(html);
        ObjectNode result = IdentityChain.replay(root, chain);
        if (result == null || "none".equals(result.path("matched").asText("none"))) throw mismatch();
    }

    private static Artifact load(Path project, String artifactId) {
        if (project == null || artifactId == null || artifactId.isBlank()) return null;
        try (StoreDatabase database = StoreDatabase.open(project);
             PreparedStatement statement = database.connection().prepareStatement(
                     "SELECT kind, path FROM artifacts WHERE artifact_id = ?")) {
            statement.setString(1, artifactId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                return new Artifact(rows.getString(1), rows.getString(2));
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private static IllegalStateException mismatch() {
        return failure(
                "error: visual target did not match",
                "fix: record the identity chain from the artifact's document");
    }

    private static IllegalStateException failure(String error, String fix) {
        return new IllegalStateException(error + "\n" + fix);
    }

    private record Artifact(String kind, String path) {
    }
}
