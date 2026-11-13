package dev.domengine.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.cdp.CdpException;
import dev.domengine.json.Jsons;
import dev.domengine.log.EngineLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Named sessions owned by this CLI process.
 * The registry file and the per-session event log match the Rust composite:
 * {@code registry.json} plus {@code <id>.events.jsonl} under {@code --session-dir}
 * or {@code COMPOSITE_SESSION_DIR} (default {@code composite-sessions} in the temp dir).
 */
public final class SessionRegistry {
    private static final SessionRegistry INSTANCE = new SessionRegistry();
    private final ConcurrentHashMap<String, BrowserSession> sessions = new ConcurrentHashMap<>();
    private volatile String joined;
    private volatile Path directoryOverride;

    public static SessionRegistry get() {
        return INSTANCE;
    }

    public static void resetForTests() {
        Path dir = INSTANCE.directory();
        INSTANCE.sessions.values().forEach(BrowserSession::close);
        INSTANCE.sessions.clear();
        INSTANCE.joined = null;
        INSTANCE.directoryOverride = null;
        scrub(dir);
    }

    /** Pins the registry directory and reloads sessions that are not already live. */
    public void useDirectory(Path dir) {
        if (dir == null) return;
        directoryOverride = dir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(directoryOverride);
        } catch (IOException failure) {
            throw new CdpException("session dir: " + failure.getMessage());
        }
        loadMissing();
    }

    public Path directory() {
        if (directoryOverride != null) return directoryOverride;
        String env = System.getenv("COMPOSITE_SESSION_DIR");
        if (env != null && !env.isBlank()) return Path.of(env);
        return Path.of(System.getProperty("java.io.tmpdir"), "composite-sessions");
    }

    public String joined() {
        return joined;
    }

    public BrowserSession create(
            String id,
            String url,
            String cdp,
            boolean headed,
            boolean process,
            boolean acceptAttach,
            Path profile,
            List<String> allow
    ) {
        if (id == null || id.isBlank()) throw new CdpException("session id is missing");
        synchronized (this) {
            loadMissing();
            if (sessions.containsKey(id)) throw new CdpException("session already exists: " + id);
            BrowserSession session;
            if (cdp != null && !cdp.isBlank()) {
                if (!acceptAttach && !"1".equals(System.getenv("COMPOSITE_ATTACH"))) {
                    throw new CdpException("attach requires --i-accept-local-attach or COMPOSITE_ATTACH=1");
                }
                session = BrowserSession.attach(id, cdp, process, allow == null ? List.of() : allow);
            } else {
                session = BrowserSession.launch(id, url, headed, process, profile, allow == null ? List.of() : allow);
            }
            sessions.put(id, session);
            if (joined == null || joined.isBlank()) joined = id;
            recordEvent(id, session.launched ? "create" : "attach", session.record());
            save();
            return session;
        }
    }

    public BrowserSession require(String id) {
        synchronized (this) {
            loadMissing();
            BrowserSession session = sessions.get(id);
            if (session == null || session.isClosed()) throw new CdpException("session not found: " + id);
            return session;
        }
    }

    public ObjectNode list() {
        synchronized (this) {
            loadMissing();
            ObjectNode node = Jsons.object();
            node.put("ok", true);
            List<BrowserSession> open = new ArrayList<>();
            for (BrowserSession session : sessions.values()) {
                if (!session.isClosed()) open.add(session);
            }
            open.sort(Comparator.comparing(session -> session.id));
            ArrayNode array = node.putArray("sessions");
            for (BrowserSession session : open) array.add(session.record());
            node.put("count", open.size());
            if (joined == null) node.putNull("joined");
            else node.put("joined", joined);
            return node;
        }
    }

    public ObjectNode close(String id) {
        if (id == null || id.isBlank()) {
            id = joined;
            if (id == null || id.isBlank()) {
                throw new CdpException("session close needs --id or a joined session");
            }
        }
        synchronized (this) {
            loadMissing();
            BrowserSession session = sessions.remove(id);
            if (session == null) throw new CdpException("session " + id + " not found");
            boolean launched = session.launched;
            if (id.equals(joined)) joined = null;
            ObjectNode detail = Jsons.object();
            detail.put("killed", launched);
            detail.put("launched", launched);
            recordEvent(id, "close", detail);
            save();
            session.close();
            ObjectNode node = Jsons.object();
            node.put("ok", true);
            node.put("id", id);
            node.put("closed", true);
            node.put("detached", true);
            node.put("killed", launched && session.killedBrowser());
            node.put("launched", launched);
            return node;
        }
    }

    /**
     * Join by id. Later commands that omit {@code --session} use this session.
     * Output matches the Rust CLI: {@code joined} plus the session record.
     */
    public ObjectNode join(String id) {
        if (id == null || id.isBlank()) throw new CdpException("session join needs --id or --cdp");
        synchronized (this) {
            loadMissing();
            BrowserSession session = sessions.get(id);
            if (session == null || session.isClosed()) throw new CdpException("session " + id + " not found");
            joined = id;
            save();
            EngineLog.info("session", "join " + id);
            ObjectNode detail = Jsons.object();
            detail.put("by", "id");
            recordEvent(id, "join", detail);
            ObjectNode node = Jsons.object();
            node.put("joined", id);
            node.set("session", session.record());
            return node;
        }
    }

    /**
     * Join the session that already uses {@code cdp}, or attach that endpoint and then join it.
     * Attaching a browser this process did not launch still requires the opt-in flag.
     */
    public ObjectNode joinCdp(String cdp, boolean acceptAttach) {
        if (cdp == null || cdp.isBlank()) throw new CdpException("session join needs --id or --cdp");
        String wanted = BrowserSession.normalize(cdp);
        String existing;
        synchronized (this) {
            loadMissing();
            existing = findByCdp(wanted);
        }
        if (existing == null) {
            String id = "s" + System.nanoTime();
            create(id, null, cdp, false, false, acceptAttach, null, List.of());
            existing = id;
        }
        return join(existing);
    }

    public ObjectNode events(String id) {
        if (id == null || id.isBlank()) throw new CdpException("missing --id");
        synchronized (this) {
            loadMissing();
            Path path = eventsPath(id);
            boolean known = sessions.containsKey(id) && !sessions.get(id).isClosed();
            if (!known && !Files.isRegularFile(path)) throw new CdpException("session " + id + " not found");
            ArrayNode events = readEvents(path);
            ObjectNode node = Jsons.object();
            node.put("id", id);
            node.put("count", events.size());
            node.set("events", events);
            return node;
        }
    }

    /**
     * Stream the session event file. Existing lines are written first.
     * The call returns after {@code id} leaves the registry, including the close line.
     */
    public String follow(String id) {
        if (id == null || id.isBlank()) throw new CdpException("missing --id");
        Path dir = directory();
        Path path = dir.resolve(id + ".events.jsonl");
        if (!inRegistry(dir, id) && !Files.isRegularFile(path)) {
            throw new CdpException("session " + id + " not found");
        }
        StringBuilder out = new StringBuilder();
        long offset = 0;
        while (true) {
            offset = appendNew(path, offset, out);
            if (!inRegistry(dir, id)) {
                appendNew(path, offset, out);
                break;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new CdpException("session log interrupted");
            }
        }
        return out.toString();
    }

    public void record(String id, String kind, ObjectNode detail) {
        if (id == null || id.isBlank()) return;
        synchronized (this) {
            recordEvent(id, kind, detail == null ? Jsons.object() : detail);
        }
    }

    /** Live session already using {@code cdp} (same rules as {@code session join --cdp}), or null. */
    public BrowserSession findCdp(String cdp) {
        if (cdp == null || cdp.isBlank()) return null;
        synchronized (this) {
            loadMissing();
            String id = findByCdp(BrowserSession.normalize(cdp));
            return id == null ? null : sessions.get(id);
        }
    }

    private String findByCdp(String wanted) {
        String wantedKey = endpointKey(wanted);
        for (BrowserSession session : sessions.values()) {
            if (session.isClosed() || session.cdp == null) continue;
            if (wanted.equals(session.cdp) || wantedKey.equals(endpointKey(session.cdp))) return session.id;
        }
        // A browser-level join (no page target) still reuses any session on that host:port.
        if (wantedKey.contains("/devtools/page/")) return null;
        for (BrowserSession session : sessions.values()) {
            if (session.isClosed() || session.cdp == null) continue;
            String have = endpointKey(session.cdp);
            int page = have.indexOf("/devtools/page/");
            if (page > 0 && wantedKey.equals(have.substring(0, page))) return session.id;
        }
        return null;
    }

    private static String endpointKey(String cdp) {
        String value = BrowserSession.normalize(cdp);
        int scheme = value.indexOf("://");
        int start = scheme < 0 ? 0 : scheme + 3;
        int slash = value.indexOf('/', start);
        String page = "";
        if (slash > 0) {
            int marker = value.indexOf("/devtools/page/", slash);
            if (marker >= 0) page = value.substring(marker);
            value = value.substring(start, slash);
        } else {
            value = value.substring(start);
        }
        return value.toLowerCase(java.util.Locale.ROOT) + page;
    }

    private void loadMissing() {
        Path file = directory().resolve("registry.json");
        if (!Files.isRegularFile(file)) return;
        JsonNode root;
        try {
            root = Jsons.MAPPER.readTree(Files.readString(file));
        } catch (Exception failure) {
            return;
        }
        if ((joined == null || joined.isBlank()) && root.hasNonNull("joined")) {
            String recorded = root.get("joined").asText("");
            if (!recorded.isBlank()) joined = recorded;
        }
        JsonNode map = root.get("sessions");
        if (map == null || !map.isObject()) return;
        map.fields().forEachRemaining(entry -> {
            if (sessions.containsKey(entry.getKey())) return;
            JsonNode rec = entry.getValue();
            String cdp = rec.path("cdp").asText("");
            if (cdp.isBlank()) return;
            try {
                boolean launched = rec.path("launched").asBoolean(false);
                long pid = rec.path("pid").asLong(0);
                String profile = rec.hasNonNull("profile") ? rec.get("profile").asText() : null;
                BrowserSession session = BrowserSession.reopen(
                        entry.getKey(), cdp, launched, rec.path("process").asBoolean(false), pid, profile);
                sessions.put(entry.getKey(), session);
            } catch (RuntimeException ignored) {
                // A dead endpoint stays out of the live map. The event file can still be read.
            }
        });
    }

    private void recordEvent(String id, String kind, ObjectNode detail) {
        ObjectNode event = Jsons.object();
        event.put("ts", Instant.now().toString());
        event.put("kind", kind);
        event.put("session", id);
        event.set("detail", detail == null ? Jsons.object() : detail);
        EngineLog.info("action", "session=" + id + " " + kind);
        try {
            Files.createDirectories(directory());
            Path path = eventsPath(id);
            Files.writeString(path, Jsons.compact(event) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            rotateIfNeeded(path, id);
        } catch (IOException failure) {
            EngineLog.warn("session", "event log: " + failure.getMessage());
        }
    }

    private void rotateIfNeeded(Path path, String id) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) <= EngineLog.DEFAULT_CAP_BYTES) return;
        String date = LocalDate.now(ZoneOffset.UTC).toString();
        for (int n = 1; n < 10_000; n++) {
            Path dest = directory().resolve(id + ".events." + date + "." + n + ".jsonl");
            if (Files.exists(dest)) continue;
            Files.move(path, dest);
            EngineLog.info("action", "session=" + id + " events rolled to " + dest);
            return;
        }
    }

    private void save() {
        try {
            Files.createDirectories(directory());
            ObjectNode body = Jsons.object();
            if (joined == null) body.putNull("joined");
            else body.put("joined", joined);
            ObjectNode map = body.putObject("sessions");
            for (BrowserSession session : sessions.values()) {
                if (!session.isClosed()) map.set(session.id, session.record());
            }
            Path path = directory().resolve("registry.json");
            Path tmp = directory().resolve("registry.json.tmp");
            Files.writeString(tmp, Jsons.pretty(body), StandardCharsets.UTF_8);
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException failure) {
            try {
                Files.move(directory().resolve("registry.json.tmp"), directory().resolve("registry.json"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                EngineLog.warn("session", "registry save: " + failure.getMessage());
            }
        } catch (IOException failure) {
            EngineLog.warn("session", "registry save: " + failure.getMessage());
        }
    }

    private Path eventsPath(String id) {
        return directory().resolve(id + ".events.jsonl");
    }

    private static boolean inRegistry(Path dir, String id) {
        Path file = dir.resolve("registry.json");
        if (!Files.isRegularFile(file)) return false;
        try {
            JsonNode root = Jsons.MAPPER.readTree(Files.readString(file));
            JsonNode sessions = root.get("sessions");
            return sessions != null && sessions.has(id);
        } catch (Exception failure) {
            return false;
        }
    }

    private static long appendNew(Path path, long offset, StringBuilder out) {
        if (!Files.isRegularFile(path)) return offset;
        try {
            String text = Files.readString(path);
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            int start = offset > bytes.length ? 0 : (int) offset;
            String rest = new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
            for (String line : rest.split("\\R", -1)) {
                if (line.isBlank()) continue;
                out.append(line).append('\n');
            }
            return bytes.length;
        } catch (IOException failure) {
            return offset;
        }
    }

    private static ArrayNode readEvents(Path path) {
        ArrayNode events = Jsons.MAPPER.createArrayNode();
        if (!Files.isRegularFile(path)) return events;
        try {
            for (String line : Files.readString(path).split("\\R")) {
                if (line.isBlank()) continue;
                try {
                    events.add(Jsons.MAPPER.readTree(line));
                } catch (Exception ignored) {
                    // A torn line is not an event.
                }
            }
        } catch (IOException ignored) {
            return events;
        }
        return events;
    }

    private static void scrub(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            stream.forEach(path -> {
                String name = path.getFileName().toString();
                if ("registry.json".equals(name) || "registry.json.tmp".equals(name) || name.endsWith(".events.jsonl")
                        || (name.contains(".events.") && name.endsWith(".jsonl"))) {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // The next test uses its own directory.
                    }
                }
            });
        } catch (IOException ignored) {
            // Nothing to scrub.
        }
    }
}
