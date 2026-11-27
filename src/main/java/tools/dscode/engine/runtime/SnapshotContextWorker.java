package tools.dscode.engine.runtime;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Cold JVM for one snapshot context. The warm loader is not on this process.
 * The parent sends one JSON line per step on stdin. Every step of that parent
 * scenario runs in one child scenario, with {@code pkb_warm=off}, and this
 * process writes one JSON line back. {@code close} ends that child scenario
 * and exits, which returns the pool slot.
 */
public final class SnapshotContextWorker {
    private static SnapshotScenario session;

    private SnapshotContextWorker() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("pkb_warm", "off");
        OutputStream raw = new FileOutputStream(FileDescriptor.out);
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(captured, true, StandardCharsets.UTF_8);
        System.setOut(sink);
        System.setErr(sink);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                String op = SnapshotChannel.field(line, "op");
                if ("close".equals(op)) {
                    endSession();
                    writeLine(raw, SnapshotChannel.object("op", "closed", "ok", "true"));
                    break;
                }
                String response = "step".equals(op)
                        ? runStep(line, captured)
                        : SnapshotChannel.object("ok", "false", "status", "FAILED", "pid", pid(), "returned", "", "error", "unknown op", "log", "");
                writeLine(raw, response);
            }
        } finally {
            endSession();
        }
    }

    private static String runStep(String line, ByteArrayOutputStream captured) {
        captured.reset();
        String className = SnapshotChannel.field(line, "className");
        String text = SnapshotChannel.field(line, "text");
        String project = SnapshotChannel.field(line, "project");
        String gherkin = SnapshotChannel.field(line, "gherkin");
        try {
            Path root = project == null || project.isBlank()
                    ? Path.of("").toAbsolutePath()
                    : Path.of(project);
            if (session == null) session = SnapshotScenario.open(root, className);
            else session.include(className);
            SnapshotScenario.Outcome outcome = session.step(text, gherkin);
            return SnapshotChannel.object(
                    "ok", outcome.ok() ? "true" : "false",
                    "status", outcome.status(),
                    "pid", pid(),
                    "returned", outcome.returned() == null ? "" : outcome.returned(),
                    "error", outcome.error() == null ? "" : outcome.error(),
                    "log", log(captured));
        } catch (Throwable thrown) {
            String message = thrown.getMessage() == null ? thrown.toString() : thrown.getMessage();
            return SnapshotChannel.object(
                    "ok", "false",
                    "status", "FAILED",
                    "pid", pid(),
                    "returned", "",
                    "error", message,
                    "log", log(captured));
        }
    }

    private static void endSession() {
        SnapshotScenario ending = session;
        session = null;
        if (ending == null) return;
        try {
            ending.close();
        } catch (RuntimeException ignored) {
            // The parent still receives the close line, then destroys this process.
        }
    }

    private static String log(ByteArrayOutputStream captured) {
        String text = captured.toString(StandardCharsets.UTF_8);
        if (text.length() <= 4000) return text;
        return text.substring(0, 4000);
    }

    private static String pid() {
        return Long.toString(ProcessHandle.current().pid());
    }

    private static void writeLine(OutputStream raw, String json) throws java.io.IOException {
        raw.write(json.getBytes(StandardCharsets.UTF_8));
        raw.write('\n');
        raw.flush();
    }
}
