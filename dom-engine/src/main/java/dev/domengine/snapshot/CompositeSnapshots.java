package dev.domengine.snapshot;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.session.BrowserSession;
import dev.domengine.tree.DomNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Engine entry points. MCP calls {@code browser}, {@code desktop}, and {@code offline}
 * and returns the JSON unchanged.
 */
public final class CompositeSnapshots {
    private CompositeSnapshots() {
    }

    public static ObjectNode offline(DomNode root, SnapshotOptions options) {
        long started = System.nanoTime();
        SnapshotOptions used = options == null ? new SnapshotOptions() : options;
        String html = read(used.file());
        SnapshotContext context = SnapshotContext.offline(root, used, html);
        return CompositeSnapshotBuilder.build(root, context, started).toJson();
    }

    public static ObjectNode browser(BrowserSession session, SnapshotOptions options) {
        throw new IllegalStateException(
                "error: browser snapshot is not produced in this build.\n"
                        + "fix: waiting on engine-defects Phase 5.");
    }

    public static ObjectNode desktop(String sessionId, SnapshotOptions options) {
        dev.domengine.desktop.DesktopTree.Session session = dev.domengine.desktop.DesktopTree.require(sessionId);
        java.util.Map<String, String> flags = new java.util.LinkedHashMap<>();
        if (options != null) flags.putAll(options.flags());
        return DesktopSnapshotProducer.produce(session, flags);
    }

    private static String read(String file) {
        if (file == null || file.isBlank()) return "";
        try {
            return Files.readString(Path.of(file));
        } catch (IOException failure) {
            return "";
        }
    }
}
