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
    private static final String TREE = """
            public class PkbTree {
              public static void main(String[] args) throws Exception {
                Process child = new ProcessBuilder(args[0], args[1], args[2])
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
                try {
                  Thread.sleep(Long.parseLong(args[2]));
                } finally {
                  child.destroyForcibly();
                  child.waitFor();
                }
              }
            }
            """;
    private static final String SPEAK = """
            public class PkbSpeak {
              public static void main(String[] args) throws Exception {
                System.out.println("Scenario: Pend case NEW UI # C:/Program Files/Pickleball/page.feature:12");
                System.err.print(java.nio.file.Files.readString(java.nio.file.Path.of(args[0])));
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
     * {@code code} is 0. An interrupted wait becomes {@link IOException} so a
     * starter that only throws {@code IOException} can call this.
     */
    public static Process exited(int code) throws IOException {
        Path source = source("PkbExit.java", EXIT);
        Process process = new ProcessBuilder(
                javaBin().toString(),
                source.toAbsolutePath().toString(),
                Integer.toString(code)
        ).start();
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("already-exited child was still alive");
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("already-exited child was interrupted", interrupted);
        }
        return process;
    }

    /**
     * A parent and a child that both stay up for {@code seconds}. The child is a
     * descendant of the returned process. Replaces {@code sh -c "sleep & wait"}.
     */
    public static Process sleepTree(long seconds) throws IOException {
        Path hold = source("PkbHold.java", HOLD);
        Path tree = source("PkbTree.java", TREE);
        long millis = Math.max(0, seconds) * 1000L;
        return new ProcessBuilder(
                javaBin().toString(),
                tree.toAbsolutePath().toString(),
                javaBin().toString(),
                hold.toAbsolutePath().toString(),
                Long.toString(millis)
        ).start();
    }

    /**
     * Command whose stdout is one scenario line and whose stderr is the bytes of
     * {@code message}. Replaces {@code /bin/sh} for stream-copy tests.
     */
    public static List<String> speakCommand(Path message) throws IOException {
        Path source = source("PkbSpeak.java", SPEAK);
        return List.of(javaBin().toString(), source.toAbsolutePath().toString(), message.toAbsolutePath().toString());
    }

    /**
     * A launcher that records its arguments, then either exits 0 or stays up until
     * it is killed. POSIX writes one argument per line. Windows writes the raw
     * command tail so a {@code =} stays inside the argument. The returned path
     * is the file to execute.
     */
    public static Path argumentLog(Path path, Path log, boolean stay) throws IOException {
        Path hold = source("PkbHold.java", HOLD);
        String logText = log.toAbsolutePath().toString();
        String java = javaBin().toString();
        String holdText = hold.toAbsolutePath().toString();
        String posix = "#!/bin/sh\nprintf '%s\\n' \"$@\" >> " + shQuote(logText) + "\n"
                + (stay
                ? "if [ \"$1\" = \"--exit-now\" ]; then exit 0; fi\nexec " + shQuote(java) + " " + shQuote(holdText) + " 3600000\n"
                : "exit 0\n");
        // cmd splits a batch %1 on "=". %* is the raw tail, so --app=http:// stays one argument.
        String windows = "@echo off\r\nsetlocal EnableExtensions\r\nset \"FIRST=%~1\"\r\n"
                + "if not \"%~1\"==\"\" >>\"" + logText + "\" echo(%*\r\n"
                + (stay
                ? "if \"%FIRST%\"==\"--exit-now\" exit /b 0\r\n\"" + java + "\" \"" + holdText + "\" 3600000\r\nexit /b %ERRORLEVEL%\r\n"
                : "exit /b 0\r\n");
        return platformScript(path, posix, windows);
    }

    /** Deletes {@code file} once nothing holds it. A missing file returns at once. */
    public static void releaseFile(Path file) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        IOException last = null;
        while (System.nanoTime() < deadline) {
            try {
                Files.deleteIfExists(file);
                return;
            } catch (IOException failure) {
                last = failure;
                Thread.sleep(50);
            }
        }
        throw new IOException("file stayed locked: " + file, last);
    }

    private static String shQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
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
