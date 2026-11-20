package tools.dscode.control.protocol.speed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * One member of the warm-JVM pool. The handle lives in
 * {@code <state>/warm/slot-<n>/warm-jvm.v2}. A legacy single JVM still uses
 * {@link WarmProcess} at the state root.
 */
public final class WarmSlot {
    public static final String HANDLE_NAME = "warm-jvm.v2";
    public static final String BUSY_NAME = "busy";
    public static final String RETIRE_NAME = "retire";

    private WarmSlot() {
    }

    public record Handle(long pid, int port, String generation, long ownerPid, String watchHash) {
    }

    public record Member(int index, Path directory, Handle handle) {
    }

    public static boolean isSlotDirectory(Path directory) {
        if (directory == null || directory.getFileName() == null) return false;
        String name = directory.getFileName().toString();
        if (!name.startsWith("slot-")) return false;
        try {
            return Integer.parseInt(name.substring("slot-".length())) > 0;
        } catch (NumberFormatException failure) {
            return false;
        }
    }

    public static Path directory(Path stateRoot, int index) {
        if (index <= 0) throw new IllegalArgumentException("Warm slot index starts at 1.");
        return WarmGeneration.warm(stateRoot).resolve("slot-" + index);
    }

    public static Path handleFile(Path slotDirectory) {
        return slotDirectory.resolve(HANDLE_NAME);
    }

    public static void write(Path slotDirectory, Handle handle) throws IOException {
        if (slotDirectory == null || handle == null || handle.pid() <= 0 || handle.port() <= 0) {
            throw new IllegalArgumentException("Warm slot needs a directory, a pid, and a port.");
        }
        Files.createDirectories(slotDirectory);
        String body = "v2\n"
                + handle.pid() + "\n"
                + handle.port() + "\n"
                + text(handle.generation()) + "\n"
                + handle.ownerPid() + "\n"
                + text(handle.watchHash()) + "\n";
        Files.writeString(handleFile(slotDirectory), body, StandardCharsets.UTF_8);
        Files.writeString(slotDirectory.resolve(WarmProcess.PID_FILE_NAME), handle.pid() + "\n", StandardCharsets.UTF_8);
    }

    public static Optional<Handle> read(Path slotDirectory) {
        if (slotDirectory == null) return Optional.empty();
        Path path = handleFile(slotDirectory);
        if (!Files.isRegularFile(path)) return Optional.empty();
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            if (lines.size() < 6 || !"v2".equals(lines.get(0).trim())) return Optional.empty();
            long pid = Long.parseLong(lines.get(1).trim());
            int port = Integer.parseInt(lines.get(2).trim());
            long owner = lines.get(4).isBlank() ? 0L : Long.parseLong(lines.get(4).trim());
            return Optional.of(new Handle(pid, port, lines.get(3).trim(), owner, lines.get(5).trim()));
        } catch (IOException | NumberFormatException failure) {
            return Optional.empty();
        }
    }

    public static List<Member> list(Path stateRoot) {
        if (stateRoot == null) return List.of();
        Path warm = WarmGeneration.warm(stateRoot);
        if (!Files.isDirectory(warm)) return List.of();
        List<Member> members = new ArrayList<>();
        try (var children = Files.list(warm)) {
            children.filter(Files::isDirectory).filter(WarmSlot::isSlotDirectory).forEach(dir -> {
                int index = Integer.parseInt(dir.getFileName().toString().substring("slot-".length()));
                read(dir).ifPresent(handle -> members.add(new Member(index, dir, handle)));
            });
        } catch (IOException failure) {
            return List.of();
        }
        members.sort(Comparator.comparingInt(Member::index));
        return List.copyOf(members);
    }

    public static int nextIndex(Path stateRoot) {
        boolean[] used = new boolean[9];
        for (Member member : list(stateRoot)) {
            if (member.index() >= 1 && member.index() <= 8 && WarmProcess.alive(member.handle().pid())) {
                used[member.index()] = true;
            }
        }
        for (int index = 1; index <= 8; index++) {
            if (!used[index]) return index;
        }
        return 1;
    }

    public static void markBusy(Path slotDirectory, String runId) {
        if (slotDirectory == null) return;
        try {
            Files.createDirectories(slotDirectory);
            Files.writeString(slotDirectory.resolve(BUSY_NAME), (runId == null ? "" : runId.trim()) + "\n", StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // A missing marker still loses the race to EXIT 2.
        }
    }

    public static void clearBusy(Path slotDirectory) {
        if (slotDirectory == null) return;
        try {
            Files.deleteIfExists(slotDirectory.resolve(BUSY_NAME));
        } catch (IOException ignored) {
            // The next PING is the live answer.
        }
    }

    public static String busyRun(Path slotDirectory) {
        if (slotDirectory == null) return "";
        Path file = slotDirectory.resolve(BUSY_NAME);
        if (!Files.isRegularFile(file)) return "";
        try {
            return Files.readString(file, StandardCharsets.UTF_8).trim();
        } catch (IOException failure) {
            return "";
        }
    }

    public static void markRetire(Path slotDirectory) {
        if (slotDirectory == null) return;
        try {
            Files.createDirectories(slotDirectory);
            Files.writeString(slotDirectory.resolve(RETIRE_NAME), "retire\n", StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // The next command writes it again.
        }
    }

    public static boolean retirePending(Path slotDirectory) {
        return slotDirectory != null && Files.isRegularFile(slotDirectory.resolve(RETIRE_NAME));
    }

    /** Deletes the handle and pid file, then kills that pid. A busy member is the caller's decision. */
    public static void release(Path slotDirectory) {
        if (slotDirectory == null) return;
        long pid = read(slotDirectory).map(Handle::pid).orElse(0L);
        if (pid <= 0) pid = WarmProcess.rememberedPid(slotDirectory);
        try {
            Files.deleteIfExists(handleFile(slotDirectory));
            Files.deleteIfExists(slotDirectory.resolve(WarmProcess.PID_FILE_NAME));
            Files.deleteIfExists(slotDirectory.resolve(BUSY_NAME));
        } catch (IOException ignored) {
            // Kill still runs. A leftover file is stale on the next read.
        }
        if (pid > 0) ProcessTree.kill(pid);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
