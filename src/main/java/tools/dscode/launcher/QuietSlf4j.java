package tools.dscode.launcher;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * A consumer classpath often has slf4j-api with no provider. SLF4J then prints three
 * "No SLF4J providers" lines on stderr in every CLI call, ahead of the JSON reply.
 * This binds SLF4J once at startup and drops only those lines; any other SLF4J
 * report (several providers, a version mismatch) still reaches stderr.
 */
final class QuietSlf4j {
    private QuietSlf4j() {
    }

    static void init() {
        Class<?> factory;
        try {
            factory = Class.forName("org.slf4j.LoggerFactory", false, QuietSlf4j.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError absent) {
            return;
        }
        PrintStream original = System.err;
        ByteArrayOutputStream held = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(held, true, StandardCharsets.UTF_8)) {
            System.setErr(capture);
            factory.getMethod("getILoggerFactory").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // A broken binding is reported by SLF4J itself on first real use.
        } finally {
            System.setErr(original);
        }
        String text = held.toString(StandardCharsets.UTF_8);
        if (text.isEmpty()) return;
        StringBuilder kept = new StringBuilder();
        for (String line : text.split("\\R")) {
            if (noProviderLine(line)) continue;
            if (!line.isEmpty()) kept.append(line).append(System.lineSeparator());
        }
        if (kept.length() > 0) original.print(kept);
    }

    static boolean noProviderLine(String line) {
        return line.startsWith("SLF4J: No SLF4J providers were found")
                || line.startsWith("SLF4J: Defaulting to no-operation (NOP) logger implementation")
                || (line.startsWith("SLF4J: See ") && line.contains("#noProviders"));
    }
}
