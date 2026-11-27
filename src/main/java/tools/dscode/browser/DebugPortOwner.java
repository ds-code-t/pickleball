package tools.dscode.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
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
    private static final Map<Integer, Path> PROJECTS = new ConcurrentHashMap<>();
    private static final Map<Object, Integer> DRIVERS = new IdentityHashMap<>();
    private static final ThreadLocal<Integer> PENDING = new ThreadLocal<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(DebugPortOwner::releaseAll, "pkb-debug-port-release"));
    }

    private DebugPortOwner() {
    }

    /** JVM shutdown. Releases every claim still held by a driver or a port. */
    static void releaseAll() {
        List<Object> drivers;
        List<Integer> ports;
        List<String> owners;
        synchronized (OWNERS) {
            drivers = new ArrayList<>(DRIVERS.keySet());
            ports = new ArrayList<>(OWNERS.keySet());
            owners = new ArrayList<>();
            for (Integer port : ports) owners.add(OWNERS.get(port));
        }
        for (Object driver : drivers) releaseDriver(driver);
        for (int index = 0; index < ports.size(); index++) release(ports.get(index), owners.get(index));
    }

    public static void resetForTests() {
        synchronized (OWNERS) {
            OWNERS.clear();
            PROJECTS.clear();
            DRIVERS.clear();
        }
        PENDING.remove();
    }

    public static void attach(int port) {
        attach(port, "session", null);
    }

    /**
     * The same owner may attach again. A driver retry rebuilds options and calls this twice.
     * A different owner fails.
     */
    public static void attach(int port, String owner, Path project) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("error: debug port " + port + " is invalid\nfix: use a port from 1 to 65535");
        }
        String name = owner == null || owner.isBlank() ? "session" : owner.trim();
        boolean fresh;
        synchronized (OWNERS) {
            String current = OWNERS.get(port);
            if (name.equals(current)) return;
            if (current != null) {
                throw new IllegalStateException("error: debug port " + port + " already has an owner\nfix: browser ports");
            }
            OWNERS.put(port, name);
            if (project != null) PROJECTS.put(port, project);
            fresh = true;
        }
        if (fresh && project != null) record(project, port, name);
    }

    /** Remember the port a driver attach just claimed, until {@link #bindPending(Object)} or {@link #releasePending()}. */
    public static void notePending(int port) {
        PENDING.set(port);
    }

    /** Bind the pending driver claim to the driver the session registry just accepted. */
    public static void bindPending(Object driver) {
        Integer port = PENDING.get();
        PENDING.remove();
        if (port == null || driver == null) return;
        synchronized (OWNERS) {
            if (OWNERS.containsKey(port)) DRIVERS.put(driver, port);
        }
    }

    /** Drop a claim that never became a live driver. */
    public static void releasePending() {
        Integer port = PENDING.get();
        PENDING.remove();
        if (port != null) release(port, "driver");
    }

    /** Session quit. Releases the port only when this driver owns it. */
    public static void releaseDriver(Object driver) {
        if (driver == null) return;
        Integer port;
        synchronized (OWNERS) {
            port = DRIVERS.remove(driver);
        }
        if (port != null) release(port, "driver");
    }

    public static void release(int port, String owner) {
        String name = owner == null || owner.isBlank() ? "session" : owner.trim();
        Path project;
        synchronized (OWNERS) {
            String current = OWNERS.get(port);
            if (current == null || !current.equals(name)) return;
            OWNERS.remove(port);
            project = PROJECTS.remove(port);
            DRIVERS.entrySet().removeIf(entry -> port == entry.getValue());
        }
        if (project != null) unrecord(project, port);
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

    private static void unrecord(Path project, int port) {
        Path file = project.resolve(".pickleball").resolve("workbench").resolve("cli-session.json");
        if (!Files.isRegularFile(file)) return;
        try {
            ObjectNode node = (ObjectNode) JSON.readTree(file.toFile());
            JsonNode ports = node.get("debugPorts");
            if (ports instanceof ObjectNode object) object.remove(Integer.toString(port));
            JSON.writeValue(file.toFile(), node);
        } catch (IOException | RuntimeException ignored) {
            // The in-memory owner is already gone.
        }
    }
}
