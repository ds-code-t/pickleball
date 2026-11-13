package dev.domengine.premap;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.image.TemplateMatch;
import dev.domengine.snapshot.SnapshotContext;
import dev.domengine.snapshot.SnapshotOptions;
import dev.domengine.tree.DomNode;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Offline lookups read the pre-map. {@link #installed()} and {@link #PASS_THROUGH}
 * still answer nothing. {@code current} stays empty until Phase 7.
 * G2's {@code RelationTerm} is not referenced here.
 */
public final class PreMapLookup {
    public static final PreMapLookup PASS_THROUGH = new PreMapLookup(true, null);
    private static final IdentityHashMap<DomNode, PreMap> TREES = new IdentityHashMap<>();
    private static final ConcurrentHashMap<String, PreMap> SESSIONS = new ConcurrentHashMap<>();

    private final boolean passThrough;
    private final PreMap bound;

    private PreMapLookup(boolean passThrough, PreMap bound) {
        this.passThrough = passThrough;
        this.bound = bound;
    }

    public static PreMapLookup installed() {
        return PASS_THROUGH;
    }

    public static void resetForTests() {
        synchronized (TREES) {
            TREES.clear();
        }
        SESSIONS.clear();
    }

    public static void store(String sessionId, PreMap map) {
        if (sessionId == null || sessionId.isBlank() || map == null) return;
        SESSIONS.put(sessionId, map);
    }

    /** Caches an offline tree by identity and fills indexes on first use. */
    public static PreMapLookup forTree(DomNode root) {
        if (root == null) return PASS_THROUGH;
        PreMap map;
        synchronized (TREES) {
            map = TREES.computeIfAbsent(root, key -> PreMapBuilder.build(
                    key, SnapshotContext.offline(key, new SnapshotOptions(), "")));
        }
        return new PreMapLookup(false, map);
    }

    public Optional<PreMap> current(String sessionId, String docVersion) {
        return Optional.empty();
    }

    public Optional<List<String>> candidates(String clauseKind, String value) {
        if (passThrough || bound == null || !indexed(bound)) return Optional.empty();
        String kind = clauseKind == null ? "" : clauseKind.trim().toLowerCase(Locale.ROOT);
        String needle = value == null ? "" : value;
        return switch (kind) {
            case "role" -> listed(bound.byRole().get(norm(needle)));
            case "role-name" -> listed(bound.byRoleName().get(roleName(needle)));
            case "text" -> listed(bound.byTextToken().get(norm(needle)));
            case "text-exact" -> listed(bound.byTextToken().get("\u0002" + norm(needle)));
            case "tag" -> listed(bound.byTag().get(norm(needle)));
            case "id" -> listed(bound.byId().get(needle.trim()));
            case "class" -> listed(bound.byClassName().get(norm(needle)));
            case "xpath-abs" -> listed(bound.byXpath().get(needle.trim()));
            case "label" -> Optional.of(labels(bound, needle));
            case "section" -> Optional.of(section(bound, needle));
            case "css-simple" -> css(bound, needle);
            case "image" -> Optional.of(image(bound, needle));
            default -> Optional.empty();
        };
    }

    public boolean passThrough() {
        return passThrough;
    }

    static PreMapLookup bound(PreMap map) {
        return new PreMapLookup(false, map);
    }

    private static boolean indexed(PreMap map) {
        return !map.byRole().isEmpty() || !map.byTag().isEmpty();
    }

    private static Optional<List<String>> listed(List<String> handles) {
        return Optional.of(handles == null ? List.of() : List.copyOf(handles));
    }

    private static String norm(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String roleName(String value) {
        int split = value.indexOf('\t');
        if (split < 0) return norm(value);
        return norm(value.substring(0, split)) + "\t" + norm(value.substring(split + 1));
    }

    private static List<String> labels(PreMap map, String needle) {
        Set<String> targets = new LinkedHashSet<>();
        String exact = "\u0002" + norm(needle);
        List<String> textHits = map.byTextToken().get(exact);
        for (PreMap.LabelRef label : map.labels()) {
            if (label.label().equals(needle.trim()) || (textHits != null && textHits.contains(label.label()))) {
                if (!label.target().isBlank()) targets.add(label.target());
            }
        }
        return List.copyOf(targets);
    }

    private static List<String> section(PreMap map, String needle) {
        Set<String> handles = new LinkedHashSet<>();
        addAll(handles, map.bySectionTitle().get("#" + needle.trim()));
        addAll(handles, map.bySectionTitle().get(norm(needle)));
        return List.copyOf(handles);
    }

    private static Optional<List<String>> css(PreMap map, String selector) {
        Simple simple = Simple.parse(selector);
        if (simple == null) return Optional.empty();
        List<String> hits = null;
        if (!simple.tag.isBlank() && !"*".equals(simple.tag)) hits = intersect(hits, map.byTag().get(simple.tag));
        if (!simple.id.isBlank()) hits = intersect(hits, map.byId().get(simple.id));
        for (String token : simple.classes) hits = intersect(hits, map.byClassName().get(token));
        if (hits == null) return Optional.of(List.of());
        return Optional.of(List.copyOf(hits));
    }

    private static List<String> image(PreMap map, String needle) {
        ParsedImage parsed = ParsedImage.parse(needle);
        if (parsed == null) return List.of();
        TemplateMatch.Features template = new TemplateMatch.Features(parsed.dhash, parsed.mean, 0);
        List<String> hits = new ArrayList<>();
        JsonNode nodes = map.image().path("nodes");
        if (!nodes.isObject()) return hits;
        nodes.fields().forEachRemaining(entry -> {
            JsonNode one = entry.getValue();
            long dhash = hex(one.path("dhash").asText("0"));
            double mean = one.path("mean").asDouble();
            if (TemplateMatch.prefilter(new TemplateMatch.Features(dhash, mean, one.path("stddev").asDouble()), template)) {
                hits.add(entry.getKey());
            }
        });
        return hits;
    }

    private static List<String> intersect(List<String> left, List<String> right) {
        if (right == null) return List.of();
        if (left == null) return right;
        List<String> hits = new ArrayList<>();
        for (String handle : left) if (right.contains(handle)) hits.add(handle);
        return hits;
    }

    private static void addAll(Set<String> into, List<String> handles) {
        if (handles != null) into.addAll(handles);
    }

    private static long hex(String value) {
        try {
            return Long.parseUnsignedLong(value.startsWith("0x") || value.startsWith("0X") ? value.substring(2) : value, 16);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private record Simple(String tag, String id, List<String> classes) {
        static Simple parse(String selector) {
            if (selector == null) return null;
            String raw = selector.trim();
            if (raw.isEmpty() || raw.indexOf(' ') >= 0 || raw.indexOf('>') >= 0 || raw.indexOf('+') >= 0
                    || raw.indexOf('~') >= 0 || raw.indexOf(',') >= 0 || raw.indexOf(':') >= 0 || raw.indexOf('[') >= 0) {
                return null;
            }
            String tag = "";
            String id = "";
            List<String> classes = new ArrayList<>();
            int i = 0;
            if (Character.isLetter(raw.charAt(0)) || raw.charAt(0) == '*') {
                int j = 1;
                while (j < raw.length()) {
                    char ch = raw.charAt(j);
                    if (!Character.isLetterOrDigit(ch) && ch != '-' && ch != '_') break;
                    j++;
                }
                tag = raw.substring(0, j).toLowerCase(Locale.ROOT);
                i = j;
            }
            while (i < raw.length()) {
                char mark = raw.charAt(i);
                if (mark != '#' && mark != '.') return null;
                int j = i + 1;
                while (j < raw.length()) {
                    char ch = raw.charAt(j);
                    if (!Character.isLetterOrDigit(ch) && ch != '-' && ch != '_') break;
                    j++;
                }
                if (j == i + 1) return null;
                String token = raw.substring(i + 1, j);
                if (mark == '#') id = token;
                else classes.add(token.toLowerCase(Locale.ROOT));
                i = j;
            }
            if (tag.isEmpty() && id.isEmpty() && classes.isEmpty()) return null;
            return new Simple(tag, id, classes);
        }
    }

    private record ParsedImage(long dhash, double mean) {
        static ParsedImage parse(String raw) {
            if (raw == null || raw.isBlank()) return null;
            String[] parts = raw.trim().split("@", 2);
            try {
                long dhash = Long.parseUnsignedLong(parts[0].startsWith("0x") ? parts[0].substring(2) : parts[0], 16);
                double mean = parts.length == 2 ? Double.parseDouble(parts[1]) : 0;
                return new ParsedImage(dhash, mean);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }
}
