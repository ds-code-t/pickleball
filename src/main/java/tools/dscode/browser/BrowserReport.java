package tools.dscode.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * E9: the "Browser" section of a run report, read from the run's browser-events NDJSON spill: vitals per
 * navigation, the slowest requests, failed requests and JS errors. URLs, timings and messages only; no payloads.
 */
public final class BrowserReport {
    private static final ObjectMapper JSON = new ObjectMapper();

    private BrowserReport() {
    }

    /** Empty when the run folder has no browser-events spill. */
    public static Map<String, Object> summarize(Path runFolder, int slowest) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        if (runFolder == null || !Files.isDirectory(runFolder)) return out;
        List<Path> spills;
        try (Stream<Path> files = Files.list(runFolder)) {
            spills = files.filter(p -> p.getFileName().toString().startsWith("browser-events-") && p.getFileName().toString().contains(".ndjson")).sorted().toList();
        }
        if (spills.isEmpty()) return out;
        Map<String, Map<String, Object>> vitals = new LinkedHashMap<>();
        List<Map<String, Object>> requests = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        for (Path spill : spills) {
            for (String line : Files.readAllLines(spill)) {
                if (line.isBlank()) continue;
                JsonNode row;
                try {
                    row = JSON.readTree(line);
                } catch (IOException torn) {
                    continue;
                }
                String kind = row.path("kind").asText();
                String subkind = row.path("subkind").asText();
                if ("perf".equals(kind) && "vital".equals(subkind)) {
                    Map<String, Object> nav = vitals.computeIfAbsent(row.path("url").asText(), u -> new LinkedHashMap<>(Map.of("url", u)));
                    nav.put(row.path("metric").asText(), row.path("value").isNumber() ? row.path("value").numberValue() : row.path("value").asText());
                } else if ("net".equals(kind) && "finished".equals(subkind) && row.has("durationMs")) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("url", row.path("url").asText());
                    r.put("durationMs", row.path("durationMs").asLong());
                    if (row.has("bytes")) r.put("bytes", row.path("bytes").asLong());
                    requests.add(r);
                } else if ("net".equals(kind) && "failed".equals(subkind)) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("url", row.path("url").asText());
                    r.put("error", row.path("text").asText());
                    failed.add(r);
                } else if ("error".equals(kind)) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("text", first(row.path("text").asText()));
                    r.put("url", row.path("url").asText());
                    errors.add(r);
                }
            }
        }
        requests.sort(Comparator.comparingLong((Map<String, Object> r) -> (Long) r.get("durationMs")).reversed());
        out.put("vitals", new ArrayList<>(vitals.values()));
        out.put("slowestRequests", requests.subList(0, Math.min(slowest, requests.size())));
        out.put("failedRequests", failed);
        out.put("jsErrors", errors);
        return out;
    }

    private static String first(String text) {
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl);
    }

    /** The section as HTML (for the run's workbench folder). */
    @SuppressWarnings("unchecked")
    public static String html(Map<String, Object> summary) {
        StringBuilder h = new StringBuilder("<section class=\"browser\"><h2>Browser</h2>");
        h.append("<h3>Vitals per navigation</h3><table><tr><th>URL</th><th>LCP ms</th><th>CLS</th><th>INP ms</th><th>FCP ms</th><th>TTFB ms</th></tr>");
        for (Map<String, Object> nav : (List<Map<String, Object>>) summary.getOrDefault("vitals", List.of())) {
            h.append("<tr><td>").append(esc(nav.get("url"))).append("</td>");
            for (String m : List.of("lcp", "cls", "inp", "fcp", "ttfb")) h.append("<td>").append(esc(nav.getOrDefault(m, ""))).append("</td>");
            h.append("</tr>");
        }
        h.append("</table><h3>Slowest requests</h3><table><tr><th>URL</th><th>ms</th></tr>");
        for (Map<String, Object> r : (List<Map<String, Object>>) summary.getOrDefault("slowestRequests", List.of())) {
            h.append("<tr><td>").append(esc(r.get("url"))).append("</td><td>").append(esc(r.get("durationMs"))).append("</td></tr>");
        }
        h.append("</table><h3>Failed requests</h3><table><tr><th>URL</th><th>Error</th></tr>");
        for (Map<String, Object> r : (List<Map<String, Object>>) summary.getOrDefault("failedRequests", List.of())) {
            h.append("<tr><td>").append(esc(r.get("url"))).append("</td><td>").append(esc(r.get("error"))).append("</td></tr>");
        }
        h.append("</table><h3>JS errors</h3><table><tr><th>Error</th><th>URL</th></tr>");
        for (Map<String, Object> r : (List<Map<String, Object>>) summary.getOrDefault("jsErrors", List.of())) {
            h.append("<tr><td>").append(esc(r.get("text"))).append("</td><td>").append(esc(r.get("url"))).append("</td></tr>");
        }
        return h.append("</table></section>").toString();
    }

    private static String esc(Object value) {
        return String.valueOf(value == null ? "" : value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
