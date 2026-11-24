package tools.dscode.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A long-lived JSON-RPC channel for CDP or WebDriver BiDi with any number of event listeners.
 * Events are delivered on one ordered worker thread, so a listener may call {@link #call} without blocking the socket
 * reader (CDP {@code Fetch.requestPaused} answers, BiDi intercept answers). Used by the protocol host and the BiDi
 * module client; {@link JsonRpc} stays the short-call path of the adapters.
 */
public final class RpcChannel implements AutoCloseable {
    public enum Dialect { CDP, BIDI }

    /** One event: method, params, and the flattened CDP session id (null for BiDi and the browser target). */
    @FunctionalInterface
    public interface Listener {
        void onEvent(String method, JsonNode params, String sessionId);
    }

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProtocolSocket socket;
    private final Dialect dialect;
    private final AtomicLong ids = new AtomicLong();
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService events = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "pkb-rpc-events");
        thread.setDaemon(true);
        return thread;
    });
    private volatile long timeoutMs = 10_000;
    private volatile boolean closed;

    public RpcChannel(ProtocolSocket socket, Dialect dialect) {
        this.socket = socket;
        this.dialect = dialect;
        socket.onText(this::onText);
    }

    public static RpcChannel open(URI uri, Dialect dialect) {
        return new RpcChannel(ProtocolSockets.open(uri), dialect);
    }

    public Dialect dialect() {
        return dialect;
    }

    public RpcChannel timeoutMs(long value) {
        timeoutMs = Math.max(100, value);
        return this;
    }

    public void listen(Listener listener) {
        if (listener != null) listeners.add(listener);
    }

    public void unlisten(Listener listener) {
        listeners.remove(listener);
    }

    public JsonNode call(String method, Map<String, ?> params) {
        return call(method, params, null);
    }

    /** Sends one command; a CDP {@code sessionId} targets a flattened child session. Errors keep the protocol text. */
    private final Object sendLock = new Object();

    public JsonNode call(String method, Map<String, ?> params, String sessionId) {
        if (closed) throw new IllegalStateException("error: " + method + ": the protocol socket is closed.\nfix: Reconnect to the browser.");
        long id = ids.incrementAndGet();
        ObjectNode request = MAPPER.createObjectNode();
        request.put("id", id);
        request.put("method", method);
        request.set("params", params == null ? MAPPER.createObjectNode() : MAPPER.valueToTree(params));
        if (sessionId != null && !sessionId.isBlank() && dialect == Dialect.CDP) request.put("sessionId", sessionId);
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        JsonNode response;
        try {
            String text = MAPPER.writeValueAsString(request);
            // java.net.http.WebSocket allows one outstanding send; callers on several threads share this channel.
            synchronized (sendLock) {
                socket.sendText(text);
            }
            response = future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception failure) {
            pending.remove(id);
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("error: " + method + " was not answered: " + failure + "\n"
                    + "fix: Check that the browser is still up.", failure);
        }
        String error = error(response);
        if (error != null) {
            if (UnsupportedProtocolOperation.unknownCommand(error)) {
                throw new UnsupportedProtocolOperation(method, dialect.name().toLowerCase(java.util.Locale.ROOT), error);
            }
            throw new IllegalStateException("error: " + method + " failed: " + error + "\nfix: Check the method name and parameters.");
        }
        JsonNode result = response.get("result");
        return result == null || result.isNull() ? MAPPER.createObjectNode() : result;
    }

    private String error(JsonNode node) {
        if (dialect == Dialect.BIDI) {
            if (!"error".equals(node.path("type").asText(""))) return null;
            String message = node.path("message").asText("");
            String code = node.path("error").asText("");
            return message.isBlank() ? code : code + ": " + message;
        }
        JsonNode error = node.get("error");
        if (error == null || error.isNull()) return null;
        return error.path("message").asText(error.toString()) + " (" + error.path("code").asText("") + ")";
    }

    private void onText(String text) {
        JsonNode node;
        try {
            node = MAPPER.readTree(text);
        } catch (Exception failure) {
            return;
        }
        JsonNode id = node.get("id");
        boolean event = dialect == Dialect.BIDI ? "event".equals(node.path("type").asText("")) : id == null;
        if (!event && id != null && id.canConvertToLong()) {
            CompletableFuture<JsonNode> future = pending.remove(id.asLong());
            if (future != null) future.complete(node);
            return;
        }
        String method = node.path("method").asText("");
        if (method.isBlank() || listeners.isEmpty()) return;
        JsonNode params = node.path("params");
        String session = node.hasNonNull("sessionId") ? node.get("sessionId").asText() : null;
        events.execute(() -> {
            for (Listener listener : listeners) {
                try {
                    listener.onEvent(method, params, session);
                } catch (RuntimeException ignored) {
                    // One listener must not stop the others.
                }
            }
        });
    }

    @Override
    public void close() {
        closed = true;
        events.shutdownNow();
        try {
            socket.close();
        } catch (RuntimeException ignored) {
            // Closing twice is fine.
        }
    }
}
