package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.browser.proto.ProtoHost;
import tools.dscode.control.protocol.LiveBridgeFile;
import tools.dscode.control.protocol.LiveHostFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P2: pw parsing, frame-list routing, and the orphan filter. No browser. */
class ConsumerFixesP2CliTest {
    @TempDir
    Path temp;

    @Test
    void pwSkipsOnlyTheProjectPathAndFlagsWin() throws Exception {
        Path project = Files.createDirectories(temp.resolve("proj"));
        assertEquals(List.of("page", "title"),
                PwCommand.positional(new String[]{"pw", project.toString(), "page", "title"}, project));
        assertEquals(List.of("page", "title"),
                PwCommand.positional(new String[]{"pw", ".", "page", "title"}, project));
        assertEquals(List.of("page", "list"),
                PwCommand.positional(new String[]{"pw", "..", "page", "list"}, project));
        Path other = Files.createDirectories(temp.resolve("screenshots"));
        List<String> kept = PwCommand.positional(new String[]{"pw", other.toString(), "title"}, project);
        assertEquals(other.toString().toLowerCase(Locale.ROOT), kept.get(0));
        assertEquals("title", kept.get(1));

        Capture bare = pw(project, "pw", "page", "title");
        assertEquals(1, bare.code, bare.out + bare.err);
        assertFalse(bare.err.contains("pw needs an area"), bare.err);
        assertTrue(bare.err.contains("no CDP endpoint"), bare.err);

        Capture flags = pw(project, "pw", "--area=page", "--action=title", project.toString());
        assertEquals(1, flags.code, flags.out + flags.err);
        assertFalse(flags.err.contains("pw needs an area"), flags.err);
        assertTrue(flags.err.contains("no CDP endpoint"), flags.err);

        Capture withDir = pw(project, "pw", project.toString(), "page", "title");
        assertEquals(1, withDir.code, withDir.out + withDir.err);
        assertFalse(withDir.err.contains("pw needs an area"), withDir.err);
        assertTrue(withDir.err.contains("no CDP endpoint"), withDir.err);

        Capture missing = pw(project, "pw", "run", "--steps=missing-steps.json");
        assertEquals(1, missing.code, missing.out + missing.err);
        assertTrue(missing.err.contains("steps file not found"), missing.err);
        assertTrue(missing.err.contains(project.toAbsolutePath().normalize().toString()), missing.err);
        assertFalse(missing.err.contains("no CDP endpoint"), missing.err);
    }

    @Test
    void pwKeepsAMissingProjectPathAheadOfAnAreaWord() throws Exception {
        Path missing = temp.resolve("not-created-yet");
        assertFalse(Files.exists(missing));
        Capture none = cli(missing, "pw", "page", "list");
        assertEquals(1, none.code, none.out + none.err);
        assertFalse((none.out + none.err).contains("pw needs an area"), none.out + none.err);
        assertTrue(none.err.startsWith("error: no CDP endpoint for Playwright"), none.err);

        Path areaDir = Path.of("locator").toAbsolutePath().normalize();
        boolean created = false;
        try {
            if (!Files.exists(areaDir)) {
                Files.createDirectory(areaDir);
                created = true;
            }
            assertTrue(Files.isDirectory(areaDir), areaDir.toString());
            WorkbenchCommandLine.Parsed bare = WorkbenchCommandLine.parse(new String[]{"pw", "locator", "list"});
            assertFalse(areaDir.equals(bare.project().toAbsolutePath().normalize()), bare.project().toString());
            WorkbenchCommandLine.Parsed parsed = WorkbenchCommandLine.parse(
                    new String[]{"pw", missing.toString(), "locator", "list"});
            assertEquals(missing.toAbsolutePath().normalize(), parsed.project().toAbsolutePath().normalize());
        } finally {
            if (created) Files.deleteIfExists(areaDir);
        }
    }

    @Test
    void callPwArgsFileIsAnAreaNotAUsageError() throws Exception {
        Path project = Files.createDirectories(temp.resolve("call"));
        Path file = project.resolve("pw-args.json");
        Files.writeString(file, "{\"area\":\"page\",\"action\":\"title\"}");
        Capture call = cli(project, "call", "pw", "--args-file=" + file);
        assertEquals(1, call.code, call.out + call.err);
        assertFalse((call.out + call.err).contains("pw needs an area"), call.out + call.err);
        assertTrue(call.err.contains("no CDP endpoint"), call.err);
    }

    @Test
    void frameListUsesTheStartHostWhileAWorkerIsAlive() throws Exception {
        Path project = Files.createDirectories(temp.resolve("route"));
        long pid = ProcessHandle.current().pid();
        LiveHostFile.write(project, new LiveHostFile.Host(
                "http://127.0.0.1:18001/", "token-start", 18001, pid, 0L, "start-session",
                false, "chrome", true, "", true, 0L, 0L));
        LiveBridgeFile.write(project, new LiveBridgeFile.Bridge(
                "127.0.0.1", 18002, "token-worker", pid, "worker-session"));

        Optional<LiveBridgeFile.Bridge> listed = WorkbenchAgentCommands.pageTargetFor(project, "", "frame", Map.of());
        assertTrue(listed.isPresent());
        assertEquals(18001, listed.get().port());

        Optional<LiveBridgeFile.Bridge> find = WorkbenchAgentCommands.pageTargetFor(project, "", "find", Map.of());
        assertTrue(find.isPresent());
        assertEquals(18002, find.get().port());

        Optional<LiveBridgeFile.Bridge> entered = WorkbenchAgentCommands.pageTargetFor(
                project, "", "frame", Map.of("enter", "inner"));
        assertTrue(entered.isPresent());
        assertEquals(18002, entered.get().port());

        Optional<LiveBridgeFile.Bridge> reset = WorkbenchAgentCommands.pageTargetFor(
                project, "", "frame", Map.of("reset", "true"));
        assertTrue(reset.isPresent());
        assertEquals(18002, reset.get().port());
    }

    @Test
    void orphansNeverListAJavaProtocolHost() {
        String needle = temp.toAbsolutePath().normalize().toString();
        assertFalse(ConsumerReadyCommands.listsOrphan(
                "C:\\Java\\bin\\java.exe -cp app.jar tools.Main --browser=chrome pickleball " + needle, needle));
        assertFalse(ConsumerReadyCommands.listsOrphan(
                "C:\\Java\\bin\\javaw.exe chrome pickleball " + needle, needle));
        assertTrue(ConsumerReadyCommands.listsOrphan(
                "/usr/bin/google-chrome --user-data-dir=/tmp/x pickleball " + needle, needle));
        assertFalse(ConsumerReadyCommands.listsOrphan(
                "/usr/bin/google-chrome --type=renderer pickleball " + needle, needle));
        assertFalse(ConsumerReadyCommands.listsOrphan(
                "/usr/bin/google-chrome --class=protohost pickleball " + needle, needle));
        assertFalse(ConsumerReadyCommands.listsOrphan(
                "/usr/bin/google-chrome protocol-host pickleball " + needle, needle));
    }

    private static Capture pw(Path project, String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = PwCommand.run(args, project,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private static Capture cli(Path project, String... args) {
        String[] command = new String[args.length + 1];
        command[0] = args[0];
        command[1] = project.toString();
        System.arraycopy(args, 1, command, 2, args.length - 1);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(command,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    @Test
    void ownerOnlyRestrictsASecretFile() throws Exception {
        Path file = temp.resolve("session.json");
        Files.writeString(file, "{\"cookies\":[{\"value\":\"keep-me\"}]}");
        ProtoHost.ownerOnly(file);
        ContextLiveTest.assertOwnerOnly(file);
        assertTrue(Files.readString(file).contains("keep-me"));
    }

    private record Capture(int code, String out, String err) {
    }
}
