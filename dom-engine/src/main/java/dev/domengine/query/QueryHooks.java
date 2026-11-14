package dev.domengine.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.affinity.AffinityScore;
import dev.domengine.affinity.AffinityScorer;
import dev.domengine.json.Jsons;
import dev.domengine.log.EngineLog;
import dev.domengine.premap.Candidate;
import dev.domengine.premap.OneMatch;
import dev.domengine.premap.OneMatchException;
import dev.domengine.premap.PreMapLookup;
import dev.domengine.tree.DomNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The only query file that calls the pre-map and affinity hooks. */
final class QueryHooks {
    private static final ThreadLocal<PreMapLookup> LOOKUP = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> SESSION_MAP = new ThreadLocal<>();
    private static final ThreadLocal<List<DomNode>> ANCHORS = ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<Map<String, AffinityScore>> SCORES = ThreadLocal.withInitial(LinkedHashMap::new);
    private static final ThreadLocal<String> PREMAP = ThreadLocal.withInitial(() -> "none");
    private static final ThreadLocal<String> RANK = ThreadLocal.withInitial(() -> "distance");
    private static final ThreadLocal<String> ONE = ThreadLocal.withInitial(() -> "ok");

    private QueryHooks() {
    }

    static void reset() {
        LOOKUP.remove();
        SESSION_MAP.remove();
        ANCHORS.remove();
        SCORES.remove();
        PREMAP.remove();
        RANK.remove();
        ONE.remove();
    }

    static void premap(DomNode root, Map<String, String> flags) {
        ANCHORS.set(new ArrayList<>());
        SCORES.set(new LinkedHashMap<>());
        RANK.set("distance");
        ONE.set("ok");
        String session = flags == null ? null : flags.get("session");
        if (session != null && !session.isBlank()) {
            PreMapLookup.noteQuery(flags);
            PreMapLookup.noteRoot(root);
            PreMapLookup lookup = PreMapLookup.installed();
            Optional<dev.domengine.premap.PreMap> map = lookup.current(session, flags.get("doc-version"));
            LOOKUP.set(lookup);
            SESSION_MAP.set(map.isPresent());
            PREMAP.set(map.isPresent() ? PreMapLookup.statusForExplain() : "none");
            return;
        }
        PreMapLookup lookup = root == null ? PreMapLookup.PASS_THROUGH : PreMapLookup.forTree(root);
        LOOKUP.set(lookup);
        SESSION_MAP.set(false);
        PREMAP.set(lookup.passThrough() ? "none" : "hit");
    }

    static void note(List<DomNode> anchors) {
        if (anchors == null || anchors.isEmpty()) return;
        List<DomNode> recorded = ANCHORS.get();
        if (recorded == null) {
            recorded = new ArrayList<>();
            ANCHORS.set(recorded);
        }
        recorded.addAll(anchors);
    }

    static List<DomNode> candidates(Expressions.Stage stage, List<DomNode> hits) {
        if (hits == null || stage == null) return hits;
        PreMapLookup lookup = LOOKUP.get();
        if (lookup == null) return hits;
        String kind = kind(stage);
        if (kind == null) return hits;
        Optional<List<String>> found = lookup.candidates(kind, stage.expr() == null ? "" : stage.expr());
        if (found.isEmpty() || found.get().isEmpty()) return hits;
        List<String> ids = found.get();
        List<DomNode> kept = new ArrayList<>();
        for (DomNode hit : hits) {
            if (ids.contains(hit.id())) kept.add(hit);
        }
        return kept.isEmpty() ? hits : kept;
    }

    static List<DomNode> rank(List<DomNode> hits, List<RelationTerm> terms, Map<String, String> flags) {
        boolean affinity = affinity(flags);
        if (!affinity || hits == null || hits.size() < 2) {
            RANK.set(affinity ? "affinity" : "distance");
            return hits;
        }
        List<DomNode> anchors = ANCHORS.get() == null ? List.of() : List.copyOf(ANCHORS.get());
        List<RelationTerm> used = terms == null ? List.of() : terms;
        Map<String, AffinityScore> scores = new LinkedHashMap<>();
        List<DomNode> ordered = new ArrayList<>(hits);
        ordered.sort((left, right) -> {
            int compared = Double.compare(total(right, anchors, used, scores), total(left, anchors, used, scores));
            if (compared != 0) return compared;
            return Integer.compare(hits.indexOf(left), hits.indexOf(right));
        });
        SCORES.set(scores);
        RANK.set("affinity");
        return ordered;
    }

    static DomNode one(List<DomNode> nodes, List<RelationTerm> terms, Map<String, String> flags) {
        List<DomNode> source = nodes == null ? List.of() : nodes;
        Map<String, AffinityScore> scores = SCORES.get();
        List<Candidate> candidates = new ArrayList<>();
        for (DomNode node : source) {
            AffinityScore score = scores == null ? null : scores.get(node.id());
            Double total = score == null ? null : score.total();
            List<String> negatives = terms == null || terms.isEmpty() ? List.of() : negatives(score);
            candidates.add(Candidate.of(node, total, negatives, 1920, 1080, null));
        }
        boolean act = flags != null && "true".equals(flags.get("__act"));
        OneMatch.Policy policy = act
                ? OneMatch.Policy.act(0.15, Set.of())
                : OneMatch.Policy.resolve(0.15, Set.of());
        try {
            OneMatch.Resolved resolved = OneMatch.resolve(candidates, policy);
            String handle = resolved.chosen().handle();
            for (DomNode node : source) {
                if (handle.equals(node.id())) {
                    ONE.set("ok");
                    return node;
                }
            }
            ONE.set("error");
            throw new OneMatchException("not found", resolved.candidates());
        } catch (OneMatchException failure) {
            ONE.set("error");
            throw failure;
        }
    }

    static void log() {
        EngineLog.info("query", "premap=" + PREMAP.get() + " rank=" + RANK.get() + " one=" + ONE.get());
    }

    static String premapStatus() {
        String value = PREMAP.get();
        return value == null || value.isBlank() ? "none" : value;
    }

    static JsonNode explainRank(Map<String, String> flags) {
        boolean affinity = "affinity".equals(RANK.get())
                || (flags != null && "affinity".equalsIgnoreCase(flags.get("rank")) && "affinity".equals(RANK.get()));
        if (!affinity) return Jsons.MAPPER.getNodeFactory().textNode("distance");
        ObjectNode node = Jsons.object();
        node.put("mode", "affinity");
        ArrayNode scores = node.putArray("scores");
        Map<String, AffinityScore> stored = SCORES.get();
        if (stored != null) {
            for (Map.Entry<String, AffinityScore> entry : stored.entrySet()) {
                ObjectNode one = scores.addObject();
                one.put("handle", entry.getKey());
                one.put("total", entry.getValue().total());
                one.set("parts", Jsons.MAPPER.valueToTree(entry.getValue().parts()));
                ArrayNode reasons = one.putArray("reasons");
                for (String reason : entry.getValue().reasons()) reasons.add(reason);
            }
        }
        return node;
    }

    private static List<String> negatives(AffinityScore score) {
        if (score == null) return List.of();
        List<String> negatives = new ArrayList<>();
        for (String reason : score.reasons()) {
            if (reason.startsWith("negated ")) negatives.add(reason);
        }
        return negatives;
    }

    private static boolean affinity(Map<String, String> flags) {
        if (Boolean.TRUE.equals(SESSION_MAP.get())) return true;
        return flags != null && "affinity".equalsIgnoreCase(flags.get("rank"));
    }

    private static double total(DomNode hit, List<DomNode> anchors, List<RelationTerm> terms, Map<String, AffinityScore> scores) {
        AffinityScore best = null;
        if (anchors.isEmpty()) {
            best = AffinityScorer.score(hit, hit, terms);
        } else {
            for (DomNode anchor : anchors) {
                AffinityScore score = AffinityScorer.score(anchor, hit, terms);
                if (best == null || score.total() > best.total()) best = score;
            }
        }
        if (best == null) best = new AffinityScore(0, Map.of(), List.of());
        scores.put(hit.id(), best);
        return best.total();
    }

    private static String kind(Expressions.Stage stage) {
        String engine = stage.engine();
        String expr = stage.expr() == null ? "" : stage.expr().trim();
        return switch (engine) {
            case "role" -> expr.contains("[name=") ? "role-name" : "role";
            case "text" -> textKind(expr);
            case "css" -> cssKind(expr);
            case "xpath" -> expr.startsWith("/") ? "xpath-abs" : null;
            case "image" -> "image";
            default -> null;
        };
    }

    private static String textKind(String expr) {
        if (expr.startsWith("~=") || expr.startsWith("^=") || expr.startsWith("$=")) return "text";
        if (expr.startsWith("\"") || expr.startsWith("'")) return "text-exact";
        return "text";
    }

    private static String cssKind(String expr) {
        if (expr.startsWith("#") && expr.indexOf('#') == 0 && !expr.contains(" ") && !expr.contains(".") && !expr.contains("[")) {
            return "id";
        }
        if (expr.startsWith(".") && !expr.contains(" ") && expr.lastIndexOf('.') == 0) return "class";
        if (expr.matches("[A-Za-z][A-Za-z0-9-]*")) return "tag";
        if (!expr.isBlank() && !expr.contains(" ") && !expr.contains(">")) return "css-simple";
        return null;
    }
}
