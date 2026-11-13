package tools.dscode.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Numeric-id JSON-RPC over a {@link ProtocolSocket}. CDP and BiDi differ only in the reply shape. */
final class JsonRpc {
    static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProtocolSocket socket;
    private final AtomicLong ids = new AtomicLong();
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final ReplyMatcher replies;
    private volatile EventHandler events = (method, params) -> {
    };

    JsonRpc(ProtocolSocket socket, ReplyMatcher replies) {
        this.socket = socket;
        this.replies = replies;
        socket.onText(this::onText);
    }

    void onEvent(EventHandler handler) {
        events = handler == null ? (method, params) -> {
        } : handler;
    }

    JsonNode call(String method, Map<String, ?> params) {
        long id = ids.incrementAndGet();
        ObjectNode request = MAPPER.createObjectNode();
        request.put("id", id);
        request.put("method", method);
        request.set("params", params == null ? MAPPER.createObjectNode() : MAPPER.valueToTree(params));
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            socket.sendText(MAPPER.writeValueAsString(request));
            JsonNode response = future.get(3, TimeUnit.SECONDS);
            String error = replies.error(response);
            if (error != null) {
                throw new IllegalStateException(
                        "error: " + method + " failed: " + error + "\n"
                                + "fix: Check the method name and parameters.");
            }
            return replies.result(response);
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            pending.remove(id);
            throw new IllegalStateException(
                    "error: " + method + " was not answered: " + failure.getMessage() + "\n"
                            + "fix: The protocol socket must reply before 3 seconds.",
                    failure);
        }
    }

    void close() {
        socket.close();
    }

    private void onText(String text) {
        JsonNode node;
        try {
            node = MAPPER.readTree(text);
        } catch (Exception failure) {
            return;
        }
        Long id = replies.id(node);
        if (id != null) {
            CompletableFuture<JsonNode> future = pending.remove(id);
            if (future != null) future.complete(node);
            return;
        }
        String method = node.path("method").asText("");
        if (!method.isBlank()) events.onEvent(method, node.path("params"));
    }

    @FunctionalInterface
    interface ReplyMatcher {
        Long id(JsonNode node);

        default JsonNode result(JsonNode node) {
            JsonNode result = node.get("result");
            return result == null || result.isNull() ? MAPPER.createObjectNode() : result;
        }

        default String error(JsonNode node) {
            return null;
        }
    }

    @FunctionalInterface
    interface EventHandler {
        void onEvent(String method, JsonNode params);
    }

    static Object value(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        return MAPPER.convertValue(node, Object.class);
    }
}
