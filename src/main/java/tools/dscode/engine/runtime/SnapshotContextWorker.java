package tools.dscode.engine.runtime;

import tools.dscode.engine.EngineOptions;
import tools.dscode.engine.PickleballEngine;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.Status;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * Cold JVM for one snapshot context. The warm loader is not on this process.
 * The parent sends one JSON line per step on stdin. This process runs that step
 * with {@code pkb_warm=off} and writes one JSON line to stdout. {@code close} exits
 * and frees the pool slot.
 */
public final class SnapshotContextWorker {
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
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            String op = SnapshotChannel.field(line, "op");
            if ("close".equals(op)) {
                writeLine(raw, SnapshotChannel.object("op", "closed", "ok", "true"));
                break;
            }
            String response = "step".equals(op)
                    ? runStep(line, captured)
                    : SnapshotChannel.object("ok", "false", "status", "FAILED", "pid", pid(), "returned", "", "error", "unknown op", "log", "");
            writeLine(raw, response);
        }
    }

    private static String runStep(String line, ByteArrayOutputStream captured) {
        captured.reset();
        String className = SnapshotChannel.field(line, "className");
        String text = SnapshotChannel.field(line, "text");
        String project = SnapshotChannel.field(line, "project");
        String gherkin = SnapshotChannel.field(line, "gherkin");
        try {
            Class<?> glue = Class.forName(className);
            Path root = project == null || project.isBlank()
                    ? Path.of("").toAbsolutePath()
                    : Path.of(project);
            EngineOptions options = EngineOptions.of(glue).project(root);
            RunResult result = gherkin == null || gherkin.isBlank()
                    ? PickleballEngine.run(List.of(text), options)
                    : PickleballEngine.run(gherkin, options);
            String returned = "";
            if (!result.scenarios().isEmpty() && !result.scenarios().getFirst().steps().isEmpty()) {
                returned = result.scenarios().getFirst().steps().getFirst().returned();
            }
            boolean passed = result.status() == Status.PASSED;
            String error = passed ? "" : result.message();
            return SnapshotChannel.object(
                    "ok", passed ? "true" : "false",
                    "status", result.status() == null ? "" : result.status().name(),
                    "pid", pid(),
                    "returned", returned == null ? "" : returned,
                    "error", error == null ? "" : error,
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
