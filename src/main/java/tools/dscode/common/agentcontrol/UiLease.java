package tools.dscode.common.agentcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;

/**
 * One file-backed holder per spine session and target ({@code ui}, {@code browser}, or {@code both}).
 * A second take fails even for the same holder. This is the renderer lease, not {@code LeaseHold}
 * and not the in-memory Workbench control lease.
 */
public final class UiLease {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> TARGETS = Set.of("ui", "browser", "both");

    private UiLease() {
    }

    public static void take(Path project, String sessionId, String target, String holder) throws IOException {
        String id = safe(SpineSession.required(project, sessionId));
        String useTarget = target(target, id);
        String name = holder == null || holder.isBlank() ? SpineSession.DEFAULT_AGENT : holder.trim();
        String blocking = blocking(project, id, useTarget);
        if (blocking != null) {
            throw new IllegalArgumentException(
                    "error: another holder already has the " + blocking + " lease ("
                            + holderOf(file(project, id, blocking), name) + ")\n"
                            + "fix: lease release --target=" + blocking + " --session=" + id);
        }
        ObjectNode node = JSON.createObjectNode();
        node.put("holder", name);
        node.put("target", useTarget);
        node.put("session", id);
        write(file(project, id, useTarget), node);
    }

    public static void release(Path project, String sessionId, String target) throws IOException {
        String id = safe(SpineSession.required(project, sessionId));
        String useTarget = target(target, id);
        Path file = file(project, id, useTarget);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException(
                    "error: the lease is not held\n"
                            + "fix: lease take --target=ui|browser|both --session=" + id);
        }
        Files.deleteIfExists(file);
    }

    /**
     * {@code both} overlaps {@code ui} and {@code browser}. {@code ui} does not overlap {@code browser}.
     * The same target still overlaps itself, including a second take by the same holder.
     */
    private static String blocking(Path project, String session, String target) {
        if (Files.isRegularFile(file(project, session, target))) return target;
        if ("both".equals(target)) {
            if (Files.isRegularFile(file(project, session, "ui"))) return "ui";
            if (Files.isRegularFile(file(project, session, "browser"))) return "browser";
            return null;
        }
        if (Files.isRegularFile(file(project, session, "both"))) return "both";
        return null;
    }

    private static String target(String raw, String session) {
        String value = raw == null ? "" : raw.trim();
        if (!TARGETS.contains(value)) {
            throw new IllegalArgumentException(
                    "error: lease target must be ui, browser, or both\n"
                            + "fix: lease take --target=ui|browser|both --session=" + session);
        }
        return value;
    }

    private static String safe(String id) {
        if (id == null || id.isBlank() || id.indexOf('/') >= 0 || id.indexOf('\\') >= 0 || id.contains("..")) {
            throw new IllegalArgumentException("error: missing session\nfix: spine session new");
        }
        return id;
    }

    private static String holderOf(Path file, String fallback) {
        try {
            JsonNode node = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            String held = node.path("holder").asText("");
            return held.isBlank() ? fallback : held;
        } catch (IOException failure) {
            return fallback;
        }
    }

    private static void write(Path file, ObjectNode node) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, JSON.writeValueAsString(node), StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException failure) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path file(Path project, String session, String target) {
        Path root = project == null ? Path.of("") : project;
        return root.resolve(".pickleball").resolve("workbench-ui").resolve("leases").resolve(session).resolve(target + ".json");
    }
}
