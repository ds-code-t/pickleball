package tools.dscode.common.coordination;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import tools.dscode.control.protocol.ControlProtocol;
import tools.dscode.control.protocol.PickleballLocalLayout;
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
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Shared bulletin board for consumer agents, with private data per run.
 *
 * <p>The short log and inbox live under the consumer {@code .pickleball} directory.
 * A private {@code .pickleball/runs/<run-id>} directory is only for an agent run
 * or a Workbench run. A normal test does not create that folder and does not
 * force a browser profile. Reports, diagnostic packs, scratch, per-worker
 * browser profiles, and a headless Workbench session file for a private run
 * live under that directory. Appending the short log does not take a file lock.
 * A dead agent must not block the others. Lines older than three days are
 * dropped on the next append.</p>
 */
public final class AgentCoordination {
    public static final String LOG_FILE = "agent-log";
    public static final String INBOX_DIRECTORY = "inbox";
    public static final String RUNS_DIRECTORY = "runs";
    public static final String ANY_AGENT = "any";
    public static final String RECORD_FILE = "record.json";
    public static final Duration RETENTION = Duration.ofDays(3);
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
        return begin(project, request, Instant.now(), null);
    }

    public static Run begin(Path project, Request request, Instant when, Appendable out) {
        Instant clock = when == null ? Instant.now() : when;
        Request safe = request == null ? Request.of(null, null, null, null, null, null, null) : request;
        String runId = safe.runId() == null ? newRunId(clock) : requireSafeId(safe.runId(), "run id");
        Path data = runDirectory(project, runId);
        Path recordFile = data.resolve(RECORD_FILE);
        try {
            if (Files.isRegularFile(recordFile)) {
                RunRecord existing = readRecord(recordFile);
                ensureTree(data);
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
        Run run = begin(project, request, Instant.now(), out);
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
        pruneIfStale(file, clock);
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
        if (agentId != null && !agentId.isBlank() && !ANY_AGENT.equalsIgnoreCase(agentId.trim())) {
            files.addAll(noteFiles(inboxDirectory(project, requireSafeId(agentId, "agent id"))));
        }
        files.addAll(noteFiles(inboxDirectory(project, ANY_AGENT)));
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

    private static void pruneIfStale(Path file, Instant now) throws IOException {
        if (!Files.isRegularFile(file)) return;
        byte[] snapshot = Files.readAllBytes(file);
        if (!containsStale(snapshot, now)) return;
        byte[] latest = Files.readAllBytes(file);
        byte[] source = latest.length >= snapshot.length ? latest : snapshot;
        String filtered = filterLines(source, now);
        if (latest.length > source.length) {
            filtered = filtered + filterLines(slice(latest, source.length), now);
        }
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

    private static String filterLines(byte[] bytes, Instant now) {
        Instant cutoff = now.minus(RETENTION);
        StringBuilder kept = new StringBuilder();
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n", -1)) {
            if (line.isBlank()) continue;
            Instant stamp = timestampOf(line);
            if (stamp != null && stamp.isBefore(cutoff)) continue;
            kept.append(line).append('\n');
        }
        return kept.toString();
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

    static String normalizeEvent(String event) {
        if (event == null || event.isBlank()) return "note";
        return event.trim().toLowerCase(Locale.ROOT);
    }
}
