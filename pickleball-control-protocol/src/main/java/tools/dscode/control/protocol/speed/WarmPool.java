package tools.dscode.control.protocol.speed;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Idle-only release and PING for the warm pool. A busy member is left alone
 * unless the caller names its run or asks to stop every slot.
 */
public final class WarmPool {
    public enum PingKind {
        IDLE,
        BUSY,
        UNKNOWN
    }

    public record Ping(PingKind kind, String runId) {
        public static Ping idle() {
            return new Ping(PingKind.IDLE, "");
        }

        public static Ping busy(String runId) {
            return new Ping(PingKind.BUSY, runId == null ? "" : runId.trim());
        }

        public static Ping unknown() {
            return new Ping(PingKind.UNKNOWN, "");
        }

        public boolean busy() {
            return kind == PingKind.BUSY;
        }

        public boolean isIdle() {
            return kind == PingKind.IDLE;
        }
    }

    private WarmPool() {
    }

    public static Ping ping(int port) {
        if (port <= 0) return Ping.unknown();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500);
            socket.setSoTimeout(500);
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            writer.println("PING");
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null) return Ping.unknown();
            String trimmed = line.trim();
            if (trimmed.startsWith("PONG busy")) {
                return Ping.busy(trimmed.substring("PONG busy".length()).trim());
            }
            if ("PONG".equals(trimmed) || trimmed.startsWith("PONG idle")) return Ping.idle();
            return Ping.unknown();
        } catch (Exception failure) {
            return Ping.unknown();
        }
    }

    /** Kills idle legacy and slot members. A busy member stays up. */
    public static void releaseIdle(Path stateRoot) {
        if (stateRoot == null) return;
        releaseLegacyIfIdle(stateRoot);
        for (WarmSlot.Member member : WarmSlot.list(stateRoot)) {
            if (idle(member)) WarmSlot.release(member.directory());
        }
    }

    /** Kills every warm JVM, busy or not. {@code warm-stop --all} is the only caller. */
    public static void releaseAll(Path stateRoot) {
        if (stateRoot == null) return;
        WarmProcess.release(stateRoot);
        for (WarmSlot.Member member : WarmSlot.list(stateRoot)) {
            WarmSlot.release(member.directory());
        }
    }

    /** Kills the member serving {@code runId}. Other busy members stay. */
    public static void releaseRun(Path stateRoot, String runId) {
        if (stateRoot == null || runId == null || runId.isBlank()) return;
        String wanted = runId.trim();
        for (WarmSlot.Member member : WarmSlot.list(stateRoot)) {
            if (!WarmProcess.alive(member.handle().pid())) continue;
            if (wanted.equals(servingRun(member))) WarmSlot.release(member.directory());
        }
        releaseLegacyIfServing(stateRoot, wanted);
    }

    public static boolean runInFlight(Path stateRoot, String runId) {
        if (stateRoot == null || runId == null || runId.isBlank()) return false;
        String wanted = runId.trim();
        for (WarmSlot.Member member : WarmSlot.list(stateRoot)) {
            if (WarmProcess.alive(member.handle().pid()) && wanted.equals(servingRun(member))) return true;
        }
        return legacyServing(stateRoot, wanted);
    }

    public static int aliveCount(Path stateRoot) {
        if (stateRoot == null) return 0;
        int count = 0;
        if (legacyAlive(stateRoot)) count++;
        for (WarmSlot.Member member : WarmSlot.list(stateRoot)) {
            if (WarmProcess.alive(member.handle().pid())) count++;
        }
        return count;
    }

    public static List<String> busyRuns(Path stateRoot) {
        List<String> runs = new ArrayList<>();
        if (stateRoot == null) return runs;
        for (WarmSlot.Member member : WarmSlot.list(stateRoot)) {
            if (!WarmProcess.alive(member.handle().pid())) continue;
            String run = servingRun(member);
            if (!run.isBlank()) runs.add(run);
        }
        String legacy = legacyRun(stateRoot);
        if (!legacy.isBlank()) runs.add(legacy);
        return List.copyOf(runs);
    }

    public static boolean idle(WarmSlot.Member member) {
        if (member == null || member.handle() == null) return true;
        if (!WarmProcess.alive(member.handle().pid())) return true;
        Ping ping = ping(member.handle().port());
        if (ping.busy()) return false;
        if (ping.isIdle()) return true;
        return WarmSlot.busyRun(member.directory()).isBlank();
    }

    /** True when this directory's JVM is serving a run. A dead pid is not serving. */
    public static boolean serving(Path directory) {
        if (directory == null) return false;
        if (WarmSlot.isSlotDirectory(directory)) {
            return WarmSlot.read(directory).map(handle -> {
                if (!WarmProcess.alive(handle.pid())) return false;
                Ping ping = ping(handle.port());
                if (ping.busy()) return true;
                if (ping.isIdle()) return false;
                return !WarmSlot.busyRun(directory).isBlank();
            }).orElse(false);
        }
        return WarmProcess.read(directory).map(handle -> {
            if (!WarmProcess.alive(handle.pid())) return false;
            Ping ping = ping(handle.port());
            return ping.busy();
        }).orElse(false);
    }

    private static String servingRun(WarmSlot.Member member) {
        Ping ping = ping(member.handle().port());
        if (ping.busy()) return ping.runId();
        if (ping.isIdle()) return "";
        return WarmSlot.busyRun(member.directory());
    }

    private static void releaseLegacyIfIdle(Path stateRoot) {
        var handle = WarmProcess.read(stateRoot);
        if (handle.isEmpty()) return;
        if (!WarmProcess.alive(handle.get().pid())) {
            WarmProcess.release(stateRoot);
            return;
        }
        Ping ping = ping(handle.get().port());
        if (ping.busy()) return;
        if (ping.isIdle() || !Files.isRegularFile(stateRoot.resolve(WarmSlot.BUSY_NAME))) {
            WarmProcess.release(stateRoot);
        }
    }

    private static void releaseLegacyIfServing(Path stateRoot, String runId) {
        if (!legacyServing(stateRoot, runId)) return;
        WarmProcess.release(stateRoot);
    }

    private static boolean legacyAlive(Path stateRoot) {
        return WarmProcess.read(stateRoot).map(handle -> WarmProcess.alive(handle.pid())).orElse(false);
    }

    private static boolean legacyServing(Path stateRoot, String runId) {
        return runId.equals(legacyRun(stateRoot));
    }

    private static String legacyRun(Path stateRoot) {
        var handle = WarmProcess.read(stateRoot);
        if (handle.isEmpty() || !WarmProcess.alive(handle.get().pid())) return "";
        Ping ping = ping(handle.get().port());
        if (ping.busy()) return ping.runId();
        if (ping.isIdle()) return "";
        Path busy = stateRoot.resolve(WarmSlot.BUSY_NAME);
        if (!Files.isRegularFile(busy)) return "";
        try {
            return Files.readString(busy, StandardCharsets.UTF_8).trim();
        } catch (Exception failure) {
            return "";
        }
    }
}
