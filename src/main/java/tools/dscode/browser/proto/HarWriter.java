package tools.dscode.browser.proto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * HAR 1.2 (http://www.softwareishard.com/blog/har-12-spec/) built from browser event rows, in repo code with no
 * dependency. Request, response and finished rows with the same requestId become one entry. Headers in the rows are
 * already redacted; bodies come from the capture store when {@code content=embed}.
 */
public final class HarWriter {
    private HarWriter() {
    }

    public static ObjectNode build(List<JsonNode> rows, String content, String urlFilter, Function<String, JsonNode> bodies) {
        Pattern filter = urlFilter == null || urlFilter.isBlank() ? null
                : Pattern.compile(urlFilter.startsWith("re:") ? urlFilter.substring(3) : globRegex(urlFilter));
        Map<String, ObjectNode[]> byId = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            if (!"net".equals(row.path("kind").asText())) continue;
            String id = row.path("requestId").asText("");
            if (id.isBlank()) continue;
            ObjectNode[] slot = byId.computeIfAbsent(id, k -> new ObjectNode[3]);
            switch (row.path("subkind").asText()) {
                case "request" -> slot[0] = (ObjectNode) row;
                case "response" -> slot[1] = (ObjectNode) row;
                case "finished", "failed" -> slot[2] = (ObjectNode) row;
                default -> { }
            }
        }
        ObjectNode har = ProtoJson.obj();
        ObjectNode log = har.putObject("log");
        log.put("version", "1.2");
        log.putObject("creator").put("name", "pickleball").put("version", "3.0.0");
        log.putArray("pages");
        ArrayNode entries = log.putArray("entries");
        for (Map.Entry<String, ObjectNode[]> e : byId.entrySet()) {
            ObjectNode request = e.getValue()[0];
            ObjectNode response = e.getValue()[1];
            ObjectNode done = e.getValue()[2];
            if (request == null) continue;
            String url = request.path("url").asText("");
            if (url.startsWith("data:")) continue;
            if (filter != null && !filter.matcher(url).find()) continue;
            ObjectNode entry = entries.addObject();
            entry.put("startedDateTime", started(request));
            long total = done == null ? 0 : done.path("durationMs").asLong(0);
            entry.put("time", total);
            ObjectNode req = entry.putObject("request");
            req.put("method", request.path("method").asText("GET")).put("url", url).put("httpVersion", httpVersion(response));
            req.set("cookies", ProtoJson.MAPPER.createArrayNode());
            req.set("headers", pairs(request.path("requestHeaders")));
            req.set("queryString", query(url));
            req.put("headersSize", -1).put("bodySize", request.path("postBytes").asLong(0));
            ObjectNode res = entry.putObject("response");
            res.put("status", response == null ? (done == null ? 0 : done.path("status").asInt(0)) : response.path("status").asInt());
            res.put("statusText", response == null ? "" : response.path("statusText").asText(""));
            res.put("httpVersion", httpVersion(response));
            res.set("cookies", ProtoJson.MAPPER.createArrayNode());
            res.set("headers", pairs(response == null ? null : response.path("responseHeaders")));
            ObjectNode body = res.putObject("content");
            long size = done == null ? 0 : done.path("bytes").asLong(0);
            body.put("size", size).put("mimeType", response == null ? "" : response.path("mime").asText(response.path("text").asText("")));
            if ("embed".equals(content) && bodies != null) {
                JsonNode stored = bodies.apply(e.getKey());
                if (stored != null) {
                    body.put("text", stored.path("body").asText(""));
                    if (stored.path("base64").asBoolean()) body.put("encoding", "base64");
                }
            }
            res.put("redirectURL", "").put("headersSize", -1).put("bodySize", size);
            entry.putObject("cache");
            ObjectNode timings = entry.putObject("timings");
            long ttfb = response == null ? -1 : response.path("ttfbMs").asLong(-1);
            timings.put("send", 0).put("wait", Math.max(ttfb, 0)).put("receive", Math.max(0, total - Math.max(ttfb, 0)));
            if (response != null && response.hasNonNull("remote")) entry.put("serverIPAddress", response.path("remote").asText().replaceAll(":\\d+$", ""));
            if (done != null && "failed".equals(done.path("subkind").asText())) entry.put("_error", done.path("text").asText(""));
            entry.put("_requestId", e.getKey());
        }
        return har;
    }

    private static String started(JsonNode request) {
        if (request.has("startedAt")) return Instant.ofEpochMilli(request.path("startedAt").asLong()).toString();
        try {
            return OffsetDateTime.parse(request.path("at").asText()).toInstant().toString();
        } catch (RuntimeException bad) {
            return Instant.now().toString();
        }
    }

    private static String httpVersion(JsonNode response) {
        String p = response == null ? "" : response.path("protocol").asText("");
        if (p.isBlank()) return "HTTP/1.1";
        return switch (p.toLowerCase(java.util.Locale.ROOT)) {
            case "h2" -> "HTTP/2";
            case "h3" -> "HTTP/3";
            case "http/1.0" -> "HTTP/1.0";
            default -> "HTTP/1.1";
        };
    }

    static ArrayNode pairs(JsonNode headers) {
        ArrayNode out = ProtoJson.MAPPER.createArrayNode();
        if (headers == null || !headers.isObject()) return out;
        headers.fields().forEachRemaining(f -> out.addObject().put("name", f.getKey()).put("value", f.getValue().asText("")));
        return out;
    }

    static ArrayNode query(String url) {
        ArrayNode out = ProtoJson.MAPPER.createArrayNode();
        int q = url.indexOf('?');
        if (q < 0) return out;
        String rest = url.substring(q + 1);
        int hash = rest.indexOf('#');
        if (hash >= 0) rest = rest.substring(0, hash);
        for (String pair : rest.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            out.addObject().put("name", eq < 0 ? pair : pair.substring(0, eq)).put("value", eq < 0 ? "" : pair.substring(eq + 1));
        }
        return out;
    }

    static String globRegex(String glob) {
        StringBuilder out = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> out.append(".*");
                case '?' -> out.append('.');
                default -> out.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return out.toString();
    }
}
