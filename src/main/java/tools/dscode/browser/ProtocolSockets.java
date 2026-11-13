package tools.dscode.browser;

import java.net.URI;

/** Socket factory. Tests replace it so a selector never dials a live browser. */
public final class ProtocolSockets {
    @FunctionalInterface
    public interface Factory {
        ProtocolSocket open(URI uri);
    }

    private static volatile Factory factory = JdkWebSocket::connect;

    private ProtocolSockets() {
    }

    public static ProtocolSocket open(URI uri) {
        return factory.open(uri);
    }

    public static void useFactoryForTests(Factory next) {
        factory = next == null ? JdkWebSocket::connect : next;
    }

    public static void resetForTests() {
        factory = JdkWebSocket::connect;
    }
}
