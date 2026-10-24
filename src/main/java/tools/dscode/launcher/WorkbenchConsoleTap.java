package tools.dscode.launcher;

import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Workbench-only console markers. A normal {@code mvn test} does not install
 * this and does not change what a print writes. Each Workbench scenario
 * prefixes a console line with {@code # pkb-console }, the URL-encoded
 * scenario name, and a space. The name match is exact. The saved console
 * file drops that prefix.
 */
public final class WorkbenchConsoleTap {
    public static final String MARKER = "# pkb-console ";
    private static final Object INSTALL = new Object();
    private static final InheritableThreadLocal<Deque<String>> STACK = new InheritableThreadLocal<>() {
        @Override
        protected Deque<String> initialValue() {
            return new ArrayDeque<>();
        }

        @Override
        protected Deque<String> childValue(Deque<String> parent) {
            return new ArrayDeque<>(parent);
        }
    };

    private WorkbenchConsoleTap() {
    }

    public static boolean active() {
        return active(System.getProperty("pkb_run_purpose"));
    }

    public static boolean active(String purpose) {
        return purpose != null && purpose.startsWith("workbench-");
    }

    public static void installIfActive() {
        if (active()) install();
    }

    public static void enter(String scenarioName) {
        if (!active()) return;
        install();
        STACK.get().push(scenarioName == null ? "" : scenarioName);
    }

    public static void leave() {
        flushInstalled();
        Deque<String> stack = STACK.get();
        if (stack != null && !stack.isEmpty()) stack.pop();
        if (stack == null || stack.isEmpty()) STACK.remove();
    }

    /** Drops a leaked name on this thread. Unit tests only. */
    static void resetForTest() {
        STACK.remove();
    }

    /**
     * Runs {@code call} on a thread that did not inherit the scenario name
     * (a pool thread created earlier) under the name of the submitting thread.
     */
    public static <T> Callable<T> carrying(Callable<T> call) {
        String name = current();
        if (name == null || call == null) return call;
        return () -> {
            enter(name);
            try {
                return call.call();
            } finally {
                leave();
            }
        };
    }

    public static void install() {
        synchronized (INSTALL) {
            if (!(System.out instanceof MarkingStream)) {
                System.setOut(wrap(System.out));
            }
            if (!(System.err instanceof MarkingStream)) {
                System.setErr(wrap(System.err));
            }
            retargetLogback();
            retargetLog4j();
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
        if (line == null || names == null || names.isEmpty() || !line.contains(MARKER)) return null;
        int at = line.indexOf(MARKER);
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

    /** Line text after the marker. The marker itself is not part of the result. */
    public static String payload(String line) {
        if (line == null) return "";
        int at = line.indexOf(MARKER);
        if (at < 0) return line;
        int start = at + MARKER.length();
        int end = line.indexOf(' ', start);
        if (end < 0) return "";
        return line.substring(end + 1);
    }

    /**
     * Points an already-started Logback {@code ConsoleAppender} at the marking
     * stream. Logback captures {@code System.out} when the appender starts,
     * which is often before the first scenario.
     */
    public static void retargetLogbackContext(Object context) {
        if (context == null) return;
        try {
            Class<?> ctxClass = Class.forName("ch.qos.logback.classic.LoggerContext");
            if (!ctxClass.isInstance(context)) return;
            @SuppressWarnings("unchecked")
            List<?> loggers = (List<?>) ctxClass.getMethod("getLoggerList").invoke(context);
            for (Object logger : loggers) {
                Object iterator = logger.getClass().getMethod("iteratorForAppenders").invoke(logger);
                if (!(iterator instanceof Iterator<?> appenders)) continue;
                while (appenders.hasNext()) retargetAppender(appenders.next());
            }
        } catch (Throwable ignored) {
            // No Logback, or a LoggerContext that does not expose appenders.
        }
    }

    private static void retargetLogback() {
        try {
            Class<?> factoryClass = Class.forName("org.slf4j.LoggerFactory");
            Object factory = factoryClass.getMethod("getILoggerFactory").invoke(null);
            retargetLogbackContext(factory);
        } catch (Throwable ignored) {
            // SLF4J is absent or not bound to Logback.
        }
    }

    private static void retargetAppender(Object appender) {
        Class<?> type = appender.getClass();
        while (type != null && type != Object.class) {
            for (String fieldName : List.of("outputStream", "os")) {
                try {
                    Field field = type.getDeclaredField(fieldName);
                    if (!OutputStream.class.isAssignableFrom(field.getType())) continue;
                    field.setAccessible(true);
                    Object current = field.get(appender);
                    if (current instanceof TapForward || current instanceof MarkingStream) return;
                    if (current instanceof OutputStream) {
                        field.set(appender, new TapForward());
                    }
                    return;
                } catch (NoSuchFieldException ignored) {
                    // Try the next name, then the superclass.
                } catch (IllegalAccessException ignored) {
                    return;
                }
            }
            type = type.getSuperclass();
        }
    }

    private static void retargetLog4j() {
        try {
            Class<?> logManager = Class.forName("org.apache.logging.log4j.LogManager");
            Object context = logManager.getMethod("getContext", boolean.class).invoke(null, Boolean.FALSE);
            if (context == null) return;
            Object config = context.getClass().getMethod("getConfiguration").invoke(context);
            if (config == null) return;
            Object appenders = config.getClass().getMethod("getAppenders").invoke(config);
            if (!(appenders instanceof java.util.Map<?, ?> map)) return;
            for (Object appender : map.values()) retargetLog4jAppender(appender);
        } catch (Throwable ignored) {
            // Log4j2 is absent, or this binding does not expose a configuration.
        }
    }

    private static void retargetLog4jAppender(Object appender) {
        if (appender == null) return;
        Object manager = readField(appender, "manager");
        if (manager == null) return;
        Object current = readField(manager, "outputStream");
        if (current instanceof TapForward || current instanceof MarkingStream) return;
        if (current instanceof OutputStream) {
            writeField(manager, "outputStream", new TapForward());
        }
    }

    private static Object readField(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null && type != Object.class) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (IllegalAccessException ignored) {
                return null;
            }
        }
        return null;
    }

    private static void writeField(Object target, String name, Object value) {
        Class<?> type = target.getClass();
        while (type != null && type != Object.class) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (IllegalAccessException ignored) {
                return;
            }
        }
    }

    private static void flushInstalled() {
        if (System.out instanceof MarkingStream out) out.flushPending();
        if (System.err instanceof MarkingStream err) err.flushPending();
    }

    private static String current() {
        Deque<String> stack = STACK.get();
        if (stack == null || stack.isEmpty()) return null;
        String name = stack.peek();
        return name == null || name.isBlank() ? null : name;
    }

    /** Writes to whatever {@code System.out} is now, without closing the old stream. */
    private static final class TapForward extends OutputStream {
        @Override
        public void write(int bite) {
            System.out.write(bite);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) {
            System.out.write(buffer, offset, length);
        }

        @Override
        public void flush() {
            System.out.flush();
        }

        @Override
        public void close() {
            // The original System.out must stay open.
        }
    }

    private static final class MarkingStream extends PrintStream {
        private final ThreadLocal<StringBuilder> pending = ThreadLocal.withInitial(StringBuilder::new);
        /** True while a finished line is copied to the delegate, so it is not marked again. */
        private final ThreadLocal<Boolean> bypass = ThreadLocal.withInitial(() -> Boolean.FALSE);

        MarkingStream(PrintStream delegate) {
            super(delegate, true, delegate.charset());
        }

        @Override
        public void print(String value) {
            String text = value == null ? "null" : value;
            if (passThrough()) {
                raw(text, false);
                return;
            }
            appendChars(text);
        }

        @Override
        public void print(char[] value) {
            print(value == null ? "null" : new String(value));
        }

        @Override
        public void println() {
            if (passThrough()) {
                raw("", true);
                return;
            }
            finishLine();
        }

        @Override
        public void println(String value) {
            String text = value == null ? "null" : value;
            if (passThrough()) {
                raw(text, true);
                return;
            }
            appendChars(text);
            finishLine();
        }

        @Override
        public void println(char[] value) {
            println(value == null ? "null" : new String(value));
        }

        @Override
        public void println(boolean value) {
            if (passThrough()) {
                super.println(value);
                return;
            }
            appendChars(Boolean.toString(value));
            finishLine();
        }

        @Override
        public void println(char value) {
            if (passThrough()) {
                super.println(value);
                return;
            }
            appendChars(String.valueOf(value));
            finishLine();
        }

        @Override
        public void println(int value) {
            if (passThrough()) {
                super.println(value);
                return;
            }
            appendChars(Integer.toString(value));
            finishLine();
        }

        @Override
        public void println(long value) {
            if (passThrough()) {
                super.println(value);
                return;
            }
            appendChars(Long.toString(value));
            finishLine();
        }

        @Override
        public void println(float value) {
            if (passThrough()) {
                super.println(value);
                return;
            }
            appendChars(Float.toString(value));
            finishLine();
        }

        @Override
        public void println(double value) {
            if (passThrough()) {
                super.println(value);
                return;
            }
            appendChars(Double.toString(value));
            finishLine();
        }

        @Override
        public void println(Object value) {
            if (passThrough()) {
                super.println(value);
                return;
            }
            appendChars(String.valueOf(value));
            finishLine();
        }

        @Override
        public void write(int bite) {
            if (passThrough()) {
                super.write(bite);
                return;
            }
            write(new byte[]{(byte) bite}, 0, 1);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) {
            if (passThrough()) {
                super.write(buffer, offset, length);
                return;
            }
            appendChars(new String(buffer, offset, length, charset()));
        }

        void flushPending() {
            synchronized (this) {
                StringBuilder acc = pending.get();
                if (acc.isEmpty()) return;
                emit(acc);
            }
        }

        /** No scenario on this thread, or the tap is off: write the original bytes. */
        private boolean passThrough() {
            return bypass.get() || !active() || current() == null;
        }

        private void raw(String text, boolean newline) {
            if (newline) super.println(text);
            else super.print(text);
        }

        private void appendChars(String text) {
            synchronized (this) {
                StringBuilder acc = pending.get();
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (c == '\n') emit(acc);
                    else if (c != '\r') acc.append(c);
                }
            }
        }

        private void finishLine() {
            synchronized (this) {
                emit(pending.get());
            }
        }

        private void emit(StringBuilder acc) {
            String text = acc.toString();
            acc.setLength(0);
            String name = current();
            bypass.set(true);
            try {
                super.print(name == null || name.isBlank() ? text : mark(name) + text);
                writeLineSeparator();
            } finally {
                bypass.set(false);
            }
        }

        private void writeLineSeparator() {
            byte[] separator = System.lineSeparator().getBytes(charset());
            super.write(separator, 0, separator.length);
        }
    }
}
