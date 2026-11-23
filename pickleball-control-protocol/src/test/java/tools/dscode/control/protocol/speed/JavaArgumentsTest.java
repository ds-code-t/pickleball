package tools.dscode.control.protocol.speed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaArgumentsTest {
    @TempDir
    Path temp;

    @Test
    void longClasspathBecomesOneQuotedArgumentFile() throws Exception {
        String classpath = "C:\\Program Files\\a.jar;C:\\lib\\\"b\".jar";
        List<String> command = new ArrayList<>();
        command.add("java");
        command.add("-cp");
        command.add("short.jar");
        assertFalse(JavaArguments.exceedsWindowsCommandLimit(command));
        command.set(2, "x".repeat(JavaArguments.WINDOWS_COMMAND_LIMIT));
        assertTrue(JavaArguments.exceedsWindowsCommandLimit(command));
        JavaArguments.fitClasspath(command, temp.resolve("long.args"));
        assertTrue(command.get(2).startsWith("@"), command.get(2));
        assertEquals(
                JavaArguments.quote("x".repeat(JavaArguments.WINDOWS_COMMAND_LIMIT)),
                Files.readString(Path.of(command.get(2).substring(1))).trim()
        );
        String token = JavaArguments.classpathReference(temp.resolve("cp.args"), classpath);
        assertTrue(token.startsWith("@"));
        assertEquals(JavaArguments.quote(classpath), Files.readString(Path.of(token.substring(1))).trim());
        List<String> shortCommand = new ArrayList<>(List.of("java", "-cp", "short.jar"));
        JavaArguments.fitClasspath(shortCommand, temp.resolve("short.args"));
        assertEquals("short.jar", shortCommand.get(2));
    }
}
