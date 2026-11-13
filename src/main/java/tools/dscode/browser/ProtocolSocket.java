package tools.dscode.browser;

import java.util.function.Consumer;

/** One text WebSocket, or a test double that speaks the same frames. */
public interface ProtocolSocket extends AutoCloseable {
    void sendText(String text);

    void onText(Consumer<String> listener);

    @Override
    void close();
}
