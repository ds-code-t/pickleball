package tools.dscode.browser.proto;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source Map v3 resolution (https://tc39.es/ecma426/) for error locations, in repo code with no dependency. The
 * script and its map are fetched through the page's own network by the caller's fetcher (a page {@code fetch()}
 * over CDP or BiDi), so the map is resolved exactly when the page could load it. Failures keep the original location.
 */
public final class SourceMaps {
    private static final Pattern MAP_URL = Pattern.compile("[#@]\\s*sourceMappingURL=(\\S+)\\s*$", Pattern.MULTILINE);
    private static final String B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    private final Map<String, Parsed> cache = new ConcurrentHashMap<>();
    private final Function<String, String> fetcher;

    /** Original position: source URL, 1-based line and column, and the symbol name when the map names one. */
    public record Position(String source, int line, int column, String name) {
    }

    record Segment(int column, int source, int line, int sourceColumn, int name) {
    }

    record Parsed(List<String> sources, List<String> names, List<List<Segment>> lines) {
    }

    public SourceMaps(Function<String, String> fetcher) {
        this.fetcher = fetcher;
    }

    /** {@code line} and {@code column} are 1-based generated positions. Null when there is no usable map. */
    public Position resolve(String scriptUrl, int line, int column) {
        if (scriptUrl == null || scriptUrl.isBlank() || line < 1) return null;
        Parsed parsed = cache.computeIfAbsent(scriptUrl, this::load);
        if (parsed == null || parsed == EMPTY) return null;
        return lookup(parsed, line, column);
    }

    private static final Parsed EMPTY = new Parsed(List.of(), List.of(), List.of());

    private Parsed load(String scriptUrl) {
        try {
            String script = fetcher.apply(scriptUrl);
            if (script == null) return EMPTY;
            Matcher m = MAP_URL.matcher(script);
            String ref = null;
            while (m.find()) ref = m.group(1);
            if (ref == null || ref.startsWith("data:")) return EMPTY;
            String mapUrl = URI.create(scriptUrl).resolve(ref).toString();
            String text = fetcher.apply(mapUrl);
            if (text == null || text.isBlank()) return EMPTY;
            JsonNode map = ProtoJson.MAPPER.readTree(text);
            List<String> sources = new ArrayList<>();
            String root = map.path("sourceRoot").asText("");
            for (JsonNode s : map.path("sources")) {
                String source = root + s.asText();
                try {
                    source = URI.create(mapUrl).resolve(source).toString();
                } catch (IllegalArgumentException ignored) {
                    // Keep the raw name.
                }
                sources.add(source);
            }
            List<String> names = new ArrayList<>();
            map.path("names").forEach(n -> names.add(n.asText()));
            return new Parsed(sources, names, decode(map.path("mappings").asText("")));
        } catch (Exception failure) {
            return EMPTY;
        }
    }

    static List<List<Segment>> decode(String mappings) {
        List<List<Segment>> lines = new ArrayList<>();
        int source = 0, sourceLine = 0, sourceColumn = 0, name = 0;
        for (String group : mappings.split(";", -1)) {
            List<Segment> segments = new ArrayList<>();
            int column = 0;
            if (!group.isEmpty()) {
                for (String raw : group.split(",")) {
                    if (raw.isEmpty()) continue;
                    int[] v = vlq(raw);
                    column += v[0];
                    if (v.length >= 4) {
                        source += v[1];
                        sourceLine += v[2];
                        sourceColumn += v[3];
                        int nm = -1;
                        if (v.length >= 5) {
                            name += v[4];
                            nm = name;
                        }
                        segments.add(new Segment(column, source, sourceLine, sourceColumn, nm));
                    }
                }
            }
            lines.add(segments);
        }
        return lines;
    }

    static int[] vlq(String text) {
        List<Integer> out = new ArrayList<>();
        int value = 0, shift = 0;
        for (int i = 0; i < text.length(); i++) {
            int digit = B64.indexOf(text.charAt(i));
            if (digit < 0) throw new IllegalArgumentException("bad VLQ " + text);
            boolean more = (digit & 32) != 0;
            value += (digit & 31) << shift;
            if (more) {
                shift += 5;
            } else {
                boolean negative = (value & 1) == 1;
                int n = value >> 1;
                out.add(negative ? -n : n);
                value = 0;
                shift = 0;
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    static Position lookup(Parsed parsed, int line, int column) {
        if (line - 1 >= parsed.lines().size()) return null;
        List<Segment> segments = parsed.lines().get(line - 1);
        Segment best = null;
        for (Segment s : segments) {
            if (s.column() <= column - 1) best = s;
            else break;
        }
        if (best == null && !segments.isEmpty()) best = segments.get(0);
        if (best == null || best.source() >= parsed.sources().size()) return null;
        String name = best.name() >= 0 && best.name() < parsed.names().size() ? parsed.names().get(best.name()) : null;
        return new Position(parsed.sources().get(best.source()), best.line() + 1, best.sourceColumn() + 1, name);
    }
}
