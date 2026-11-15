package tools.dscode.dolt.server;

import tools.dscode.dolt.DoltLog;
import tools.dscode.dolt.install.DoltInstaller;
import tools.dscode.dolt.install.DoltReleaseCatalog;
import tools.dscode.dolt.store.DoltConnections;
import tools.dscode.dolt.store.DoltJdbc;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Loopback Dolt sql-server for one project. Pickleball's {@code dolt server}
 * commands call this class. The Workbench does not.
 */
public final class DoltServer {
    public static final String DRIVER = DoltJdbc.DRIVER;
    public static final String MISSING_DRIVER = DoltJdbc.MISSING_DRIVER;
    public static final int DEFAULT_START_MS = 15_000;
    public static final int DEFAULT_SERVER_LOG_MAX = 4_194_304;

    public record ServerInfo(boolean running, long pid, long supervisorPid, int port, String doltVersion,
                              String startedAt, String project, boolean reused) {
        ServerInfo reused(boolean value) {
            return new ServerInfo(running, pid, supervisorPid, port, doltVersion, startedAt, project, value);
        }

        public String toJson() {
            if (!running) {
                return "{\"running\":false}";
            }
            return "{\"running\":true"
                    + ",\"pid\":" + pid
                    + ",\"supervisorPid\":" + supervisorPid
                    + ",\"port\":" + port
                    + ",\"doltVersion\":" + quote(doltVersion)
                    + ",\"startedAt\":" + quote(startedAt)
                    + ",\"project\":" + quote(project)
                    + ",\"reused\":" + reused
                    + "}";
        }
    }

    private static final java.util.Set<Path> PROJECTS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Keeps one project's server and stops the others that have no checked-out
     * connection. Tests open a server per temp directory; this drops the previous one.
     */
    public static void retain(Path project) {
        if (project == null) return;
        Path root = project.toAbsolutePath().normalize();
        java.util.List<Path> others = new java.util.ArrayList<>();
        for (Path known : PROJECTS) {
            if (!known.equals(root)) others.add(known);
        }
        PROJECTS.add(root);
        for (Path other : others) {
            if (DoltConnections.inUse(other)) continue;
            PROJECTS.remove(other);
            DoltConnections.closeProject(other);
            try {
                stop(other);
            } catch (IOException ignored) {
                // A server that already exited still leaves the next open correct.
            }
        }
    }

    private DoltServer() {
    }

    public static boolean driverPresent() {
        return DoltJdbc.driverPresent();
    }

    public static Path home(Path project) {
        return project.toAbsolutePath().normalize().resolve(".pickleball").resolve("dolt");
    }

    public static Path dataDir(Path project) {
        return home(project).resolve("data");
    }

    public static ServerInfo ensure(Path project) throws IOException {
        retain(project);
        if (!driverPresent()) throw new IOException(MISSING_DRIVER);
        Path home = home(project);
        Files.createDirectories(home);
        Path lockPath = home.resolve("server.lock");
        long budgetNs = startTimeoutMs() * 1_000_000L;
        long deadline = System.nanoTime() + budgetNs;
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock = null;
            while (lock == null) {
                try {
                    lock = channel.tryLock();
                } catch (java.nio.channels.OverlappingFileLockException busy) {
                    // Another thread in this JVM already holds server.lock.
                    lock = null;
                }
                if (lock == null) {
                    ServerInfo ready = probe(project, 2_000);
                    if (ready != null) return ready.reused(true);
                    if (System.nanoTime() > deadline) {
                        throw new IOException("timed out waiting for " + lockPath);
                    }
                    sleep(100);
                }
            }
            try {
                ServerInfo ready = probe(project, 2_000);
                if (ready != null) {
                    touchLease(project);
                    DoltLog.log(project, "server", Long.toString(ready.pid()), "reuse port=" + ready.port());
                    return ready.reused(true);
                }
                clearStale(project);
                DoltInstaller.requireInstalled(project);
                launchSupervisor(project);
                while (System.nanoTime() < deadline) {
                    ServerInfo started = probe(project, 2_000);
                    if (started != null) {
                        touchLease(project);
                        DoltLog.log(project, "server", Long.toString(started.pid()), "start port=" + started.port());
                        return started.reused(false);
                    }
                    sleep(100);
                }
                throw new IOException("Dolt sql-server did not answer SELECT 1 within " + startTimeoutMs() + " ms");
            } finally {
                lock.release();
            }
        }
    }

    public static ServerInfo status(Path project) {
        try {
            ServerInfo info = probe(project, 2_000);
            if (info != null) return info.reused(true);
        } catch (IOException ignored) {
            // Status reports the file when the driver cannot probe.
        }
        ServerInfo recorded = readInfo(project);
        if (recorded != null && pidAlive(recorded.pid(), "dolt")) return recorded;
        return new ServerInfo(false, 0, 0, 0, "", "", project.toString(), false);
    }

    public static void stop(Path project) throws IOException {
        ServerInfo info = readInfo(project);
        if (info == null) {
            DoltLog.log(project, "server", "-", "stop already stopped");
            return;
        }
        ProcessHandle supervisor = ProcessHandle.of(info.supervisorPid()).orElse(null);
        ProcessHandle dolt = ProcessHandle.of(info.pid()).orElse(null);
        if (supervisor != null && supervisor.isAlive()) supervisor.destroy();
        if (dolt != null && dolt.isAlive() && looksLike(dolt, "dolt")) dolt.destroy();
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            boolean supervisorAlive = supervisor != null && supervisor.isAlive();
            boolean doltAlive = dolt != null && dolt.isAlive();
            if (!supervisorAlive && !doltAlive) break;
            sleep(100);
        }
        if (supervisor != null && supervisor.isAlive()) supervisor.destroyForcibly();
        if (dolt != null && dolt.isAlive() && looksLike(dolt, "dolt")) dolt.destroyForcibly();
        deadline = System.nanoTime() + 3_000_000_000L;
        while (System.nanoTime() < deadline) {
            boolean supervisorAlive = supervisor != null && supervisor.isAlive();
            boolean doltAlive = dolt != null && dolt.isAlive();
            if (!supervisorAlive && !doltAlive) break;
            sleep(100);
        }
        Files.deleteIfExists(home(project).resolve("server.json"));
        DoltLog.log(project, "server", Long.toString(info.pid()), "stopped");
    }

    public static void touchLease(Path project) throws IOException {
        long pid = ProcessHandle.current().pid();
        Path lease = home(project).resolve("lease").resolve(Long.toString(pid));
        Files.createDirectories(lease.getParent());
        Files.writeString(lease, Instant.now().toString(), StandardCharsets.UTF_8);
    }

    public static boolean selectOne(int port, int timeoutMs) throws SQLException {
        return DoltJdbc.selectOne(port, timeoutMs);
    }

    static ServerInfo probe(Path project, int timeoutMs) throws IOException {
        ServerInfo info = readInfo(project);
        if (info == null || info.port() <= 0) return null;
        if (!pidAlive(info.pid(), "dolt") && !tcpOpen(info.port())) return null;
        try {
            if (!selectOne(info.port(), timeoutMs)) return null;
        } catch (SQLException failure) {
            return null;
        }
        return info;
    }

    static ServerInfo readInfo(Path project) {
        Path file = home(project).resolve("server.json");
        if (!Files.isRegularFile(file)) return null;
        try {
            String json = Files.readString(file);
            return new ServerInfo(
                    true,
                    parseLong(field(json, "pid")),
                    parseLong(field(json, "supervisorPid")),
                    (int) parseLong(field(json, "port")),
                    unquote(field(json, "doltVersion")),
                    unquote(field(json, "startedAt")),
                    unquote(field(json, "project")),
                    false);
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    static void writeInfo(Path project, long pid, long supervisorPid, int port, String version) throws IOException {
        String json = "{\"pid\":" + pid
                + ",\"supervisorPid\":" + supervisorPid
                + ",\"port\":" + port
                + ",\"doltVersion\":" + quote(version == null ? DoltReleaseCatalog.VERSION : version)
                + ",\"startedAt\":" + quote(Instant.now().toString())
                + ",\"project\":" + quote(project.toAbsolutePath().normalize().toString())
                + "}\n";
        Path file = home(project).resolve("server.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    private static void clearStale(Path project) throws IOException {
        ServerInfo info = readInfo(project);
        if (info == null) return;
        ProcessHandle dolt = ProcessHandle.of(info.pid()).orElse(null);
        if (dolt != null && dolt.isAlive() && looksLike(dolt, "dolt")) {
            dolt.destroy();
            sleep(200);
            if (dolt.isAlive()) dolt.destroyForcibly();
        }
        ProcessHandle supervisor = ProcessHandle.of(info.supervisorPid()).orElse(null);
        if (supervisor != null && supervisor.isAlive() && looksLike(supervisor, "java")) {
            supervisor.destroy();
        }
        Files.deleteIfExists(home(project).resolve("server.json"));
    }

    private static void launchSupervisor(Path project) throws IOException {
        String java = ProcessHandle.current().info().command().orElse("java");
        Path home = home(project);
        Path argFile = home.resolve("supervisor.args");
        List<String> lines = new ArrayList<>();
        Properties properties = System.getProperties();
        for (String name : properties.stringPropertyNames()) {
            if (!name.startsWith("pkb_dolt")) continue;
            lines.add(quoteArg("-D" + name + "=" + properties.getProperty(name)));
        }
        lines.add("-cp");
        lines.add(quoteArg(System.getProperty("java.class.path")));
        lines.add("tools.dscode.dolt.server.DoltServerSupervisor");
        lines.add(quoteArg("--project=" + project.toAbsolutePath().normalize()));
        Files.writeString(argFile, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        Path log = home.resolve("supervisor.out");
        ProcessBuilder builder = new ProcessBuilder(java, "@" + argFile.toAbsolutePath());
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        builder.redirectErrorStream(true);
        builder.directory(project.toFile());
        builder.start();
    }

    static void capServerLogs(Path project) throws IOException {
        Path home = home(project);
        if (!Files.isDirectory(home)) return;
        int max = serverLogMaxBytes();
        List<Path> logs;
        try (var stream = Files.list(home)) {
            logs = stream.filter(path -> {
                String name = path.getFileName().toString();
                return name.startsWith("server-") && name.endsWith(".log");
            }).sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
        long total = 0;
        for (Path log : logs) total += Files.size(log);
        List<Path> remaining = new ArrayList<>(logs);
        while (total > max && remaining.size() > 1) {
            Path oldest = remaining.remove(0);
            total -= Files.size(oldest);
            Files.deleteIfExists(oldest);
        }
        if (!remaining.isEmpty() && total > max) {
            Path current = remaining.get(remaining.size() - 1);
            byte[] bytes = Files.readAllBytes(current);
            if (bytes.length > max) {
                int start = bytes.length - max;
                while (start < bytes.length && bytes[start] != '\n') start++;
                if (start < bytes.length - 1) start++;
                Files.write(current, java.util.Arrays.copyOfRange(bytes, start, bytes.length));
            }
        }
    }

    public static int startTimeoutMs() {
        String raw = DoltLog.setting("pkb_dolt_server_start_ms", "PKB_DOLT_SERVER_START_MS");
        if (raw == null) return DEFAULT_START_MS;
        try {
            return Math.max(1000, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException failure) {
            return DEFAULT_START_MS;
        }
    }

    public static long idleMillis() {
        String raw = DoltLog.setting("pkb_dolt_server_idle_minutes", "PKB_DOLT_SERVER_IDLE_MINUTES");
        if (raw == null) return 30L * 60_000L;
        try {
            double minutes = Double.parseDouble(raw.trim());
            if (minutes < 0) return 30L * 60_000L;
            return (long) (minutes * 60_000d);
        } catch (NumberFormatException failure) {
            return 30L * 60_000L;
        }
    }

    public static int serverLogMaxBytes() {
        String raw = DoltLog.setting("pkb_dolt_server_log_max_bytes", "PKB_DOLT_SERVER_LOG_MAX_BYTES");
        if (raw == null) return DEFAULT_SERVER_LOG_MAX;
        try {
            return Math.max(64, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException failure) {
            return DEFAULT_SERVER_LOG_MAX;
        }
    }

    static boolean leaseFresh(Path project) throws IOException {
        Path dir = home(project).resolve("lease");
        if (!Files.isDirectory(dir)) return false;
        long window = idleMillis();
        long now = System.currentTimeMillis();
        try (var stream = Files.list(dir)) {
            for (Path lease : stream.toList()) {
                long pid;
                try {
                    pid = Long.parseLong(lease.getFileName().toString());
                } catch (NumberFormatException failure) {
                    continue;
                }
                if (!pidAlive(pid, null)) continue;
                long age = now - Files.getLastModifiedTime(lease).toMillis();
                if (age <= window) return true;
            }
        }
        return false;
    }

    static boolean pidAlive(long pid, String commandContains) {
        if (pid <= 0) return false;
        return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).filter(handle -> commandContains == null
                || looksLike(handle, commandContains) || commandLine(handle).isBlank()).isPresent();
    }

    private static boolean looksLike(ProcessHandle handle, String token) {
        String command = commandLine(handle).toLowerCase(Locale.ROOT);
        if (command.isBlank()) return true;
        return command.contains(token);
    }

    private static String commandLine(ProcessHandle handle) {
        String command = handle.info().command().orElse("");
        String line = handle.info().commandLine().orElse("");
        return command + " " + line;
    }

    static boolean tcpOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 500);
            return true;
        } catch (IOException failure) {
            return false;
        }
    }

    public static int cli(List<String> tokens, PrintStream out, PrintStream err) {
        String action = "status";
        for (String token : tokens) {
            if (!token.startsWith("--") && !"server".equals(token)) {
                action = token;
                break;
            }
        }
        Path project = DoltInstaller.project(tokens);
        boolean json = tokens.contains("--json");
        try {
            return switch (action) {
                case "start" -> {
                    ServerInfo info = ensure(project);
                    if (json) out.println(info.toJson());
                    else out.println("dolt server port=" + info.port() + " pid=" + info.pid()
                            + " reused=" + info.reused());
                    yield 0;
                }
                case "stop" -> {
                    stop(project);
                    if (json) out.println("{\"running\":false}");
                    else out.println("dolt server stopped");
                    yield 0;
                }
                case "status" -> {
                    if (!driverPresent() && Files.isRegularFile(home(project).resolve("server.json"))) {
                        err.println("error: " + MISSING_DRIVER);
                        err.println("fix: add org.mariadb.jdbc:mariadb-java-client or set pkb_dolt=false");
                        yield 1;
                    }
                    ServerInfo info = status(project);
                    if (json) out.println(info.toJson());
                    else if (!info.running()) out.println("dolt server stopped");
                    else out.println("dolt server port=" + info.port() + " pid=" + info.pid());
                    yield 0;
                }
                default -> {
                    err.println("error: Unknown Dolt server command: " + action);
                    err.println("fix: pickleball dolt server status");
                    yield 2;
                }
            };
        } catch (IOException failure) {
            err.println("error: " + failure.getMessage());
            err.println("fix: pickleball dolt install");
            return 1;
        }
    }

    private static String quote(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String quoteArg(String value) {
        if (value.indexOf(' ') < 0 && value.indexOf('\t') < 0 && value.indexOf('"') < 0) return value;
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static String field(String json, String key) {
        String needle = "\"" + key + "\":";
        int index = json.indexOf(needle);
        if (index < 0) return "";
        int start = index + needle.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length()) return "";
        if (json.charAt(start) == '"') {
            StringBuilder value = new StringBuilder();
            for (int cursor = start + 1; cursor < json.length(); cursor++) {
                char ch = json.charAt(cursor);
                if (ch == '\\' && cursor + 1 < json.length()) {
                    value.append(json.charAt(cursor + 1));
                    cursor++;
                    continue;
                }
                if (ch == '"') break;
                value.append(ch);
            }
            return value.toString();
        }
        int end = start;
        while (end < json.length() && ",}\n\r ".indexOf(json.charAt(end)) < 0) end++;
        return json.substring(start, end);
    }

    private static String unquote(String value) {
        return value == null ? "" : value;
    }

    private static long parseLong(String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (RuntimeException failure) {
            return 0;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }
}
