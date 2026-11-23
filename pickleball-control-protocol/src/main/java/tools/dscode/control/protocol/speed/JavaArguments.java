package tools.dscode.control.protocol.speed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Java command lines that can exceed the Windows 32767-character limit.
 * A classpath over that limit is one quoted argument in an {@code @file}.
 */
public final class JavaArguments {
    /** Leave room for quotes and the executable path under the 32767 limit. */
    public static final int WINDOWS_COMMAND_LIMIT = 24_000;

    private JavaArguments() {
    }

    public static boolean exceedsWindowsCommandLimit(List<String> command) {
        if (command == null) return false;
        int length = 0;
        for (String argument : command) {
            if (argument != null) length += argument.length() + 1;
        }
        return length > WINDOWS_COMMAND_LIMIT;
    }

    /**
     * Writes {@code classpath} as one quoted argument and returns {@code @absolutePath}.
     * The command uses that token after {@code -cp}.
     */
    public static String classpathReference(Path file, String classpath) throws IOException {
        Path target = file.toAbsolutePath().normalize();
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Files.writeString(target, quote(classpath) + "\n", StandardCharsets.UTF_8);
        return "@" + target;
    }

    /** Replaces a long {@code -cp} value with {@link #classpathReference}. A short command stays inline. */
    public static void fitClasspath(List<String> command, Path file) throws IOException {
        if (!exceedsWindowsCommandLimit(command)) return;
        int index = command == null ? -1 : command.indexOf("-cp");
        if (index < 0 || index + 1 >= command.size()) return;
        String current = command.get(index + 1);
        if (current != null && current.startsWith("@")) return;
        command.set(index + 1, classpathReference(file, current));
    }

    public static String quote(String value) {
        String raw = value == null ? "" : value;
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
