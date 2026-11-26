package tools.dscode.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Side-by-side conflict choice. One side is kept. The other is held under
 * {@code .pickleball/store/discarded/} until the next process start drops it
 * for age or for a different JVM start id. No merge.
 */
public final class StoreConflictChoice {
    public static final Duration HOLD = Duration.ofHours(24);
    private static final DateTimeFormatter STAMP = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmssSSS'Z'")
            .withZone(ZoneOffset.UTC);
    private static final Pattern JSON_FIELD = Pattern.compile(
            "\"([^\"\\\\]+)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?m)^([ \\t]*)([A-Za-z_][A-Za-z0-9_.-]*)([ \\t]*[:=][ \\t]*)(\\S+)[ \\t]*$");
    private static final Object GATE = new Object();
    private static Clock clock = Clock.systemUTC();
    private static String startId = UUID.randomUUID().toString();

    private StoreConflictChoice() {
    }

    /** Tests inject a clock. Production uses {@link Clock#systemUTC()}. */
    public static void clock(Clock value) {
        synchronized (GATE) {
            clock = value == null ? Clock.systemUTC() : value;
        }
    }

    /**
     * Pretend a new JVM started: a new start id is written to {@code session}
     * and discarded files from the previous id are deleted.
     */
    public static void newProcess(Path project) throws IOException {
        synchronized (GATE) {
            startId = UUID.randomUUID().toString();
        }
        sweep(project);
    }

    public static Path directory(Path project) {
        return project.resolve(".pickleball").resolve("store").resolve("discarded");
    }

    /** Delete discarded files older than 24 hours or marked by another JVM. */
    public static void sweep(Path project) throws IOException {
        if (project == null) return;
        Path dir = directory(project);
        if (!Files.isDirectory(dir)) return;
        String current = currentStartId();
        Instant now = now();
        Files.writeString(dir.resolve("session"), current, StandardCharsets.UTF_8);
        List<Path> bodies = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path file : stream) bodies.add(file);
        }
        for (Path file : bodies) {
            if (!Files.isRegularFile(file)) continue;
            String name = file.getFileName().toString();
            if ("session".equals(name) || name.endsWith(".start")) continue;
            Instant stamped = stampOf(name);
            if (stamped == null) continue;
            String marker = readMarker(dir.resolve(name + ".start"));
            boolean old = stamped.isBefore(now.minus(HOLD));
            boolean otherJvm = marker == null || !marker.equals(current);
            if (old || otherJvm) {
                Files.deleteIfExists(file);
                Files.deleteIfExists(dir.resolve(name + ".start"));
            }
        }
    }

    public record View(String path, String fileText, String databaseText, String discardedName) {
    }

    public static View show(Path project, String relative) throws IOException, SQLException {
        String path = StorePaths.normalizeRelative(relative);
        Sides sides = read(project, path);
        String fileText = mask(project, text(sides.fileBytes()));
        String databaseText = mask(project, text(sides.databaseBytes()));
        StoreLog.log(project, "conflict", path, "show");
        return new View(path, fileText, databaseText, "");
    }

    public static View choose(Path project, String relative, String keep) throws IOException, SQLException {
        String path = StorePaths.normalizeRelative(relative);
        String which = keep == null ? "" : keep.trim();
        if (!"db".equals(which) && !"file".equals(which)) {
            throw new Usage(
                    "store conflicts choose needs --keep=db|file",
                    "store conflicts choose <relative-path> --keep=db|file");
        }
        if (!StorePaths.ready(project)) {
            throw new Refusal("no stored file: " + path, "store conflicts");
        }
        sweep(project);
        try (StoreDatabase database = StoreDatabase.open(project)) {
            Connection connection = database.connection();
            StoreFiles.Row row = StoreFiles.find(connection, path);
            if (row == null) {
                throw new Refusal("no stored file: " + path, "store conflicts");
            }
            if (!bothSides(project, connection, path)) {
                throw new Refusal("no both-sides conflict: " + path, "store conflicts");
            }
            Path disk = project.resolve(path);
            byte[] fileBytes = Files.readAllBytes(disk);
            byte[] databaseBytes = row.raw() == null ? new byte[0] : row.raw();
            byte[] discarded = "db".equals(which) ? fileBytes : databaseBytes;
            byte[] kept = "db".equals(which) ? databaseBytes : fileBytes;
            String discardedName = writeDiscard(project, path, mask(project, text(discarded)));
            connection.setAutoCommit(false);
            try {
                String now = Instant.now().toString();
                String hash = StoreHashes.sha256(kept);
                long mtime = Files.getLastModifiedTime(disk).toMillis();
                if ("db".equals(which)) {
                    if (disk.getParent() != null) Files.createDirectories(disk.getParent());
                    Files.write(disk, kept);
                    mtime = Files.getLastModifiedTime(disk).toMillis();
                }
                StoreFiles.upsert(connection, new StoreFiles.Row(
                        row.path(),
                        hash,
                        kept,
                        mtime,
                        row.sourceType(),
                        now,
                        hash,
                        false,
                        row.gitBlob(),
                        false,
                        kept.length,
                        "db",
                        row.gitCommit() == null ? "" : row.gitCommit()));
                if ("file".equals(which)) {
                    if (StoreGherkin.isGherkin(path, row.sourceType())) {
                        StoreGherkin.store(connection, project, path, row.sourceType(), kept);
                    }
                    if (StoreEntities.isRecord(path, row.sourceType())) {
                        StoreEntities.store(connection, project, path, kept);
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException | IOException failure) {
                connection.rollback();
                Files.deleteIfExists(directory(project).resolve(discardedName));
                Files.deleteIfExists(directory(project).resolve(discardedName + ".start"));
                if ("db".equals(which)) Files.write(disk, fileBytes);
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
            StoreLog.log(project, "conflict", path, "choose keep=" + which + " discarded=" + discardedName);
            return new View(path, mask(project, text(fileBytes)), mask(project, text(databaseBytes)), discardedName);
        }
    }

    public static String showText(View view) {
        return "file " + view.path() + "\n"
                + finish(view.fileText())
                + "database " + view.path() + "\n"
                + finish(view.databaseText());
    }

    public static String showJson(View view) {
        return "{\"path\":\"" + jsonEscape(view.path())
                + "\",\"file\":\"" + jsonEscape(view.fileText())
                + "\",\"database\":\"" + jsonEscape(view.databaseText()) + "\"}";
    }

    public static String chooseText(View view, String keep) {
        return "choose " + view.path() + " keep=" + keep + " discarded=" + view.discardedName() + "\n";
    }

    public static String chooseJson(View view, String keep) {
        return "{\"ok\":true,\"command\":\"conflicts choose\",\"path\":\"" + jsonEscape(view.path())
                + "\",\"keep\":\"" + jsonEscape(keep)
                + "\",\"discarded\":\"" + jsonEscape(view.discardedName()) + "\"}";
    }

    public static final class Usage extends IllegalArgumentException {
        private final String fix;

        public Usage(String message, String fix) {
            super(message);
            this.fix = fix;
        }

        public String fix() {
            return fix;
        }
    }

    public static final class Refusal extends IllegalStateException {
        private final String fix;

        public Refusal(String message, String fix) {
            super(message);
            this.fix = fix;
        }

        public String fix() {
            return fix;
        }
    }

    private record Sides(byte[] fileBytes, byte[] databaseBytes) {
    }

    private static Sides read(Path project, String path) throws IOException, SQLException {
        if (!StorePaths.ready(project)) {
            throw new Refusal("no stored file: " + path, "store conflicts");
        }
        try (StoreDatabase database = StoreDatabase.open(project)) {
            StoreFiles.Row row = StoreFiles.find(database.connection(), path);
            if (row == null) throw new Refusal("no stored file: " + path, "store conflicts");
            Path disk = project.resolve(path);
            byte[] fileBytes = Files.isRegularFile(disk) ? Files.readAllBytes(disk) : new byte[0];
            byte[] databaseBytes = row.raw() == null ? new byte[0] : row.raw();
            return new Sides(fileBytes, databaseBytes);
        }
    }

    private static boolean bothSides(Path project, Connection connection, String path) throws SQLException {
        for (StoreQuery.Conflict conflict : StoreQuery.conflicts(project, connection)) {
            if (path.equals(conflict.path()) && "both".equals(conflict.kind())) return true;
        }
        return false;
    }

    private static String writeDiscard(Path project, String relative, String masked) throws IOException {
        Path dir = directory(project);
        Files.createDirectories(dir);
        String current = currentStartId();
        Files.writeString(dir.resolve("session"), current, StandardCharsets.UTF_8);
        Instant stamped = now();
        String safe = relative.replace('/', '_').replace('\\', '_');
        String name = null;
        for (int bump = 0; bump < 1000; bump++) {
            String candidate = STAMP.format(stamped.plusMillis(bump)) + "-" + safe;
            if (!Files.exists(dir.resolve(candidate))) {
                name = candidate;
                break;
            }
        }
        if (name == null) {
            throw new IOException("discarded name already exists for " + relative);
        }
        Files.writeString(dir.resolve(name), masked, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(name + ".start"), current, StandardCharsets.UTF_8);
        return name;
    }

    static String mask(Path project, String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        String assigned = maskAssignments(project, text);
        return maskJson(project, assigned);
    }

    private static String maskAssignments(Path project, String text) {
        Matcher matcher = ASSIGNMENT.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            String key = matcher.group(2);
            String value = matcher.group(4);
            out.append(text, last, matcher.start(4));
            out.append(hidden(project, key, value));
            last = matcher.end(4);
        }
        out.append(text.substring(last));
        return out.toString();
    }

    private static String maskJson(Path project, String text) {
        Matcher matcher = JSON_FIELD.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = matcher.group(2);
            out.append(text, last, matcher.start(2));
            if (StoreSecrets.secretName(project, key) && !StoreSecrets.reference(jsonUnescape(value))) {
                out.append("~MASK;");
            } else {
                out.append(value);
            }
            last = matcher.end(2);
        }
        out.append(text.substring(last));
        return out.toString();
    }

    private static String hidden(Path project, String key, String value) {
        if (!StoreSecrets.secretName(project, key) || StoreSecrets.reference(value)) return value;
        return "~MASK;";
    }

    private static String jsonUnescape(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String text(byte[] raw) {
        if (raw == null || raw.length == 0) return "";
        for (byte one : raw) {
            if (one == 0) return "binary";
        }
        return new String(raw, StandardCharsets.UTF_8);
    }

    private static String finish(String text) {
        if (text == null || text.isEmpty()) return "\n";
        return text.endsWith("\n") ? text : text + "\n";
    }

    private static Instant stampOf(String name) {
        int split = name.indexOf('-');
        if (split != 19) return null;
        try {
            return STAMP.parse(name.substring(0, 19), Instant::from);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static String readMarker(Path marker) throws IOException {
        if (!Files.isRegularFile(marker)) return null;
        return Files.readString(marker, StandardCharsets.UTF_8).trim();
    }

    private static String currentStartId() {
        synchronized (GATE) {
            return startId;
        }
    }

    private static Instant now() {
        synchronized (GATE) {
            return clock.instant().truncatedTo(ChronoUnit.MILLIS);
        }
    }

    private static String jsonEscape(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }
}
