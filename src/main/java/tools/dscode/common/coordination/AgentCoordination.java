package tools.dscode.common.coordination;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import tools.dscode.control.protocol.ControlProtocol;
import tools.dscode.control.protocol.PickleballLocalLayout;
import tools.dscode.control.protocol.WindowDriver;
import tools.dscode.testengine.PKB_props;
import tools.dscode.testengine.PickleballRunner;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Shared bulletin board for consumer agents, with private data per run.
 *
 * <p>Project history stays at the {@code .pickleball} root: the short log,
 * inbox, runs, investigations, presence, posts, and {@code history.log}.
 * The jar cache under {@code v/<version>/} is separate. Appending the short
 * log does not take a file lock. A dead agent must not block the others.
 * Lines older than three days are dropped on the next append. A dropped run
 * whose directory is still there keeps one index stub. Launcher commands are
 * one-shot. They are not daemons, and they do not delete a run, a profile,
 * or this log on finish.</p>
 */
public final class AgentCoordination {
    public static final String LOG_FILE = "agent-log";
    public static final String INBOX_DIRECTORY = "inbox";
    public static final String RUNS_DIRECTORY = "runs";
    public static final String PRESENCE_DIRECTORY = "presence";
    public static final String POSTS_DIRECTORY = "posts";
    public static final String HISTORY_FILE = "history.log";
    public static final String ANY_AGENT = "any";
    public static final String ALL_AGENTS = "all";
    public static final String RECORD_FILE = "record.json";
    public static final String RUN_PROFILE_FILE = "pkb_run_profile";
    public static final Duration RETENTION = Duration.ofDays(3);
    public static final Duration PRESENCE_STALE = Duration.ofMinutes(15);
    public static final Duration POST_TTL = Duration.ofHours(24);
    public static final Duration RUN_PAYLOAD_STALE = Duration.ofHours(24);
    public static final long HISTORY_MAX_BYTES = 10L * 1024L * 1024L;
    public static final int PURPOSE_LIMIT = 240;

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final DateTimeFormatter RUN_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS'Z'")
            .withZone(ZoneOffset.UTC);
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,119}");
    private static final String ATTACHMENT_TEMP_PROPERTY = "dscode.logging.attachmentTempRoot";

    private static volatile Run current;

    private AgentCoordination() {
    }

    public record Request(
            String runId,
            String agentId,
            String group,
            Integer sequence,
            String who,
            String why,
            String purpose
    ) {
        public static Request of(
                String runId,
                String agentId,
                String group,
                Integer sequence,
                String who,
                String why,
                String purpose
        ) {
            return new Request(
                    blankToNull(runId),
                    blankToNull(agentId),
                    blankToNull(group),
                    sequence,
                    blankToNull(who),
                    blankToNull(why),
                    blankToNull(purpose)
            );
        }
    }

    public record Run(
            Path project,
            String runId,
            String agentId,
            String group,
            Integer sequence,
            String who,
            String why,
            Path dataDirectory,
            Path recordFile
    ) {
        public Path reportsDirectory() {
            return dataDirectory.resolve("reports");
        }

        public Path diagnosticDirectory() {
            return dataDirectory.resolve("diagnostic");
        }

        public Path scratchDirectory() {
            return dataDirectory.resolve("scratch");
        }

        public Path browserProfileDirectory() {
            return dataDirectory.resolve("browser-profile");
        }

        public Path configDirectory() {
            return dataDirectory.resolve(RunConfigCopy.DIRECTORY_NAME);
        }

        public Path sessionDirectory() {
            return dataDirectory.resolve("session");
        }

        public Path sessionStateFile() {
            return sessionDirectory().resolve("cli-session.json");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RunRecord(
            String runId,
            String agentId,
            String group,
            Integer sequence,
            String who,
            String why,
            String learned,
            String startedAt,
            String stoppedAt,
            String status,
            String dataPath
    ) {
    }

    public record InboxNote(Path file, String from, String at, String message, String record) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record InboxFile(String from, String at, String message, String record) {
    }

    public static Run current() {
        return current;
    }

    public static void clearCurrent() {
        Run run = current;
        current = null;
        if (run == null) return;
        String scratch = System.getProperty(ATTACHMENT_TEMP_PROPERTY);
        if (scratch != null && scratch.equals(run.scratchDirectory().toString())) {
            System.clearProperty(ATTACHMENT_TEMP_PROPERTY);
        }
    }

    /**
     * True when this JVM was launched as an agent run ({@code -Dpkb_run_id} or
     * {@code -Dpkb_agent_id}) or a Workbench worker. A normal {@code mvn test}
     * is neither.
     */
    public static boolean launchRequestsPrivateRun() {
        return present(System.getProperty(PKB_props.PKB_RUN_ID))
                || present(System.getProperty(PKB_props.PKB_AGENT_ID))
                || present(System.getProperty(ControlProtocol.WORKBENCH_TEST_OUTPUT_ROOT_PROPERTY))
                || present(System.getenv(ControlProtocol.SESSION_DIRECTORY_ENV));
    }

    /** Folder name for the local browser profile of the current parallel worker. */
    public static String workerKey() {
        String name = Thread.currentThread().getName();
        String cleaned = name == null ? "" : name.replaceAll("[^A-Za-z0-9._-]", "-");
        if (cleaned.isBlank()) cleaned = "worker";
        if (cleaned.length() > 60) cleaned = cleaned.substring(0, 60);
        return cleaned + "-" + Thread.currentThread().threadId();
    }

    public static Path currentBrowserProfile() {
        Run run = current;
        return run == null ? null : run.browserProfileDirectory().resolve(workerKey());
    }

    /**
     * Explicit {@code pkb_compositereport} / {@code pkb_scenarioreport} value, or null when unset.
     * A blank value is explicit. Does not start a {@link PickleballRunner}.
     */
    public static String explicitHtmlSetting(String shortName) {
        if (shortName == null || shortName.isBlank()) return null;
        String key = PKB_props.PKB_PREFIX + shortName.trim().toLowerCase(Locale.ROOT);
        if (PickleballRunner.rawInstance() != null) {
            String live = liveRunVar(shortName.trim());
            if (live != null) return live;
            String resolved = PickleballRunner.rawInstance().get(key);
            if (resolved != null) return resolved;
        }
        return System.getProperty(key);
    }

    /**
     * Whether to write one HTML report. {@code false} suppresses it.
     * Unset writes on a normal test and does not write on an agent or Workbench run.
     * Any other non-blank value, including {@code true} or a path, writes.
     */
    public static boolean htmlEnabled(String shortName) {
        if (explicitHtmlSetting(shortName) == null) return current() == null;
        return explicitHtmlOn(shortName);
    }

    /** True only when the HTML run var is set to something other than false or blank. */
    public static boolean explicitHtmlOn(String shortName) {
        String explicit = explicitHtmlSetting(shortName);
        if (explicit == null) return false;
        String normalized = stripHtmlQuotes(explicit.trim());
        return !normalized.isBlank() && !"false".equalsIgnoreCase(normalized);
    }

    public static Path reportHtmlOrDefault() {
        Run run = current;
        if (run == null) return Path.of("reports", "cucumber-report.html");
        return run.reportsDirectory().resolve("cucumber-report.html");
    }

    public static Path logFile(Path project) {
        return PickleballLocalLayout.root(project).resolve(LOG_FILE);
    }

    public static Path runDirectory(Path project, String runId) {
        return PickleballLocalLayout.root(project).resolve(RUNS_DIRECTORY).resolve(requireSafeId(runId, "run id"));
    }

    public static Run begin(Path project, Request request) {
        return begin(project, request, Instant.now(), null, null);
    }

    public static Run begin(Path project, Request request, Instant when, Appendable out) {
        return begin(project, request, when, out, null);
    }

    public static Run begin(Path project, Request request, Instant when, Appendable out, String configSource) {
        Instant clock = when == null ? Instant.now() : when;
        Request safe = request == null ? Request.of(null, null, null, null, null, null, null) : request;
        String runId = safe.runId() == null ? newRunId(clock) : requireSafeId(safe.runId(), "run id");
        Path data = runDirectory(project, runId);
        Path recordFile = data.resolve(RECORD_FILE);
        try {
            if (Files.isRegularFile(recordFile)) {
                RunRecord existing = readRecord(recordFile);
                ensureTree(data);
                ensureConfigs(project, data, configSource);
                Run adopted = toRun(project, data, recordFile, existing);
                activate(adopted);
                print(out, "run-id=" + adopted.runId());
                print(out, "run-record=" + adopted.recordFile());
                print(out, "short-log=" + logFile(project));
                return adopted;
            }
            boolean generatedAgent = safe.agentId() == null;
            String agentId = generatedAgent ? newAgentId() : requireSafeId(safe.agentId(), "agent id");
            if (ANY_AGENT.equals(agentId)) {
                throw new IllegalArgumentException("agent id 'any' is reserved for the shared inbox.");
            }
            ensureTree(data);
            ensureConfigs(project, data, configSource);
            String purpose = oneLine(firstNonBlank(safe.why(), safe.purpose(), "run"));
            RunRecord record = new RunRecord(
                    runId,
                    agentId,
                    safe.group(),
                    safe.sequence(),
                    safe.who(),
                    safe.why(),
                    null,
                    clock.toString(),
                    null,
                    "RUNNING",
                    data.toString()
            );
            writeRecord(recordFile, record);
            appendLog(project, clock, agentId, runId, "start", purpose, recordFile);
            Run opened = toRun(project, data, recordFile, record);
            activate(opened);
            if (generatedAgent) print(out, "agent-id=" + agentId);
            print(out, "run-id=" + runId);
            print(out, "run-record=" + recordFile);
            print(out, "short-log=" + logFile(project));
            return opened;
        } catch (IOException failure) {
            throw new IllegalStateException("Could not open run " + runId + ": " + failure.getMessage(), failure);
        }
    }

    public static Run openConsumerRun(Path project, Appendable out) {
        return openConsumerRun(project, out, null);
    }

    public static Run openConsumerRun(Path project, Appendable out, String configSource) {
        if (!launchRequestsPrivateRun()) {
            clearCurrent();
            return null;
        }
        Request request = Request.of(
                System.getProperty(PKB_props.PKB_RUN_ID),
                System.getProperty(PKB_props.PKB_AGENT_ID),
                System.getProperty(PKB_props.PKB_RUN_GROUP),
                parseOptionalSequence(System.getProperty(PKB_props.PKB_RUN_SEQUENCE)),
                System.getProperty(PKB_props.PKB_RUN_WHO),
                System.getProperty(PKB_props.PKB_RUN_WHY),
                "consumer-run"
        );
        Run run = begin(project, request, Instant.now(), out, configSource);
        System.setProperty(PKB_props.PKB_RUN_ID, run.runId());
        System.setProperty(PKB_props.PKB_AGENT_ID, run.agentId());
        return run;
    }

    public static void finish(Path project, String runId, String learned, String status) {
        finish(project, runId, learned, status, Instant.now(), null);
    }

    public static void finish(
            Path project,
            String runId,
            String learned,
            String status,
            Instant when,
            Appendable out
    ) {
        String safeRun = requireSafeId(runId, "run id");
        Instant clock = when == null ? Instant.now() : when;
        Path recordFile = runDirectory(project, safeRun).resolve(RECORD_FILE);
        if (!Files.isRegularFile(recordFile)) {
            throw new IllegalStateException("No run record for " + safeRun + " at " + recordFile);
        }
        try {
            RunRecord existing = readRecord(recordFile);
            boolean alreadyStopped = existing.stoppedAt() != null && !existing.stoppedAt().isBlank();
            String learnedValue = blankToNull(learned);
            String keptLearned = learnedValue != null ? learnedValue : existing.learned();
            String statusValue = blankToNull(status);
            if (!alreadyStopped) {
                if (statusValue == null) statusValue = "STOPPED";
                RunRecord updated = new RunRecord(
                        existing.runId(),
                        existing.agentId(),
                        existing.group(),
                        existing.sequence(),
                        existing.who(),
                        existing.why(),
                        keptLearned,
                        existing.startedAt(),
                        clock.toString(),
                        statusValue,
                        existing.dataPath()
                );
                writeRecord(recordFile, updated);
                String purpose = oneLine(firstNonBlank(learnedValue, existing.why(), statusValue));
                appendLog(project, clock, existing.agentId(), existing.runId(), "stop", purpose, recordFile);
                print(out, "run-id=" + existing.runId());
                print(out, "run-record=" + recordFile);
                return;
            }
            if (learnedValue != null && !learnedValue.equals(existing.learned())) {
                RunRecord updated = new RunRecord(
                        existing.runId(),
                        existing.agentId(),
                        existing.group(),
                        existing.sequence(),
                        existing.who(),
                        existing.why(),
                        learnedValue,
                        existing.startedAt(),
                        existing.stoppedAt(),
                        existing.status(),
                        existing.dataPath()
                );
                writeRecord(recordFile, updated);
                appendLog(
                        project,
                        clock,
                        existing.agentId(),
                        existing.runId(),
                        "note",
                        oneLine(learnedValue),
                        recordFile
                );
            }
            print(out, "run-id=" + existing.runId());
            print(out, "run-record=" + recordFile);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not finish run " + safeRun + ": " + failure.getMessage(), failure);
        }
    }

    public static void finishCurrent(String status, String learned, Appendable out) {
        Run run = current;
        if (run == null) return;
        Path record = run.recordFile();
        if (!Files.isRegularFile(record)) return;
        finish(run.project(), run.runId(), learned, status, Instant.now(), out);
    }

    public static void appendLog(
            Path project,
            Instant when,
            String agentId,
            String runId,
            String event,
            String purpose,
            Path record
    ) throws IOException {
        Instant clock = when == null ? Instant.now() : when;
        String line = String.join("\t",
                clock.toString(),
                oneField(agentId),
                oneField(runId),
                oneField(event),
                oneLine(purpose),
                record == null ? "-" : oneField(record.toString())
        );
        Path file = logFile(project);
        Files.createDirectories(file.getParent());
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(
                file,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND
        )) {
            channel.write(ByteBuffer.wrap(bytes));
        }
        pruneIfStale(project, file, clock);
    }

    public static List<String> readLog(Path project) throws IOException {
        Path file = logFile(project);
        if (!Files.isRegularFile(file)) return List.of();
        List<String> lines = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) lines.add(line);
        }
        return List.copyOf(lines);
    }

    public static List<String> recentLog(Path project, int limit) throws IOException {
        List<String> lines = readLog(project);
        if (limit <= 0 || lines.size() <= limit) return lines;
        return List.copyOf(lines.subList(lines.size() - limit, lines.size()));
    }

    public static void note(Path project, String agentId, String runId, String purpose, Path record, Appendable out) {
        String agent = agentId == null || agentId.isBlank() ? newAgentId() : requireSafeId(agentId, "agent id");
        String run = runId == null || runId.isBlank() ? "-" : requireSafeId(runId, "run id");
        Path recordPath = record;
        if ("-".equals(run)) recordPath = null;
        try {
            appendLog(project, Instant.now(), agent, run, "note", purpose, recordPath);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not append short-log note: " + failure.getMessage(), failure);
        }
        if (agentId == null || agentId.isBlank()) print(out, "agent-id=" + agent);
        print(out, "short-log=" + logFile(project));
    }

    public static Path writeInbox(Path project, String toAgent, String fromAgent, String message, String recordPath) {
        String target = toAgent == null || toAgent.isBlank() || ANY_AGENT.equalsIgnoreCase(toAgent.trim())
                ? ANY_AGENT
                : requireSafeId(toAgent, "agent id");
        String from = fromAgent == null || fromAgent.isBlank() ? newAgentId() : requireSafeId(fromAgent, "agent id");
        String text = message == null ? "" : message.strip();
        if (text.isEmpty()) throw new IllegalArgumentException("An inbox note needs one message.");
        if (text.contains("\n") || text.contains("\r")) {
            throw new IllegalArgumentException("An inbox note is one line.");
        }
        if (ANY_AGENT.equals(target)) {
            return writePost(project, from, ALL_AGENTS, null, text, null, Instant.now()).file();
        }
        Path dir = inboxDirectory(project, target);
        String name = Instant.now().toEpochMilli() + "-" + UUID.randomUUID() + ".json";
        Path file = dir.resolve(name);
        try {
            Files.createDirectories(dir);
            InboxFile body = new InboxFile(from, Instant.now().toString(), text, blankToNull(recordPath));
            writeJson(file, body);
            return file;
        } catch (IOException failure) {
            throw new IllegalStateException("Could not write inbox note: " + failure.getMessage(), failure);
        }
    }

    public static List<InboxNote> listInbox(Path project, String agentId) {
        return List.copyOf(readInbox(project, agentId, false));
    }

    public static List<InboxNote> takeInbox(Path project, String agentId) {
        return List.copyOf(readInbox(project, agentId, true));
    }

    public record Presence(String agentId, String lastSeen, List<String> runIds, Path file) {
    }

    public record BoardPost(
            String id,
            String from,
            String to,
            String runId,
            String text,
            String createdAt,
            String updatedAt,
            String expiresAt,
            List<String> ackedBy,
            Path file
    ) {
    }

    public record RunSweep(int profilesRemoved, int payloadsRemoved) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PresenceFile(String agentId, String lastSeen, List<String> runIds) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PostFile(
            String id,
            String from,
            String to,
            String runId,
            String text,
            String createdAt,
            String updatedAt,
            String expiresAt,
            List<String> ackedBy
    ) {
    }

    public static Presence touchPresence(Path project, String agentId, String runId, Instant when) {
        String agent = requireSafeId(agentId, "agent id");
        if (ANY_AGENT.equals(agent) || ALL_AGENTS.equals(agent)) {
            throw new IllegalArgumentException("presence is for one agent, not '" + agent + "'.");
        }
        Instant clock = when == null ? Instant.now() : when;
        Path file = presenceFile(project, agent);
        List<String> runs = new ArrayList<>();
        if (Files.isRegularFile(file)) {
            try {
                PresenceFile existing = JSON.readValue(file.toFile(), PresenceFile.class);
                if (existing.runIds() != null) {
                    for (String id : existing.runIds()) {
                        if (id != null && !id.isBlank() && !runs.contains(id)) runs.add(id);
                    }
                }
            } catch (IOException ignored) {
                // A broken row is replaced by this touch.
            }
        }
        if (runId != null && !runId.isBlank()) {
            String safeRun = requireSafeId(runId, "run id");
            if (!runs.contains(safeRun)) runs.add(safeRun);
        }
        PresenceFile body = new PresenceFile(agent, clock.toString(), List.copyOf(runs));
        try {
            writeJson(file, body);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not write presence for " + agent + ": " + failure.getMessage(), failure);
        }
        return new Presence(agent, body.lastSeen(), body.runIds(), file);
    }

    public static List<Presence> listPresence(Path project) {
        Path dir = presenceDirectory(project);
        if (!Files.isDirectory(dir)) return List.of();
        List<Presence> rows = new ArrayList<>();
        for (Path file : noteFiles(dir)) {
            try {
                PresenceFile body = JSON.readValue(file.toFile(), PresenceFile.class);
                String agent = body.agentId() == null || body.agentId().isBlank()
                        ? file.getFileName().toString().replaceFirst("\\.json$", "")
                        : body.agentId();
                List<String> runs = body.runIds() == null ? List.of() : List.copyOf(body.runIds());
                rows.add(new Presence(agent, body.lastSeen() == null ? "" : body.lastSeen(), runs, file));
            } catch (IOException ignored) {
                // An unreadable row is not a heartbeat.
            }
        }
        rows.sort(Comparator.comparing(Presence::agentId));
        return List.copyOf(rows);
    }

    /** Deletes expired presence rows only. Posts, runs, profiles, and history.log stay. */
    public static int sweepPresence(Path project, Instant when) {
        Instant clock = when == null ? Instant.now() : when;
        int removed = 0;
        for (Presence row : listPresence(project)) {
            if (!presenceExpired(row.lastSeen(), clock)) continue;
            try {
                if (row.file() != null && Files.deleteIfExists(row.file())) removed++;
            } catch (IOException ignored) {
                // Another sweeper already removed the row.
            }
        }
        Path dir = presenceDirectory(project);
        if (!Files.isDirectory(dir)) return removed;
        for (Path file : noteFiles(dir)) {
            if (readablePresence(file)) continue;
            try {
                if (Files.deleteIfExists(file)) removed++;
            } catch (IOException ignored) {
                // Idempotent: a second delete of the same file is fine.
            }
        }
        return removed;
    }

    public static BoardPost writePost(
            Path project,
            String fromAgent,
            String toAgent,
            String runId,
            String message,
            Duration ttl,
            Instant when
    ) {
        String from = fromAgent == null || fromAgent.isBlank() ? newAgentId() : requireSafeId(fromAgent, "agent id");
        String to = normalizePostTarget(toAgent);
        String text = onePostLine(message);
        Instant clock = when == null ? Instant.now() : when;
        Duration life = ttl == null ? POST_TTL : ttl;
        if (life.isZero() || life.isNegative()) {
            throw new IllegalArgumentException("A post ttl must be positive. The default is 24 hours.");
        }
        String id = clock.toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 8);
        String run = runId == null || runId.isBlank() ? null : requireSafeId(runId, "run id");
        String created = clock.toString();
        String expires = clock.plus(life).toString();
        PostFile body = new PostFile(id, from, to, run, text, created, created, expires, List.of());
        Path file = postsDirectory(project).resolve(id + ".json");
        try {
            writeJson(file, body);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not write post: " + failure.getMessage(), failure);
        }
        return toBoardPost(body, file);
    }

    public static List<BoardPost> listPosts(Path project, Instant when) {
        Instant clock = when == null ? Instant.now() : when;
        List<BoardPost> posts = new ArrayList<>();
        for (Path file : noteFiles(postsDirectory(project))) {
            BoardPost post = readPost(file);
            if (post == null || postExpired(post.expiresAt(), clock)) continue;
            posts.add(post);
        }
        posts.sort(Comparator.comparing(BoardPost::updatedAt).thenComparing(BoardPost::id));
        return List.copyOf(posts);
    }

    public static BoardPost renewPost(Path project, String id, Duration ttl, Instant when) {
        String safe = requireSafeId(stripJsonSuffix(id), "post id");
        Path file = postsDirectory(project).resolve(safe + ".json");
        BoardPost existing = readPost(file);
        if (existing == null) {
            throw new IllegalArgumentException("No post " + safe + " at " + file);
        }
        Instant clock = when == null ? Instant.now() : when;
        Duration life = ttl == null ? POST_TTL : ttl;
        if (life.isZero() || life.isNegative()) {
            throw new IllegalArgumentException("A post ttl must be positive. The default is 24 hours.");
        }
        PostFile body = new PostFile(
                existing.id(),
                existing.from(),
                existing.to(),
                existing.runId(),
                existing.text(),
                existing.createdAt(),
                clock.toString(),
                clock.plus(life).toString(),
                existing.ackedBy()
        );
        try {
            writeJson(file, body);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not renew post " + safe + ": " + failure.getMessage(), failure);
        }
        return toBoardPost(body, file);
    }

    /** Deletes expired post files only. Runs, profiles, presence, and history.log stay. */
    public static int sweepPosts(Path project, Instant when) {
        Instant clock = when == null ? Instant.now() : when;
        int removed = 0;
        for (Path file : noteFiles(postsDirectory(project))) {
            BoardPost post = readPost(file);
            boolean expired = post == null || postExpired(post.expiresAt(), clock);
            if (!expired) continue;
            try {
                if (Files.deleteIfExists(file)) removed++;
            } catch (IOException ignored) {
                // Two deletes of the same file are fine.
            }
        }
        return removed;
    }

    public static Path historyFile(Path project) {
        return PickleballLocalLayout.root(project).resolve(HISTORY_FILE);
    }

    public static void appendHistory(Path project, String text) {
        String line = onePostLine(text);
        Path file = historyFile(project);
        try {
            Files.createDirectories(file.getParent());
            byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(
                    file,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            )) {
                channel.write(ByteBuffer.wrap(bytes));
            }
            trimHistory(file);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not append history.log: " + failure.getMessage(), failure);
        }
    }

    public static List<String> tailHistory(Path project, int limit) throws IOException {
        Path file = historyFile(project);
        if (!Files.isRegularFile(file)) return List.of();
        List<String> lines = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) lines.add(line);
        }
        if (limit <= 0 || lines.size() <= limit) return List.copyOf(lines);
        return List.copyOf(lines.subList(lines.size() - limit, lines.size()));
    }

    /**
     * Explicit payload sweep. Not called from finish. Deletes a browser profile
     * only when that run has a stop line, nothing has the profile open, and no
     * agent holds the run. Other dense payload waits 24 hours after stoppedAt,
     * and never removes the run open in Workbench or the last failed run.
     * record.json, sparse indexes, and pkb_run_profile stay. The run directory
     * stays. Two sweepers deleting the same file are safe.
     */
    public static RunSweep sweepRuns(Path project, Instant when) {
        Instant clock = when == null ? Instant.now() : when;
        Path runs = PickleballLocalLayout.root(project).resolve(RUNS_DIRECTORY);
        if (!Files.isDirectory(runs)) return new RunSweep(0, 0);
        List<Path> directories;
        try (var children = Files.list(runs)) {
            directories = children.filter(Files::isDirectory).toList();
        } catch (IOException ignored) {
            return new RunSweep(0, 0);
        }
        String lastFailed = lastFailedRun(directories);
        int profiles = 0;
        int payloads = 0;
        for (Path directory : directories) {
            RunRecord record = readRecordQuiet(directory.resolve(RECORD_FILE));
            if (record == null || held(project, directory, record, clock)) continue;
            if (deleteTreeContents(directory.resolve("browser-profile"))) profiles++;
            if (record.runId() != null && record.runId().equals(lastFailed)) continue;
            if (!payloadExpired(record, clock)) continue;
            if (deleteDensePayload(directory)) payloads++;
        }
        return new RunSweep(profiles, payloads);
    }

    public static List<String> mavenProperties(Run run) {
        List<String> properties = new ArrayList<>();
        properties.add("-D" + PKB_props.PKB_RUN_ID + "=" + run.runId());
        properties.add("-D" + PKB_props.PKB_AGENT_ID + "=" + run.agentId());
        if (run.group() != null) properties.add("-D" + PKB_props.PKB_RUN_GROUP + "=" + oneLine(run.group()));
        if (run.sequence() != null) properties.add("-D" + PKB_props.PKB_RUN_SEQUENCE + "=" + run.sequence());
        if (run.who() != null) properties.add("-D" + PKB_props.PKB_RUN_WHO + "=" + oneLine(run.who()));
        if (run.why() != null) properties.add("-D" + PKB_props.PKB_RUN_WHY + "=" + oneLine(run.why()));
        return List.copyOf(properties);
    }

    public static String requireSafeId(String id, String label) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        String value = id.trim();
        if (!SAFE_ID.matcher(value).matches() || value.contains("..")) {
            throw new IllegalArgumentException(
                    label + " must be a single folder name (letters, digits, '.', '_' or '-'): " + id
            );
        }
        return value;
    }

    public static String oneLine(String value) {
        if (value == null || value.isBlank()) return "-";
        String collapsed = value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').strip();
        if (collapsed.isEmpty()) return "-";
        if (collapsed.length() <= PURPOSE_LIMIT) return collapsed;
        return collapsed.substring(0, PURPOSE_LIMIT);
    }

    private static List<InboxNote> readInbox(Path project, String agentId, boolean take) {
        List<Path> files = new ArrayList<>();
        if (agentId != null && !agentId.isBlank() && !ANY_AGENT.equalsIgnoreCase(agentId.trim())
                && !ALL_AGENTS.equalsIgnoreCase(agentId.trim())) {
            files.addAll(noteFiles(inboxDirectory(project, requireSafeId(agentId, "agent id"))));
        }
        files.sort(Comparator.comparing(path -> path.getFileName().toString()));
        List<InboxNote> notes = new ArrayList<>();
        for (Path file : files) {
            try {
                byte[] bytes = Files.readAllBytes(file);
                if (take && !Files.deleteIfExists(file)) continue;
                InboxFile body = JSON.readValue(bytes, InboxFile.class);
                notes.add(new InboxNote(
                        file,
                        body.from() == null ? "" : body.from(),
                        body.at() == null ? "" : body.at(),
                        body.message() == null ? "" : body.message(),
                        body.record()
                ));
            } catch (IOException ignored) {
                // A note that disappeared or cannot be read is not history.
            }
        }
        return notes;
    }

    private static List<Path> noteFiles(Path directory) {
        if (!Files.isDirectory(directory)) return List.of();
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                    .toList();
        } catch (IOException ignored) {
            return List.of();
        }
    }

    private static Path inboxDirectory(Path project, String agentFolder) {
        return PickleballLocalLayout.root(project).resolve(INBOX_DIRECTORY).resolve(agentFolder);
    }

    private static void ensureConfigs(Path project, Path data, String configSource) throws IOException {
        RunConfigCopy.copyIfAbsent(project, data.resolve(RunConfigCopy.DIRECTORY_NAME), configSource);
    }

    private static void ensureTree(Path data) throws IOException {
        Files.createDirectories(data.resolve("reports"));
        Files.createDirectories(data.resolve("diagnostic"));
        Files.createDirectories(data.resolve("scratch"));
        Files.createDirectories(data.resolve("browser-profile"));
        Files.createDirectories(data.resolve("session"));
        Files.writeString(data.resolve("run-id.txt"), data.getFileName().toString() + "\n", StandardCharsets.UTF_8);
    }

    private static void activate(Run run) {
        current = run;
        System.setProperty(ATTACHMENT_TEMP_PROPERTY, run.scratchDirectory().toString());
    }

    private static Run toRun(Path project, Path data, Path recordFile, RunRecord record) {
        return new Run(
                project.toAbsolutePath().normalize(),
                record.runId(),
                record.agentId(),
                record.group(),
                record.sequence(),
                record.who(),
                record.why(),
                data.toAbsolutePath().normalize(),
                recordFile.toAbsolutePath().normalize()
        );
    }

    private static void pruneIfStale(Path project, Path file, Instant now) throws IOException {
        if (!Files.isRegularFile(file)) return;
        byte[] snapshot = Files.readAllBytes(file);
        if (!containsStale(snapshot, now)) return;
        byte[] latest = Files.readAllBytes(file);
        byte[] source = latest.length >= snapshot.length ? latest : snapshot;
        LinkedHashMap<String, String[]> dropped = new LinkedHashMap<>();
        String filtered = filterLines(source, now, dropped);
        if (latest.length > source.length) {
            filtered = filtered + filterLines(slice(latest, source.length), now, dropped);
        }
        filtered = filtered + indexStubs(filtered, dropped, project, now);
        Path temp = file.resolveSibling(file.getFileName() + ".prune-" + UUID.randomUUID());
        Files.writeString(temp, filtered, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean containsStale(byte[] bytes, Instant now) {
        Instant cutoff = now.minus(RETENTION);
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n", -1)) {
            Instant stamp = timestampOf(line);
            if (stamp != null && stamp.isBefore(cutoff)) return true;
        }
        return false;
    }

    private static String filterLines(byte[] bytes, Instant now, LinkedHashMap<String, String[]> dropped) {
        Instant cutoff = now.minus(RETENTION);
        StringBuilder kept = new StringBuilder();
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n", -1)) {
            if (line.isBlank()) continue;
            Instant stamp = timestampOf(line);
            if (stamp != null && stamp.isBefore(cutoff)) {
                rememberDropped(dropped, line);
                continue;
            }
            kept.append(line).append('\n');
        }
        return kept.toString();
    }

    private static void rememberDropped(LinkedHashMap<String, String[]> dropped, String line) {
        String[] fields = line.split("\t", -1);
        if (fields.length < 3) return;
        String runId = fields[2].trim();
        if (runId.isBlank() || "-".equals(runId) || runId.contains("..") || runId.contains("/") || runId.contains("\\")) {
            return;
        }
        dropped.put(runId, fields);
    }

    private static String indexStubs(
            String kept,
            LinkedHashMap<String, String[]> dropped,
            Path project,
            Instant now
    ) {
        if (dropped.isEmpty() || project == null) return "";
        Set<String> present = new LinkedHashSet<>();
        for (String line : kept.split("\n", -1)) {
            if (line.isBlank()) continue;
            String[] fields = line.split("\t", -1);
            if (fields.length > 2) present.add(fields[2].trim());
        }
        StringBuilder stubs = new StringBuilder();
        Path runs = PickleballLocalLayout.root(project).resolve(RUNS_DIRECTORY);
        for (var entry : dropped.entrySet()) {
            String runId = entry.getKey();
            if (present.contains(runId)) continue;
            if (!Files.isDirectory(runs.resolve(runId))) continue;
            String[] fields = entry.getValue();
            String agent = fields.length > 1 && !fields[1].isBlank() ? fields[1] : "-";
            String record = fields.length > 5 && !fields[5].isBlank() ? fields[5] : "-";
            stubs.append(String.join("\t",
                    now.toString(),
                    oneField(agent),
                    oneField(runId),
                    "index",
                    "pruned",
                    oneField(record)
            )).append('\n');
        }
        return stubs.toString();
    }

    private static byte[] slice(byte[] bytes, int from) {
        byte[] extra = new byte[bytes.length - from];
        System.arraycopy(bytes, from, extra, 0, extra.length);
        return extra;
    }

    static Instant timestampOf(String line) {
        if (line == null || line.isBlank()) return null;
        int tab = line.indexOf('\t');
        String first = tab < 0 ? line.trim() : line.substring(0, tab).trim();
        try {
            return Instant.parse(first);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static RunRecord readRecord(Path file) throws IOException {
        return JSON.readValue(file.toFile(), RunRecord.class);
    }

    private static void writeRecord(Path file, RunRecord record) throws IOException {
        writeJson(file, record);
    }

    private static void writeJson(Path file, Object value) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp-" + UUID.randomUUID());
        JSON.writeValue(temp.toFile(), value);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String newRunId(Instant when) {
        String stamp = RUN_STAMP.format(when);
        return stamp + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String newAgentId() {
        return "agent-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static Integer parseOptionalSequence(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("pkb_run_sequence must be a whole number: " + value);
        }
    }

    private static String oneField(String value) {
        String cleaned = oneLine(value);
        return "-".equals(cleaned) ? "-" : cleaned;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank() && !"-".equals(value.trim())) return value;
        }
        return null;
    }

    private static String liveRunVar(String shortName) {
        try {
            Object value = tools.dscode.common.variables.RunVars.resolveFromVars(shortName);
            return value == null ? null : value.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String stripHtmlQuotes(String raw) {
        String value = raw;
        while ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))) {
            if (value.length() < 2) break;
            value = value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void print(Appendable out, String line) {
        if (out == null || line == null) return;
        try {
            out.append(line).append(System.lineSeparator());
        } catch (IOException ignored) {
            // Printing the id must not hide the run itself.
        }
    }

    private static final Set<String> SPARSE_NAMES = Set.of(
            "record.json",
            "run-id.txt",
            "run-index.json",
            "summary.json",
            "run-catalog.json",
            RUN_PROFILE_FILE,
            RUN_PROFILE_FILE + ".json",
            RUN_PROFILE_FILE + ".txt"
    );

    private static Path presenceDirectory(Path project) {
        return PickleballLocalLayout.root(project).resolve(PRESENCE_DIRECTORY);
    }

    private static Path presenceFile(Path project, String agentId) {
        return presenceDirectory(project).resolve(agentId + ".json");
    }

    private static Path postsDirectory(Path project) {
        return PickleballLocalLayout.root(project).resolve(POSTS_DIRECTORY);
    }

    private static boolean presenceExpired(String lastSeen, Instant now) {
        if (lastSeen == null || lastSeen.isBlank()) return true;
        try {
            return lastSeenInstant(lastSeen).isBefore(now.minus(PRESENCE_STALE));
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private static Instant lastSeenInstant(String lastSeen) {
        return Instant.parse(lastSeen.trim());
    }

    private static boolean readablePresence(Path file) {
        try {
            PresenceFile body = JSON.readValue(file.toFile(), PresenceFile.class);
            return body.lastSeen() != null && !body.lastSeen().isBlank() && lastSeenInstant(body.lastSeen()) != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String normalizePostTarget(String toAgent) {
        if (toAgent == null || toAgent.isBlank()
                || ANY_AGENT.equalsIgnoreCase(toAgent.trim())
                || ALL_AGENTS.equalsIgnoreCase(toAgent.trim())) {
            return ALL_AGENTS;
        }
        return requireSafeId(toAgent, "agent id");
    }

    private static String onePostLine(String message) {
        String text = message == null ? "" : message.strip();
        if (text.isEmpty()) throw new IllegalArgumentException("The line must not be empty.");
        if (text.contains("\n") || text.contains("\r")) {
            throw new IllegalArgumentException("The line must be one line.");
        }
        return oneLine(text);
    }

    private static BoardPost readPost(Path file) {
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            return toBoardPost(JSON.readValue(file.toFile(), PostFile.class), file);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static BoardPost toBoardPost(PostFile body, Path file) {
        String id = body.id() == null || body.id().isBlank()
                ? stripJsonSuffix(file.getFileName().toString())
                : body.id();
        List<String> acked = body.ackedBy() == null ? List.of() : List.copyOf(body.ackedBy());
        return new BoardPost(
                id,
                body.from() == null ? "" : body.from(),
                body.to() == null ? ALL_AGENTS : body.to(),
                blankToNull(body.runId()),
                body.text() == null ? "" : body.text(),
                body.createdAt() == null ? "" : body.createdAt(),
                body.updatedAt() == null ? "" : body.updatedAt(),
                body.expiresAt() == null ? "" : body.expiresAt(),
                acked,
                file
        );
    }

    private static boolean postExpired(String expiresAt, Instant now) {
        if (expiresAt == null || expiresAt.isBlank()) return true;
        try {
            return !Instant.parse(expiresAt.trim()).isAfter(now);
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private static String stripJsonSuffix(String name) {
        if (name == null) return "";
        return name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
    }

    private static void trimHistory(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) <= HISTORY_MAX_BYTES) return;
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length <= HISTORY_MAX_BYTES) return;
        int cut = bytes.length - (int) HISTORY_MAX_BYTES;
        while (cut < bytes.length && bytes[cut] != '\n') cut++;
        if (cut < bytes.length) cut++;
        if (cut >= bytes.length) cut = bytes.length - (int) HISTORY_MAX_BYTES;
        byte[] tail = slice(bytes, cut);
        Path temp = file.resolveSibling(file.getFileName() + ".trim-" + UUID.randomUUID());
        Files.write(temp, tail);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean held(Path project, Path directory, RunRecord record, Instant now) {
        if (record.stoppedAt() == null || record.stoppedAt().isBlank()) return true;
        if (record.status() != null && "RUNNING".equalsIgnoreCase(record.status().trim())) return true;
        if (Files.isRegularFile(directory.resolve("session").resolve("cli-session.json"))) return true;
        if (liveWorker(directory)) return true;
        if (profileOpen(directory)) return true;
        if (windowHolds(project, record.runId())) return true;
        return agentHolds(project, record.runId(), now);
    }

    private static boolean liveWorker(Path directory) {
        Path session = directory.resolve("session");
        if (Files.isRegularFile(session.resolve("worker.json"))) return true;
        Path pidFile = session.resolve("worker.pid");
        if (!Files.isRegularFile(pidFile)) return false;
        try {
            long pid = Long.parseLong(Files.readString(pidFile, StandardCharsets.UTF_8).trim());
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        } catch (Exception ignored) {
            return true;
        }
    }

    private static boolean profileOpen(Path directory) {
        Path profile = directory.resolve("browser-profile");
        if (!Files.isDirectory(profile)) return false;
        try (var paths = Files.walk(profile)) {
            return paths.anyMatch(path -> {
                Path name = path.getFileName();
                return name != null && "SingletonLock".equals(name.toString());
            });
        } catch (IOException ignored) {
            return true;
        }
    }

    private static boolean windowHolds(Path project, String runId) {
        if (runId == null || runId.isBlank()) return false;
        WindowDriver.State state = WindowDriver.read(project);
        if (!state.open()) return false;
        return runId.equals(state.liveRunId()) || runId.equals(state.viewedRunId());
    }

    private static boolean agentHolds(Path project, String runId, Instant now) {
        if (runId == null || runId.isBlank()) return false;
        for (Presence row : listPresence(project)) {
            if (presenceExpired(row.lastSeen(), now)) continue;
            if (row.runIds() != null && row.runIds().contains(runId)) return true;
        }
        return false;
    }

    private static String lastFailedRun(List<Path> directories) {
        String winner = null;
        Instant latest = null;
        for (Path directory : directories) {
            RunRecord record = readRecordQuiet(directory.resolve(RECORD_FILE));
            if (record == null || record.status() == null || !"FAILED".equalsIgnoreCase(record.status().trim())) {
                continue;
            }
            if (record.stoppedAt() == null || record.stoppedAt().isBlank()) continue;
            Instant stopped;
            try {
                stopped = Instant.parse(record.stoppedAt().trim());
            } catch (RuntimeException ignored) {
                continue;
            }
            if (latest == null || stopped.isAfter(latest)) {
                latest = stopped;
                winner = record.runId();
            }
        }
        return winner;
    }

    private static boolean payloadExpired(RunRecord record, Instant now) {
        if (record.stoppedAt() == null || record.stoppedAt().isBlank()) return false;
        try {
            return !Instant.parse(record.stoppedAt().trim()).isAfter(now.minus(RUN_PAYLOAD_STALE));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static RunRecord readRecordQuiet(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try {
            return readRecord(file);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static boolean deleteTreeContents(Path root) {
        if (!Files.exists(root)) return false;
        boolean removed = false;
        List<Path> paths;
        try (var walk = Files.walk(root)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        } catch (IOException ignored) {
            return false;
        }
        for (Path path : paths) {
            if (path.equals(root)) continue;
            try {
                if (Files.deleteIfExists(path)) removed = true;
            } catch (IOException ignored) {
                // Another sweeper removed it, or it is still open.
            }
        }
        try {
            Files.deleteIfExists(root);
        } catch (IOException ignored) {
            // The parent run directory is not this path.
        }
        return removed;
    }

    private static boolean deleteDensePayload(Path runDirectory) {
        boolean removed = false;
        List<Path> paths;
        try (var walk = Files.walk(runDirectory)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        } catch (IOException ignored) {
            return false;
        }
        for (Path path : paths) {
            if (path.equals(runDirectory)) continue;
            if (keepPayload(runDirectory, path)) continue;
            try {
                if (Files.deleteIfExists(path)) removed = true;
            } catch (IOException ignored) {
                // Two sweepers removing the same file are fine.
            }
        }
        return removed;
    }

    private static boolean keepPayload(Path runDirectory, Path path) {
        Path name = path.getFileName();
        if (name != null && SPARSE_NAMES.contains(name.toString())) return true;
        Path config = runDirectory.resolve("config");
        Path relative;
        try {
            relative = runDirectory.relativize(path);
        } catch (IllegalArgumentException ignored) {
            return true;
        }
        return relative.getNameCount() > 0 && "config".equals(relative.getName(0).toString())
                || path.startsWith(config);
    }

    static String normalizeEvent(String event) {
        if (event == null || event.isBlank()) return "note";
        return event.trim().toLowerCase(Locale.ROOT);
    }
}
