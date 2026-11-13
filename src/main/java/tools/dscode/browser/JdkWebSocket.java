package tools.dscode.browser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** JDK WebSocket client. CDP and BiDi both ride this. No Selenium transport. */
final class JdkWebSocket implements ProtocolSocket {
    private final WebSocket socket;
    private volatile Consumer<String> listener = text -> {
    };

    private JdkWebSocket(WebSocket socket) {
        this.socket = socket;
    }

    static ProtocolSocket connect(URI uri) {
        if (uri == null) {
            throw new IllegalArgumentException("error: websocket URI is null.\nfix: Pass ws:// or wss://.");
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        Holder holder = new Holder();
        try {
            WebSocket opened = client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .buildAsync(uri, holder)
                    .get(4, TimeUnit.SECONDS);
            JdkWebSocket self = new JdkWebSocket(opened);
            holder.owner = self;
            opened.request(1);
            return self;
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "error: websocket connect failed for " + uri + ": " + failure.getMessage() + "\n"
                            + "fix: Check the debugger or BiDi URL and that the browser is still up.",
                    failure);
        }
    }

    @Override
    public void sendText(String text) {
        socket.sendText(text == null ? "" : text, true).join();
    }

    @Override
    public void onText(Consumer<String> listener) {
        this.listener = listener == null ? text -> {
        } : listener;
    }

    @Override
    public void close() {
        try {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
        } catch (RuntimeException ignored) {
            socket.abort();
        }
    }

    private static final class Holder implements WebSocket.Listener {
        private final StringBuilder partial = new StringBuilder();
        private volatile JdkWebSocket owner;

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String text = partial.toString();
                partial.setLength(0);
                JdkWebSocket current = owner;
                if (current != null) current.listener.accept(text);
            }
            webSocket.request(1);
            return null;
        }
    }
}
