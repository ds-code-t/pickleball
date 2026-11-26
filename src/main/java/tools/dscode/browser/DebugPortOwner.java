package tools.dscode.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One owner per debugging port. A second attach to the same port fails.
 * The owner is written into {@code .pickleball/workbench/cli-session.json} only when that file
 * already exists. This class does not create the session file.
 */
public final class DebugPortOwner {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<Integer, String> OWNERS = new ConcurrentHashMap<>();

    private DebugPortOwner() {
    }

    public static void resetForTests() {
        OWNERS.clear();
    }

    public static void attach(int port) {
        attach(port, "session", null);
    }

    public static void attach(int port, String owner, Path project) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("error: debug port " + port + " is invalid\nfix: use a port from 1 to 65535");
        }
        String name = owner == null || owner.isBlank() ? "session" : owner.trim();
        synchronized (OWNERS) {
            if (OWNERS.containsKey(port)) {
                throw new IllegalStateException("error: debug port " + port + " already has an owner\nfix: browser ports");
            }
            OWNERS.put(port, name);
        }
        if (project != null) record(project, port, name);
    }

    /** {@code port owner} lines, sorted by port. */
    public static List<String> ports() {
        List<Integer> keys = new ArrayList<>(OWNERS.keySet());
        keys.sort(Integer::compareTo);
        List<String> lines = new ArrayList<>();
        for (Integer port : keys) lines.add(port + " " + OWNERS.get(port));
        return List.copyOf(lines);
    }

    private static void record(Path project, int port, String owner) {
        Path file = project.resolve(".pickleball").resolve("workbench").resolve("cli-session.json");
        if (!Files.isRegularFile(file)) return;
        try {
            ObjectNode node = (ObjectNode) JSON.readTree(file.toFile());
            ObjectNode ports = node.withObject("debugPorts");
            ports.put(Integer.toString(port), owner);
            JSON.writeValue(file.toFile(), node);
        } catch (IOException | RuntimeException ignored) {
            // The in-memory owner still stands. A bad session file is not rewritten.
        }
    }
}
