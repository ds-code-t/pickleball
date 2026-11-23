package tools.dscode.testsupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Java stand-ins for Unix {@code sleep} and {@code true}, and platform scripts
 * for test fakes. A {@code .cmd} file is the Windows launcher. A shell script
 * with the owner execute bit is the POSIX launcher. POSIX mode bits are not
 * set on Windows.
 */
public final class PortableProcess {
    private static final String HOLD = """
            public class PkbHold {
              public static void main(String[] args) throws Exception {
                long millis = args.length == 0 ? Long.MAX_VALUE : Long.parseLong(args[0]);
                Thread.sleep(millis);
              }
            }
            """;
    private static final String EXIT = """
            public class PkbExit {
              public static void main(String[] args) {
                System.exit(args.length == 0 ? 0 : Integer.parseInt(args[0]));
              }
            }
            """;

    private PortableProcess() {
    }

    public static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public static Path javaBin() {
        String name = windows() ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", name);
    }

    /** Live child that stays up for {@code seconds}. Replaces Unix {@code sleep}. */
    public static Process sleep(long seconds) throws IOException {
        return sleepBuilder(seconds).start();
    }

    public static ProcessBuilder sleepBuilder(long seconds) throws IOException {
        return new ProcessBuilder(sleepCommand(seconds));
    }

    public static List<String> sleepCommand(long seconds) throws IOException {
        Path source = source("PkbHold.java", HOLD);
        return List.of(javaBin().toString(), source.toAbsolutePath().toString(), Long.toString(Math.max(0, seconds) * 1000L));
    }

    /**
     * A Java process that has already exited. Replaces Unix {@code true} when
     * {@code code} is 0.
     */
    public static Process exited(int code) throws IOException, InterruptedException {
        Path source = source("PkbExit.java", EXIT);
        Process process = new ProcessBuilder(
                javaBin().toString(),
                source.toAbsolutePath().toString(),
                Integer.toString(code)
        ).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("already-exited child was still alive");
        }
        return process;
    }

    /**
     * Writes {@code path} on POSIX and {@code path} plus {@code .cmd} on Windows
     * when the name is not already a batch file. Returns the path to launch.
     */
    public static Path platformScript(Path path, String posixBody, String windowsBody) throws IOException {
        boolean windows = windows();
        Path launcher = windows ? batchPath(path) : path;
        if (launcher.getParent() != null) Files.createDirectories(launcher.getParent());
        Files.writeString(launcher, windows ? windowsBody : posixBody, StandardCharsets.UTF_8);
        if (!windows) markExecutable(launcher);
        return launcher;
    }

    /** A launcher that exits 0. Shell on POSIX, batch on Windows. */
    public static Path exitZero(Path path) throws IOException {
        return platformScript(path, "#!/bin/sh\nexit 0\n", "@echo off\r\nexit /b 0\r\n");
    }

    /** Sets the owner execute bit when the file system has POSIX permissions. */
    public static void markExecutable(Path path) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (view == null) return;
        view.setPermissions(EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
        ));
    }

    static Path batchPath(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".cmd") || lower.endsWith(".bat")) return path;
        Path parent = path.getParent();
        Path renamed = Path.of(name + ".cmd");
        return parent == null ? renamed : parent.resolve(renamed);
    }

    private static Path source(String fileName, String content) throws IOException {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "pkb-portable-process");
        Files.createDirectories(dir);
        Path target = dir.resolve(fileName);
        if (Files.isRegularFile(target) && content.equals(Files.readString(target))) return target;
        Path temporary = Files.createTempFile(dir, "src-", ".tmp");
        Files.writeString(temporary, content, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }
}
