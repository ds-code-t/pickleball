package tools.dscode.common.servicecalls;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import tools.dscode.common.mappings.NodeMap;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

import static tools.dscode.common.mappings.ValueFormatting.toSafeJsonNode;
import static tools.dscode.common.servicecalls.RestAssuredUtil.buildRequest;
import static tools.dscode.common.servicecalls.RestAssuredUtil.execute;
import static tools.dscode.common.servicecalls.RestAssuredUtil.extractResponse;

/**
 * Mutable execution instance, produced by an immutable ServiceCallTemplate.
 */
public class CallMap extends NodeMap {
    // --- logging ---
    private boolean logEnabled = true;

    public static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = NodeMap.MAPPER;

    // --- immutable provenance ---
    private final ServiceCallTemplate template;
    private final ObjectNode originalRequest; // deep copy snapshot of template request

    // --- runtime ---
    private RequestSpecification requestSpec;
    private Response response;

    // --- retry config/state ---
    private Duration retryWait = Duration.ofSeconds(5);
    private int retryMax = 4;
    private Function<CallMap, Boolean> retryPredicate; // can mutate state and decide

    // Back-compat constructor (no template)
    public CallMap(JsonNode requestConfig) {
        this(null, requestConfig.deepCopy());
    }

    public CallMap(ServiceCallTemplate template, ObjectNode requestConfig) {
        super();
        this.template = template;
        this.originalRequest = requestConfig.deepCopy();

        // root state
        root.set("request", requestConfig);
        if (template != null) {
            root.set("template", toSafeJsonNode(template)); // stored as safe ref placeholder
        }

        rebuildRequestSpec();
    }

    // ---------- fluent API ----------

    /**
     * Apply arbitrary logic to this CallMap (including nested fluent calls).
     * Useful as the “request(...) takes logic” entrypoint.
     */
    public CallMap request(Consumer<CallMap> logic) {
        Objects.requireNonNull(logic, "logic");
        logic.accept(this);
        // if they mutated request config, they can call rebuild explicitly,
        // but we make it easy: rebuild every time request() is used.
        rebuildRequestSpec();
        return this;
    }

    /**
     * Convenience: mutate request node directly.
     */
    public CallMap requestNode(Consumer<ObjectNode> logic) {
        Objects.requireNonNull(logic, "logic");
        logic.accept(getRequestNode());
        rebuildRequestSpec();
        return this;
    }

    /**
     * Configure retry logic. Predicate may also mutate state (wait/max/etc).
     */
    public CallMap retryOn(Function<CallMap, Boolean> predicate) {
        this.retryPredicate = Objects.requireNonNull(predicate, "predicate");
        return this;
    }

    public CallMap retryWait(Duration wait) {
        this.retryWait = Objects.requireNonNull(wait, "wait");
        return this;
    }

    public CallMap retryMax(int max) {
        if (max < 0) throw new IllegalArgumentException("retryMax must be >= 0");
        this.retryMax = max;
        return this;
    }

    /**
     * Trigger the call (with retry policy) and then run post-logic that can mutate root,
     * request, response, etc. Response overwrites previous response; other state remains.
     */
    public CallMap run(Consumer<CallMap> postLogic) {
        run(); // do the call(s)
        if (postLogic != null) postLogic.accept(this);
        return this;
    }

    /**
     * Trigger the call with retry policy. Overwrites previous response only.
     */
    public Response run() {
        int attempt = 0;
        Response last = null;

        while (true) {
            attempt++;

            if (logEnabled) {
                System.out.println("\n============================================================");
                System.out.println("SERVICE CALL: attempt " + attempt + " of " + (retryMax + 1));
                System.out.println("============================================================");
            }

            last = runOnce(attempt);

            boolean shouldRetry = false;
            if (retryPredicate != null) {
                try {
                    shouldRetry = Boolean.TRUE.equals(retryPredicate.apply(this));
                } catch (Exception e) {
                    // predicate failed; treat as no-retry but report
                    if (logEnabled) {
                        System.out.println("[RETRY predicate ERROR] " + e.getClass().getSimpleName() + ": " + safeMsg(e));
                    }
                    shouldRetry = false;
                }
            }

            boolean hasMoreAttempts = attempt <= retryMax;

            if (shouldRetry && hasMoreAttempts) {
                if (logEnabled) {
                    System.out.println("[RETRY] Condition matched -> retrying after " + retryWait.toMillis() + " ms"
                            + " (remaining retries: " + (retryMax - attempt + 1) + ")");
                }
                sleepQuietly(retryWait);
                continue;
            }

            if (shouldRetry && !hasMoreAttempts && logEnabled) {
                System.out.println("[RETRY] Condition matched but no retries left -> stopping.");
            }

            break;
        }

        return last;
    }

    private Response runOnce(int attempt) {
        // ensure spec matches current request node
        rebuildRequestSpec();

        ObjectNode reqNode = getRequestNode();

        if (logEnabled) {
            System.out.println(prettyRequest(reqNode));
        }

        try {
            response = RestAssuredUtil.execute(requestSpec, reqNode);
            root.set("response", RestAssuredUtil.extractResponse(response));

            if (logEnabled) {
                System.out.println(prettyResponse(root.get("response")));
            }

            return response;
        } catch (Exception e) {
            response = null;

            // Store an error node in root.response so the state is still inspectable
            ObjectNode err = MAPPER.createObjectNode();
            err.put("error", true);
            err.put("message", safeMsg(e));
            err.put("exception", e.getClass().getName());
            err.put("attempt", attempt);
            root.set("response", err);

            if (logEnabled) {
                System.out.println("[FAILURE] No response (exception thrown).");
                System.out.println("[FAILURE] " + e.getClass().getSimpleName() + ": " + safeMsg(e));
                System.out.println(prettyResponse(err));
            }

            return null;
        }
    }


    /**
     * Reset request state back to template/original request snapshot and rebuild spec.
     */
    public CallMap resetRequest() {
        root.set("request", originalRequest.deepCopy());
        rebuildRequestSpec();
        return this;
    }

    /**
     * Create a brand new CallMap from the original template snapshot.
     * Subsequent chained calls apply to the returned instance.
     */
    public CallMap newCall() {
        if (template != null) {
            return template.newCall();
        }
        // if no template (back-compat), we still can clone from originalRequest snapshot
        return new CallMap(null, originalRequest.deepCopy());
    }

    // ---------- internals ----------

    private Response runOnce() {
        // ensure spec matches current request node
        rebuildRequestSpec();

        JsonNode reqNode = getRequestNode();
        response = execute(requestSpec, reqNode);
        root.set("response", extractResponse(response));
        return response;
    }

    private void rebuildRequestSpec() {
        this.requestSpec = buildRequest(getRequestNode());
    }

    private ObjectNode getRequestNode() {
        JsonNode n = root.get("request");
        if (n == null || !n.isObject()) {
            ObjectNode fresh = MAPPER.createObjectNode();
            root.set("request", fresh);
            return fresh;
        }
        return (ObjectNode) n;
    }

    private static void sleepQuietly(Duration d) {
        if (d == null || d.isZero() || d.isNegative()) return;
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private String prettyRequest(ObjectNode req) {
        String method = req.path("method").asText("GET");
        String baseUri = req.path("baseUri").asText("");
        String basePath = req.path("basePath").asText("");
        String endpoint = req.path("endpoint").asText("");

        String url = joinUrl(baseUri, basePath, endpoint);

        StringBuilder sb = new StringBuilder();
        sb.append("REQUEST\n");
        sb.append("  ").append(method).append(" ").append(url).append("\n");

        JsonNode headers = req.get("headers");
        if (headers != null && !headers.isMissingNode() && !headers.isNull()) {
            sb.append("  Headers:\n").append(indent(prettyJson(headers))).append("\n");
        }

        JsonNode query = req.get("queryParams");
        if (query != null && !query.isMissingNode() && !query.isNull()) {
            sb.append("  Query Params:\n").append(indent(prettyJson(query))).append("\n");
        }

        JsonNode path = req.get("pathParams");
        if (path != null && !path.isMissingNode() && !path.isNull()) {
            sb.append("  Path Params:\n").append(indent(prettyJson(path))).append("\n");
        }

        String contentType = req.path("contentType").asText(null);
        if (contentType != null) sb.append("  Content-Type: ").append(contentType).append("\n");

        String accept = req.path("accept").asText(null);
        if (accept != null) sb.append("  Accept: ").append(accept).append("\n");

        JsonNode body = req.get("body");
        if (body != null && !body.isNull()) {
            sb.append("  Body:\n");
            if (body.isTextual()) {
                sb.append(indent(body.asText()));
            } else {
                sb.append(indent(prettyJson(body)));
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private String prettyResponse(JsonNode respNode) {
        if (respNode == null || respNode.isNull()) {
            return "RESPONSE\n  <null response node>\n";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("RESPONSE\n");

        if (respNode.path("error").asBoolean(false)) {
            sb.append("  <ERROR>\n");
        }

        if (respNode.has("statusCode")) {
            sb.append("  Status: ").append(respNode.path("statusCode").asInt()).append("\n");
        }
        if (respNode.has("statusLine")) {
            sb.append("  StatusLine: ").append(respNode.path("statusLine").asText()).append("\n");
        }

        JsonNode headers = respNode.get("headers");
        if (headers != null && !headers.isNull()) {
            sb.append("  Headers:\n").append(indent(prettyJson(headers))).append("\n");
        }

        JsonNode body = respNode.get("body");
        if (body != null && !body.isNull()) {
            sb.append("  Body:\n");
            // try to pretty-print JSON bodies if possible; otherwise show raw
            if (body.isTextual()) {
                String raw = body.asText();
                JsonNode parsed = tryParseJson(raw);
                if (parsed != null) {
                    sb.append(indent(prettyJson(parsed)));
                } else {
                    sb.append(indent(truncate(raw, 4000)));
                }
            } else {
                sb.append(indent(prettyJson(body)));
            }
            sb.append("\n");
        }

        // include stored error fields if present
        if (respNode.has("exception") || respNode.has("message")) {
            sb.append("  Details:\n");
            if (respNode.has("exception")) sb.append("    exception: ").append(respNode.get("exception").asText()).append("\n");
            if (respNode.has("message")) sb.append("    message  : ").append(respNode.get("message").asText()).append("\n");
        }

        return sb.toString();
    }

    private static JsonNode tryParseJson(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String prettyJson(JsonNode n) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(n);
        } catch (Exception e) {
            return String.valueOf(n);
        }
    }

    private static String indent(String s) {
        String[] lines = s.split("\\R", -1);
        StringBuilder out = new StringBuilder();
        for (String line : lines) out.append("    ").append(line).append("\n");
        return out.toString();
    }

    private static String joinUrl(String baseUri, String basePath, String endpoint) {
        String a = baseUri == null ? "" : baseUri.trim();
        String b = basePath == null ? "" : basePath.trim();
        String c = endpoint == null ? "" : endpoint.trim();

        // normalize slashes
        a = trimTrailingSlashes(a);
        b = trimSlashes(b);
        c = trimLeadingSlashes(c);

        StringBuilder sb = new StringBuilder();
        sb.append(a);
        if (!b.isEmpty()) sb.append("/").append(b);
        if (!c.isEmpty()) sb.append("/").append(c);
        return sb.toString();
    }

    private static String trimTrailingSlashes(String s) {
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String trimLeadingSlashes(String s) {
        while (s.startsWith("/")) s = s.substring(1);
        return s;
    }

    private static String trimSlashes(String s) {
        return trimLeadingSlashes(trimTrailingSlashes(s));
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "\n... <truncated> ...";
    }

    private static String safeMsg(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? "<no message>" : m;
    }


    // getters if you need them
    public Response getResponse() { return response; }
    public ServiceCallTemplate getTemplate() { return template; }
}
