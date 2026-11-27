package tools.dscode.launcher;

import tools.dscode.browser.PlaywrightConnection;
import tools.dscode.engine.runtime.ResourceGate;
import tools.dscode.engine.runtime.WarmFlags;

import java.nio.file.Path;

/**
 * One Playwright CDP connection held by the warm JVM.
 * Opens only when {@code pkb_warm=on} and {@link ResourceGate} returns {@code allow}.
 * {@code pkb_warm=off} and {@code refuse} do not open it. {@code spine stop} closes it.
 */
public final class WarmCdp {
    /** Test double for {@link PlaywrightConnection#connect(String)}. */
    public interface Connector {
        void connect(String endpoint);

        void close();
    }

    private static volatile Connector connectorForTests;
    private static volatile boolean held;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(WarmCdp::close, "pkb-warm-cdp-close"));
    }

    private WarmCdp() {
    }

    public static void resetForTests() {
        connectorForTests = null;
        held = false;
    }

    public static void useConnectorForTests(Connector connector) {
        connectorForTests = connector;
    }

    public static boolean held() {
        return held;
    }

    /**
     * Called from the hold JVM. A second call reuses the open connection.
     * A missing endpoint does not fail the warm JVM and does not download a browser.
     */
    public static void open(Path project) {
        if (!WarmFlags.enabled() || held) return;
        if (ResourceGate.decisionNow(project) != ResourceGate.Decision.ALLOW) return;
        String endpoint = PlaywrightConnection.configuredEndpoint(null);
        Connector connector = connectorForTests;
        if (connector != null) {
            connector.connect(endpoint);
            held = true;
            return;
        }
        if (endpoint == null || endpoint.isBlank()) return;
        try {
            PlaywrightConnection.connect(endpoint);
            held = true;
        } catch (RuntimeException ignored) {
            // The warm JVM stays up. The next start can try again after spine stop.
        }
    }

    /** Closes the connection held in this JVM. A JVM that does not hold one returns. */
    public static void close() {
        if (!held) return;
        held = false;
        Connector connector = connectorForTests;
        if (connector != null) {
            connector.close();
            return;
        }
        PlaywrightConnection.closeAll();
    }
}
