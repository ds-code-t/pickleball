package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.VerbArgs;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code @file} and {@code --args-file} merge under explicit flags. The error does not echo a secret. */
class ArgsFileTest {
    private static final String SECRET = "hunter2-r4-secret";

    @TempDir
    Path project;

    @Test
    void fileSuppliesNameAndTheCliFlagWins() throws Exception {
        Path file = project.resolve("args.json");
        Files.writeString(file, "{\"name\":\"from-file\"}", StandardCharsets.UTF_8);
        String token = "@" + file.toAbsolutePath();
        WorkbenchCommandLine.Parsed fromFile = WorkbenchCommandLine.parse(new String[] {"doctor", token});
        assertEquals("from-file", fromFile.name());
        WorkbenchCommandLine.Parsed overridden = WorkbenchCommandLine.parse(
                new String[] {"doctor", "--name=from-cli", token});
        assertEquals("from-cli", overridden.name());
        Map<String, String> merged = ArgsFiles.merge(
                "doctor",
                Map.of("name", "from-cli"),
                Map.of("name", "from-file"));
        assertEquals("from-cli", merged.get("name"));
    }

    @Test
    void unknownKeyExitsNonZeroAndHidesTheSecret() throws Exception {
        Path file = project.resolve("args.json");
        Files.writeString(file, "{\"not-a-flag\":\"" + SECRET + "\"}", StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                new String[] {"doctor", "@" + file.toAbsolutePath()},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        String errors = err.toString(StandardCharsets.UTF_8);
        assertNotEquals(0, code, errors);
        assertTrue(errors.contains("error: unknown args key not-a-flag"), errors);
        assertTrue(errors.contains("fix:"), errors);
        assertFalse(errors.contains(SECRET), errors);
        assertFalse(out.toString(StandardCharsets.UTF_8).contains(SECRET), out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void dispatchLogsTheFileAndNotTheBody() throws Exception {
        Path file = project.resolve("args.json");
        Files.writeString(file, "{\"not-a-flag\":\"" + SECRET + "\"}", StandardCharsets.UTF_8);
        String previous = System.getProperty("pkb_engine_log");
        System.setProperty("pkb_engine_log", "dispatch");
        try {
            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("project", project.toString());
            flags.put(VerbArgs.ARGV, VerbArgs.encode(new String[] {"doctor", "@" + file.toAbsolutePath()}));
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int code = VerbDispatch.run(
                    "doctor",
                    flags,
                    new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                    new PrintStream(err, true, StandardCharsets.UTF_8));
            assertNotEquals(0, code, err.toString(StandardCharsets.UTF_8));
            String log = Files.readString(project.resolve(".pickleball/engine/engine.log"), StandardCharsets.UTF_8);
            assertTrue(log.contains("dispatch doctor file=yes"), log);
            assertFalse(log.contains(SECRET), log);
            assertFalse(err.toString(StandardCharsets.UTF_8).contains(SECRET), err.toString(StandardCharsets.UTF_8));
        } finally {
            if (previous == null) System.clearProperty("pkb_engine_log");
            else System.setProperty("pkb_engine_log", previous);
        }
    }

    @Test
    void brokenJsonAndATagDoNotBecomeAFile() throws Exception {
        Path file = project.resolve("broken.json");
        Files.writeString(file, "{\"name\":\"" + SECRET, StandardCharsets.UTF_8);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                new String[] {"doctor", "@" + file.toAbsolutePath()},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        String errors = err.toString(StandardCharsets.UTF_8);
        assertNotEquals(0, code, errors);
        assertTrue(errors.contains("error: args file is not a JSON object"), errors);
        assertFalse(errors.contains(SECRET), errors);

        ArgsFiles.Expansion smoke = ArgsFiles.expand(new String[] {"doctor", "@smoke"});
        assertFalse(smoke.fromFile());
        assertEquals(List.of("doctor", "@smoke"), List.of(smoke.argv()));

        Path callFile = project.resolve("call.json");
        Files.writeString(callFile, "{\"not-a-flag\":\"" + SECRET + "\"}", StandardCharsets.UTF_8);
        ArgsFiles.Expansion call = ArgsFiles.expand(new String[] {"call", "click", "@" + callFile.toAbsolutePath()});
        assertTrue(call.fromFile());
        assertTrue(List.of(call.argv()).stream().anyMatch(token -> token.startsWith("--args-file=")), List.of(call.argv()).toString());
        assertFalse(String.join("\n", call.argv()).contains(SECRET));
    }
}
