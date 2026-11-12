package tools.dscode.testsupport;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestPlan;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Gradle keeps each test's stdout until that test finishes. A dual-run of the
 * whole feature tree prints more than a small sandbox can hold. This wraps only
 * Gradle's own capture stream: a prefix still reaches Gradle, and the rest is
 * streamed to a capped file. A test that replaces {@code System.out} is left
 * alone, so its assertions still see the bytes it captured.
 */
public final class CappedTestConsole implements LauncherSessionListener, TestExecutionListener {
    private static final Object LOCK = new Object();

    public CappedTestConsole() {
    }

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        install();
    }

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        install();
    }

    static void install() {
        if (!enabled()) return;
        synchronized (LOCK) {
            arm(true);
            arm(false);
        }
    }

    private static boolean enabled() {
        String value = System.getProperty("pkb.test.console.cap", "on");
        return !"off".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value);
    }

    private static void arm(boolean stdout) {
        PrintStream current = stdout ? System.out : System.err;
        if (current == null || current instanceof CappedPrintStream) return;
        String name = stdout ? "out" : "err";
        if (isMarking(current)) {
            capDelegate(current, name);
            return;
        }
        if (!isGradleCapture(current)) return;
        OutputStream file = openFile(name);
        PrintStream capped = new CappedPrintStream(new CappedOutputStream(current, file, forwardLimit(), fileLimit()));
        if (stdout) System.setOut(capped);
        else System.setErr(capped);
    }

    private static boolean isMarking(PrintStream stream) {
        return stream.getClass().getName().endsWith("MarkingStream");
    }

    /** Gradle's test worker replaces System.out with this stream. Nothing else matches. */
    private static boolean isGradleCapture(Object stream) {
        String type = stream.getClass().getName();
        return type.contains("LinePerThreadBufferingOutputStream") || type.startsWith("org.gradle.");
    }

    /** Leave a console-tap stream in place and cap only a Gradle delegate. */
    private static void capDelegate(PrintStream marking, String name) {
        try {
            Field field = FilterOutputStream.class.getDeclaredField("out");
            field.setAccessible(true);
            Object delegate = field.get(marking);
            if (delegate instanceof CappedOutputStream || delegate instanceof CappedPrintStream) return;
            if (!(delegate instanceof OutputStream inner) || !isGradleCapture(inner)) return;
            field.set(marking, new CappedOutputStream(inner, openFile(name), forwardLimit(), fileLimit()));
        } catch (ReflectiveOperationException ignored) {
            // The tap keeps its own stream.
        }
    }

    private static int forwardLimit() {
        return positive("pkb.test.console.forward.bytes", 256 * 1024);
    }

    private static int fileLimit() {
        return positive("pkb.test.console.file.bytes", 4 * 1024 * 1024);
    }

    private static int positive(String key, int fallback) {
        try {
            int value = Integer.parseInt(System.getProperty(key, Integer.toString(fallback)));
            return value > 0 ? value : fallback;
        } catch (NumberFormatException failure) {
            return fallback;
        }
    }

    private static OutputStream openFile(String name) {
        String dir = System.getProperty("pkb.test.console.dir");
        if (dir == null || dir.isBlank()) return null;
        try {
            Path folder = Path.of(dir);
            Files.createDirectories(folder);
            return Files.newOutputStream(folder.resolve("std-" + name.toLowerCase(Locale.ROOT) + ".log"));
        } catch (IOException failure) {
            return null;
        }
    }

    static final class CappedPrintStream extends PrintStream {
        CappedPrintStream(OutputStream out) {
            super(out, true, StandardCharsets.UTF_8);
        }
    }

    static final class CappedOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final OutputStream file;
        private final int forwardLimit;
        private final int fileLimit;
        private int forwarded;
        private int filed;
        private boolean closedNotice;

        CappedOutputStream(OutputStream delegate, OutputStream file, int forwardLimit, int fileLimit) {
            this.delegate = delegate;
            this.file = file;
            this.forwardLimit = forwardLimit;
            this.fileLimit = fileLimit;
        }

        @Override
        public synchronized void write(int bite) throws IOException {
            byte[] one = {(byte) bite};
            write(one, 0, 1);
        }

        @Override
        public synchronized void write(byte[] buffer, int offset, int length) throws IOException {
            if (buffer == null || length <= 0) return;
            if (file != null && filed < fileLimit) {
                int room = Math.min(length, fileLimit - filed);
                file.write(buffer, offset, room);
                filed += room;
                if (filed >= fileLimit) file.flush();
            }
            if (forwarded < forwardLimit) {
                int room = Math.min(length, forwardLimit - forwarded);
                delegate.write(buffer, offset, room);
                forwarded += room;
                if (forwarded >= forwardLimit && !closedNotice) {
                    closedNotice = true;
                    byte[] note = "\n[pkb] further test console output was capped\n".getBytes(StandardCharsets.UTF_8);
                    delegate.write(note);
                }
            }
        }

        @Override
        public synchronized void flush() throws IOException {
            if (file != null) file.flush();
            delegate.flush();
        }

        @Override
        public void close() {
            // Gradle and the console tap still own the delegate.
        }
    }
}
