package tools.dscode.launcher;

import java.io.PrintStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Workbench-only console markers. A normal {@code mvn test} does not install
 * this and does not change what {@code print} writes. Each Workbench scenario
 * prefixes {@link PrintStream#print(String)} with {@code # pkb-console }, the
 * URL-encoded scenario name, and a space. The name match is exact.
 */
public final class WorkbenchConsoleTap {
    public static final String MARKER = "# pkb-console ";
    private static final Object INSTALL = new Object();
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private WorkbenchConsoleTap() {
    }

    public static boolean active() {
        return active(System.getProperty("pkb_run_purpose"));
    }

    public static boolean active(String purpose) {
        return purpose != null && purpose.startsWith("workbench-");
    }

    public static void enter(String scenarioName) {
        if (!active()) return;
        install();
        CURRENT.set(scenarioName == null ? "" : scenarioName);
    }

    public static void leave() {
        CURRENT.remove();
    }

    public static void install() {
        synchronized (INSTALL) {
            if (!(System.out instanceof MarkingStream)) {
                System.setOut(wrap(System.out));
            }
            if (!(System.err instanceof MarkingStream)) {
                System.setErr(wrap(System.err));
            }
        }
    }

    public static PrintStream wrap(PrintStream delegate) {
        return new MarkingStream(delegate);
    }

    public static String mark(String name) {
        String encoded = URLEncoder.encode(name == null ? "" : name, StandardCharsets.UTF_8);
        return MARKER + encoded + " ";
    }

    /** Exact scenario name from a marked line, or null when the line has no marker. */
    public static String scenarioOnMarkedLine(String line, List<String> names) {
        if (line == null || names == null || names.isEmpty()) return null;
        int at = line.indexOf(MARKER);
        if (at < 0) return null;
        int start = at + MARKER.length();
        int end = line.indexOf(' ', start);
        if (end < 0) return null;
        String decoded;
        try {
            decoded = URLDecoder.decode(line.substring(start, end), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException failure) {
            return null;
        }
        for (String name : names) {
            if (name != null && name.equals(decoded)) return name;
        }
        return null;
    }

    private static final class MarkingStream extends PrintStream {
        MarkingStream(PrintStream delegate) {
            super(delegate, true, StandardCharsets.UTF_8);
        }

        @Override
        public void print(String value) {
            String name = CURRENT.get();
            if (name == null) {
                super.print(value);
                return;
            }
            super.print(mark(name) + (value == null ? "null" : value));
        }
    }
}
