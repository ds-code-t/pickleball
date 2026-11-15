package tools.dscode.dolt.server;

import tools.dscode.dolt.DoltLog;
import tools.dscode.dolt.install.DoltInstaller;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Child JVM that owns one {@code dolt sql-server}. It stops the child on idle,
 * on its own shutdown, and when {@code pickleball dolt server stop} destroys it.
 */
public final class DoltServerSupervisor {
    private static volatile Process dolt;

    private DoltServerSupervisor() {
    }

    public static void main(String[] args) {
        Path project = Path.of("").toAbsolutePath().normalize();
        if (args != null) {
            for (String arg : args) {
                if (arg != null && arg.startsWith("--project=")) {
                    project = Path.of(arg.substring("--project=".length())).toAbsolutePath().normalize();
                }
            }
        }
        Path finalProject = project;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> destroyDolt(finalProject)));
        int code = 1;
        try {
            code = run(project);
        } catch (Exception failure) {
            System.err.println("error: " + failure.getMessage());
            code = 1;
        }
        if (code != 0) System.exit(code);
    }

    static int run(Path project) throws IOException, InterruptedException {
        Path binary = DoltInstaller.requireInstalled(project).path();
        initDatabase(binary, project);
        int forcedFailures = Integer.getInteger("pkb_dolt_test_fail_binds", 0);
        int caused = 0;
        Process started = null;
        int port = 0;
        for (int attempt = 1; attempt <= 3; attempt++) {
            boolean hold = caused < forcedFailures;
            port = choosePort(attempt == 1 && !hold);
            ServerSocket held = null;
            if (hold) {
                held = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"));
                caused++;
                DoltLog.log(project, "server", "bind", "test hold port=" + port);
            }
            started = startDolt(binary, project, port);
            boolean ready = waitUntilReady(started, port, held != null ? 2_000 : 8_000);
            if (held != null) held.close();
            if (ready) break;
            destroyProcess(started);
            started = null;
            DoltLog.log(project, "server", "bind", "retry attempt=" + attempt + " port=" + port);
        }
        if (started == null || !started.isAlive()) {
            throw new IOException("Dolt sql-server failed to bind after 3 attempts");
        }
        dolt = started;
        String version = DoltInstaller.readVersion(binary);
        DoltServer.writeInfo(project, started.pid(), ProcessHandle.current().pid(), port, version);
        DoltLog.log(project, "server", Long.toString(started.pid()), "supervisor port=" + port);
        watch(project, started);
        return 0;
    }

    private static void initDatabase(Path binary, Path project) throws IOException, InterruptedException {
        Path database = DoltServer.dataDir(project).resolve("pickleball");
        if (Files.isDirectory(database.resolve(".dolt"))) return;
        Files.createDirectories(database);
        ProcessBuilder builder = new ProcessBuilder(
                binary.toAbsolutePath().toString(),
                "init",
                "--name", "pickleball",
                "--email", "pickleball@localhost");
        builder.directory(database.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        byte[] output = process.getInputStream().readAllBytes();
        int code = process.waitFor();
        if (code != 0) {
            throw new IOException("dolt init exited " + code + ": " + new String(output));
        }
        DoltLog.log(project, "server", "init", "dolt init --name pickleball --email pickleball@localhost");
    }

    private static Process startDolt(Path binary, Path project, int port) throws IOException {
        Path log = DoltServer.home(project).resolve("server-"
                + DateTimeFormatter.BASIC_ISO_DATE.format(LocalDate.now(ZoneOffset.UTC)) + ".log");
        Files.createDirectories(log.getParent());
        if (!Files.exists(log)) Files.createFile(log);
        ProcessBuilder builder = new ProcessBuilder(
                binary.toAbsolutePath().toString(),
                "sql-server",
                "--host", "127.0.0.1",
                "--port", Integer.toString(port),
                "--data-dir", DoltServer.dataDir(project).toAbsolutePath().toString());
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        builder.redirectErrorStream(true);
        builder.directory(project.toFile());
        return builder.start();
    }

    private static boolean waitUntilReady(Process process, int port, int budgetMs) throws InterruptedException {
        long deadline = System.nanoTime() + budgetMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) return false;
            if (DoltServer.tcpOpen(port)) return true;
            Thread.sleep(50);
        }
        return process.isAlive() && DoltServer.tcpOpen(port);
    }

    private static int choosePort(boolean allowPin) throws IOException {
        if (allowPin) {
            String pin = DoltLog.setting("pkb_dolt_port", "PKB_DOLT_PORT");
            if (pin != null) {
                try {
                    return Integer.parseInt(pin.trim());
                } catch (NumberFormatException failure) {
                    throw new IOException("pkb_dolt_port is not a port: " + pin);
                }
            }
        }
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private static void watch(Path project, Process process) throws IOException, InterruptedException {
        long graceUntil = System.currentTimeMillis() + 1_000;
        long poll = DoltServer.idleMillis() < 60_000 ? 200 : 5_000;
        while (process.isAlive()) {
            DoltServer.capServerLogs(project);
            if (System.currentTimeMillis() > graceUntil && !DoltServer.leaseFresh(project)) {
                DoltLog.log(project, "server", Long.toString(process.pid()), "idle stop");
                process.destroy();
                if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
                break;
            }
            Thread.sleep(poll);
        }
        Files.deleteIfExists(DoltServer.home(project).resolve("server.json"));
    }

    private static void destroyDolt(Path project) {
        Process process = dolt;
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException failure) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        try {
            Files.deleteIfExists(DoltServer.home(project).resolve("server.json"));
        } catch (IOException ignored) {
            // The parent stop also deletes the file.
        }
    }

    private static void destroyProcess(Process process) {
        if (process == null) return;
        process.destroyForcibly();
        try {
            process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }

    /** Keeps the import used when a caller wants the argument list shape. */
    static List<String> commandPreview(Path binary, int port, Path dataDir) {
        return List.of(binary.toString(), "sql-server", "--host", "127.0.0.1", "--port", Integer.toString(port),
                "--data-dir", dataDir.toString());
    }
}
