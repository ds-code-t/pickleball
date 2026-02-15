package tools.dscode.common.servicecalls;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import static tools.dscode.common.mappings.ValueFormatting.toSafeJsonNode;

/**
 * Immutable template for producing CallMap instances.
 */
public final class ServiceCallTemplate {

    private final String baseUri;
    private final String basePath;
    private final String endpoint;     // path appended at execution time (optional)
    private final String method;       // GET/POST/PUT/DELETE/PATCH...
    private final Map<String, ?> headers;
    private final Map<String, ?> queryParams;
    private final Map<String, ?> pathParams;
    private final String contentType;
    private final String accept;
    private final String bodyRaw;

    private final ObjectNode requestNode; // immutable-by-convention (we only hand out deep copies)

    public ServiceCallTemplate(
            String method,
            String baseUri,
            String basePath,
            String endpoint,
            Map<String, ?> headers,
            Map<String, ?> queryParams,
            Map<String, ?> pathParams,
            String contentType,
            String accept,
            String body
    ) {
        this.method = normalizeMethod(method);
        this.baseUri = trimToNull(baseUri);
        this.basePath = trimToNull(basePath);
        this.endpoint = trimToNull(endpoint);
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        this.queryParams = queryParams == null ? Map.of() : Map.copyOf(queryParams);
        this.pathParams = pathParams == null ? Map.of() : Map.copyOf(pathParams);
        this.contentType = trimToNull(contentType);
        this.accept = trimToNull(accept);
        this.bodyRaw = body; // can be null

        this.requestNode = buildRequestNode();
    }

    /** A deep-copy request node for new calls. */
    public ObjectNode newRequestNodeCopy() {
        return requestNode.deepCopy();
    }

    public CallMap newCall() {
        return new CallMap(this, newRequestNodeCopy());
    }

    public CallMap newCall(@NotNull Consumer<CallMap> initLogic) {
        Objects.requireNonNull(initLogic, "initLogic");
        return newCall().request(initLogic);
    }

    private ObjectNode buildRequestNode() {
        ObjectNode n = CallMap.MAPPER.createObjectNode();

        if (baseUri != null) n.put("baseUri", baseUri);
        if (basePath != null) n.put("basePath", basePath);
        if (endpoint != null) n.put("endpoint", endpoint);
        if (method != null) n.put("method", method);
        if (!headers.isEmpty()) n.set("headers", toSafeJsonNode(headers));
        if (!queryParams.isEmpty()) n.set("queryParams", toSafeJsonNode(queryParams));
        if (!pathParams.isEmpty()) n.set("pathParams", toSafeJsonNode(pathParams));
        if (contentType != null) n.put("contentType", contentType);
        if (accept != null) n.put("accept", accept);

        if (bodyRaw != null) {
            JsonNode bodyNode = JacksonUtils.parseBodyAuto(bodyRaw);
            n.set("body", bodyNode);
        }

        return n;
    }

    private static String normalizeMethod(String m) {
        String x = trimToNull(m);
        return x == null ? "GET" : x.toUpperCase();
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    // getters if you want them (optional)
}
