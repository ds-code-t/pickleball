package dev.domengine.cdp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.json.Jsons;
import dev.domengine.log.EngineLog;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One DevTools WebSocket. Calls are JSON-RPC: {@code {id, method, params}} / {@code {id, result}}.
 */
public final class CdpConnection implements AutoCloseable {
    private final WebSocket socket;
    private final AtomicLong ids = new AtomicLong();
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<JsonNode>> eventListeners = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    private CdpConnection(WebSocket socket) {
        this.socket = socket;
    }

    public static CdpConnection connect(URI uri, Duration timeout) {
        if (uri == null) throw new CdpException("cdp websocket uri is missing");
        Duration wait = timeout == null ? Duration.ofSeconds(10) : timeout;
        Listener listener = new Listener();
        HttpClient client = HttpClient.newBuilder().connectTimeout(wait).build();
        try {
            WebSocket socket = client.newWebSocketBuilder()
                    .connectTimeout(wait)
                    .buildAsync(uri, listener)
                    .get(wait.toMillis(), TimeUnit.MILLISECONDS);
            CdpConnection connection = new CdpConnection(socket);
            listener.connection = connection;
            EngineLog.info("cdp", "connected " + uri);
            return connection;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new CdpException("cdp connect interrupted");
        } catch (ExecutionException | TimeoutException failure) {
            throw new CdpException("cdp connect failed: " + root(failure));
        }
    }

    public JsonNode call(String method, ObjectNode params, Duration timeout) {
        return call(method, params, null, timeout);
    }

    /** {@code sessionId} routes a flattened child-target call. Null stays on this target. */
    public JsonNode call(String method, ObjectNode params, String sessionId, Duration timeout) {
        if (closed) throw new CdpException("cdp connection is closed");
        long id = ids.incrementAndGet();
        ObjectNode request = Jsons.object();
        request.put("id", id);
        request.put("method", method);
        if (sessionId != null && !sessionId.isBlank()) request.put("sessionId", sessionId);
        request.set("params", params == null ? Jsons.object() : params);
        CompletableFuture<JsonNode> done = new CompletableFuture<>();
        pending.put(id, done);
        EngineLog.debug("cdp", method);
        try {
            socket.sendText(Jsons.compact(request), true).join();
            Duration wait = timeout == null ? Duration.ofSeconds(10) : timeout;
            JsonNode response = done.get(wait.toMillis(), TimeUnit.MILLISECONDS);
            if (response.has("error")) {
                throw new CdpException(method + " " + response.get("error"));
            }
            return response.get("result") == null ? Jsons.object() : response.get("result");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            pending.remove(id);
            throw new CdpException(method + " interrupted");
        } catch (ExecutionException | TimeoutException failure) {
            pending.remove(id);
            throw new CdpException(method + " " + root(failure));
        }
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * Receives every CDP event frame ({@code method} without {@code id}) this
     * socket reads. Listeners run on the socket reader thread, so they must
     * not block and must not call {@link #call} themselves.
     */
    public void addEventListener(Consumer<JsonNode> listener) {
        if (listener != null) eventListeners.add(listener);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
        } catch (RuntimeException ignored) {
            socket.abort();
        }
        pending.values().forEach(future -> future.completeExceptionally(new CdpException("cdp connection closed")));
        pending.clear();
    }

    void accept(String text) {
        JsonNode message;
        try {
            message = Jsons.MAPPER.readTree(text);
        } catch (Exception failure) {
            EngineLog.debug("cdp", "non-json frame");
            return;
        }
        if (!message.has("id")) {
            if (message.has("method")) {
                for (Consumer<JsonNode> listener : eventListeners) {
                    try {
                        listener.accept(message);
                    } catch (RuntimeException failure) {
                        EngineLog.debug("cdp", "event listener failed: " + failure.getMessage());
                    }
                }
            }
            return;
        }
        long id = message.get("id").asLong();
        CompletableFuture<JsonNode> future = pending.remove(id);
        if (future != null) future.complete(message);
    }

    private static String root(Throwable failure) {
        Throwable cursor = failure;
        while (cursor.getCause() != null && cursor.getCause() != cursor) cursor = cursor.getCause();
        String message = cursor.getMessage();
        return message == null || message.isBlank() ? cursor.getClass().getSimpleName() : message;
    }

    private static final class Listener implements WebSocket.Listener {
        private CdpConnection connection;
        private final StringBuilder partial = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String text = partial.toString();
                partial.setLength(0);
                if (connection != null) connection.accept(text);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (connection != null) connection.closed = true;
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (connection != null) {
                connection.closed = true;
                connection.pending.values().forEach(future -> future.completeExceptionally(error));
            }
        }
    }
}
