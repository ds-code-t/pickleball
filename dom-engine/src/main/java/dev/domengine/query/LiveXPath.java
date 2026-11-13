package dev.domengine.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.cdp.CdpConnection;
import dev.domengine.cdp.CdpException;
import dev.domengine.json.Jsons;
import dev.domengine.tree.DomNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live XPath 1.0. The page runs {@code document.evaluate} with
 * {@code ORDERED_NODE_SNAPSHOT_TYPE}. Hits become backend node ids through
 * {@code DOM.requestNode} and {@code DOM.describeNode}.
 */
public final class LiveXPath {
    private static final ThreadLocal<CdpConnection> CONNECTION = new ThreadLocal<>();
    private static final ThreadLocal<DomNode> WITHIN = new ThreadLocal<>();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String EVALUATE = """
            function(expr) {
              const ctx = (this && (this.nodeType === 1 || this.nodeType === 9 || this.nodeType === 11))
                ? this : document;
              let snap;
              try {
                snap = document.evaluate(String(expr), ctx, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
              } catch (e) {
                return {ok: false, invalid: String(e && e.message || e)};
              }
              const nodes = [];
              for (let i = 0; i < snap.snapshotLength; i++) nodes.push(snap.snapshotItem(i));
              return {ok: true, nodes: nodes};
            }
            """;

    private LiveXPath() {
    }

    /** Binds the page socket for the next query. {@code null} clears it. */
    public static void use(CdpConnection connection) {
        if (connection == null) CONNECTION.remove();
        else CONNECTION.set(connection);
    }

    public static CdpConnection connection() {
        return CONNECTION.get();
    }

    /** Scope node for a relative XPath when the pipeline is still on the document. */
    public static void within(DomNode node) {
        if (node == null) WITHIN.remove();
        else WITHIN.set(node);
    }

    public static DomNode withinNode() {
        return WITHIN.get();
    }

    public static List<Long> backendIds(CdpConnection cdp, String expression, DomNode context) {
        if (cdp == null) throw new CdpException("invalid xpath: no page session");
        if (expression == null || expression.isBlank()) throw new CdpException("invalid xpath");
        List<String> release = new ArrayList<>();
        try {
            String wrapper = evaluate(cdp, expression, context, release);
            if (!bool(cdp, wrapper, "function(){ return this.ok === true; }")) {
                String why = text(cdp, wrapper, "function(){ return this.invalid || 'invalid xpath'; }");
                throw new CdpException("invalid xpath: " + why);
            }
            int count = (int) number(cdp, wrapper, "function(){ return this.nodes.length; }");
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String objectId = nodeAt(cdp, wrapper, i, release);
                if (objectId.isBlank()) continue;
                long backend = backendOf(cdp, objectId);
                if (backend > 0) ids.add(backend);
            }
            return ids;
        } finally {
            for (String objectId : release) release(cdp, objectId);
        }
    }

    public static List<DomNode> nodesFor(DomNode scope, List<Long> backendIds) {
        Map<Long, DomNode> index = new LinkedHashMap<>();
        index(scope, index);
        List<DomNode> hits = new ArrayList<>();
        if (backendIds == null) return hits;
        for (Long id : backendIds) {
            DomNode node = index.get(id);
            if (node != null) hits.add(node);
        }
        return hits;
    }

    private static String evaluate(CdpConnection cdp, String expression, DomNode context, List<String> release) {
        String contextId = contextObject(cdp, context, release);
        ObjectNode params = Jsons.object();
        params.put("returnByValue", false);
        JsonNode result;
        if (contextId == null) {
            params.put("expression", "(" + EVALUATE + ")(" + Jsons.compact(Jsons.MAPPER.getNodeFactory().textNode(expression)) + ")");
            result = cdp.call("Runtime.evaluate", params, TIMEOUT);
        } else {
            params.put("functionDeclaration", EVALUATE);
            params.put("objectId", contextId);
            ArrayNode args = params.putArray("arguments");
            args.addObject().put("value", expression);
            result = cdp.call("Runtime.callFunctionOn", params, TIMEOUT);
        }
        if (result.has("exceptionDetails")) {
            throw new CdpException("invalid xpath: " + result.get("exceptionDetails"));
        }
        String objectId = result.path("result").path("objectId").asText("");
        if (objectId.isBlank()) throw new CdpException("invalid xpath: evaluation returned no object");
        release.add(objectId);
        return objectId;
    }

    private static String contextObject(CdpConnection cdp, DomNode context, List<String> release) {
        if (context == null || context.backendNodeId <= 0 || context.nodeType == 9) return null;
        ObjectNode params = Jsons.object();
        params.put("backendNodeId", context.backendNodeId);
        JsonNode resolved = cdp.call("DOM.resolveNode", params, TIMEOUT);
        String objectId = resolved.path("object").path("objectId").asText("");
        if (objectId.isBlank()) return null;
        release.add(objectId);
        return objectId;
    }

    private static long backendOf(CdpConnection cdp, String objectId) {
        ObjectNode request = Jsons.object();
        request.put("objectId", objectId);
        JsonNode requested = cdp.call("DOM.requestNode", request, TIMEOUT);
        long nodeId = requested.path("nodeId").asLong(0);
        if (nodeId <= 0) return 0;
        ObjectNode describe = Jsons.object();
        describe.put("nodeId", nodeId);
        JsonNode described = cdp.call("DOM.describeNode", describe, TIMEOUT);
        return described.path("node").path("backendNodeId").asLong(0);
    }

    private static String nodeAt(CdpConnection cdp, String wrapper, int index, List<String> release) {
        ObjectNode params = Jsons.object();
        params.put("functionDeclaration", "function(i){ return this.nodes[i]; }");
        params.put("objectId", wrapper);
        params.put("returnByValue", false);
        ArrayNode args = params.putArray("arguments");
        args.addObject().put("value", index);
        JsonNode called = cdp.call("Runtime.callFunctionOn", params, TIMEOUT);
        if (called.has("exceptionDetails")) return "";
        String objectId = called.path("result").path("objectId").asText("");
        if (!objectId.isBlank()) release.add(objectId);
        return objectId;
    }

    private static boolean bool(CdpConnection cdp, String objectId, String function) {
        return callValue(cdp, objectId, function).path("result").path("value").asBoolean(false);
    }

    private static String text(CdpConnection cdp, String objectId, String function) {
        JsonNode value = callValue(cdp, objectId, function).path("result").path("value");
        return value.isMissingNode() || value.isNull() ? "invalid xpath" : value.asText("invalid xpath");
    }

    private static double number(CdpConnection cdp, String objectId, String function) {
        return callValue(cdp, objectId, function).path("result").path("value").asDouble(0);
    }

    private static JsonNode callValue(CdpConnection cdp, String objectId, String function) {
        ObjectNode params = Jsons.object();
        params.put("functionDeclaration", function);
        params.put("objectId", objectId);
        params.put("returnByValue", true);
        JsonNode called = cdp.call("Runtime.callFunctionOn", params, TIMEOUT);
        if (called.has("exceptionDetails")) throw new CdpException("invalid xpath: " + called.get("exceptionDetails"));
        return called;
    }

    private static void release(CdpConnection cdp, String objectId) {
        try {
            ObjectNode params = Jsons.object();
            params.put("objectId", objectId);
            cdp.call("Runtime.releaseObject", params, Duration.ofSeconds(2));
        } catch (RuntimeException ignored) {
            // The page is already done with the query.
        }
    }

    private static void index(DomNode node, Map<Long, DomNode> into) {
        if (node == null) return;
        if (node.backendNodeId > 0) into.putIfAbsent(node.backendNodeId, node);
        for (DomNode child : node.children) index(child, into);
        for (DomNode shadow : node.shadowRoots) index(shadow, into);
    }
}
