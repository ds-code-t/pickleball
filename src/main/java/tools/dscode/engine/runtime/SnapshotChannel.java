package tools.dscode.engine.runtime;

import tools.dscode.engine.EngineLog;
import tools.dscode.engine.model.DocText;
import tools.dscode.engine.model.StepArgument;
import tools.dscode.engine.model.Table;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stdin/stdout JSON between a snapshot context and its cold child JVM.
 * One line in each direction. The parent does not read the child's stdout until a step
 * or a close, so a child that only prints and exits keeps that stream for its caller.
 */
public final class SnapshotChannel {
    private static final ConcurrentHashMap<Process, Link> LINKS = new ConcurrentHashMap<>();

    private SnapshotChannel() {
    }

    /**
     * Runs one step in the child. Returns the step's returned text, or null when the step
     * returned nothing. A failed step throws {@link IllegalStateException} whose message is
     * the child's error.
     */
    public static Object step(Process process, String snapshotId, Path project, String className, String text, String gherkin) {
        if (process == null) {
            throw new IllegalStateException("error: cold child is missing\nfix: spine sample");
        }
        Link link = LINKS.computeIfAbsent(process, Link::new);
        String request = object(
                "op", "step",
                "className", className == null ? "" : className,
                "text", text == null ? "" : text,
                "project", project == null ? "" : project.toAbsolutePath().normalize().toString(),
                "gherkin", gherkin == null ? "" : gherkin);
        String response;
        synchronized (link) {
            try {
                link.writer.write(request);
                link.writer.write("\n");
                link.writer.flush();
                response = link.reader.readLine();
            } catch (IOException failure) {
                throw new IllegalStateException("error: cold child step channel failed\nfix: spine sample", failure);
            }
        }
        if (response == null) {
            String exit = process.isAlive() ? "alive" : "exit " + process.exitValue();
            throw new IllegalStateException(
                    "error: cold child closed the step channel (" + exit + ")\nfix: spine sample\n" + link.stderr());
        }
        String ok = field(response, "ok");
        String status = field(response, "status");
        String pid = field(response, "pid");
        String returned = field(response, "returned");
        String error = field(response, "error");
        Path logged = project == null ? Path.of("").toAbsolutePath() : project;
        EngineLog.log(logged, "gate", snapshotId == null || snapshotId.isBlank() ? "sample" : snapshotId,
                "subprocess status=" + status + " pid=" + pid);
        if (!"true".equals(ok)) {
            String message = error == null || error.isBlank()
                    ? "error: cold child step failed\nfix: spine sample"
                    : error;
            throw new IllegalStateException(message);
        }
        return returned == null || returned.isEmpty() ? null : returned;
    }

    /** Asks the child to exit. A child that already left is ignored. */
    public static void close(Process process) {
        if (process == null || !process.isAlive()) return;
        Link link = LINKS.computeIfAbsent(process, Link::new);
        synchronized (link) {
            try {
                link.writer.write(object("op", "close"));
                link.writer.write("\n");
                link.writer.flush();
                link.reader.readLine();
            } catch (IOException ignored) {
                // destroyForcibly is the backstop when the pipe is already closed.
            }
        }
    }

    /** Gherkin for a step that carries a doc string or a table. Blank when the step is text only. */
    public static String gherkin(String phrase, StepArgument argument) {
        if (argument == null || argument instanceof StepArgument.None) return "";
        StringBuilder feature = new StringBuilder();
        feature.append("Feature: cold\n  Scenario: cold\n    * ");
        feature.append(phrase == null ? "" : phrase.replace("\r", " ").replace("\n", " "));
        feature.append('\n');
        if (argument instanceof StepArgument.DocArg doc) {
            DocText body = doc.doc();
            feature.append("      \"\"\"\n");
            String content = body == null || body.content() == null ? "" : body.content();
            for (String line : content.split("\n", -1)) {
                feature.append("      ").append(line).append('\n');
            }
            feature.append("      \"\"\"\n");
            return feature.toString();
        }
        if (argument instanceof StepArgument.TableArg table) {
            Table rows = table.table();
            if (rows != null) {
                for (List<String> row : rows.rows()) {
                    feature.append("      |");
                    if (row != null) {
                        for (String cell : row) {
                            String safe = cell == null ? "" : cell.replace("|", "\\|");
                            feature.append(' ').append(safe).append(" |");
                        }
                    }
                    feature.append('\n');
                }
            }
        }
        return feature.toString();
    }

    public static String object(String... pairs) {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (i > 0) json.append(',');
            json.append('"').append(pairs[i]).append("\":\"").append(escape(pairs[i + 1])).append('"');
        }
        return json.append('}').toString();
    }

    public static String field(String json, String key) {
        if (json == null || key == null) return "";
        String needle = "\"" + key + "\":\"";
        int start = json.indexOf(needle);
        if (start < 0) return "";
        start += needle.length();
        StringBuilder value = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '\\' && i + 1 < json.length()) {
                char next = json.charAt(++i);
                value.append(switch (next) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case '"' -> '"';
                    case '\\' -> '\\';
                    default -> next;
                });
                continue;
            }
            if (ch == '"') break;
            value.append(ch);
        }
        return value.toString();
    }

    private static String escape(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch >= 0x20) out.append(ch);
                }
            }
        }
        return out.toString();
    }

    private static final class Link {
        private final BufferedReader reader;
        private final OutputStreamWriter writer;
        private final StringBuilder stderr = new StringBuilder();

        private Link(Process process) {
            this.writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
            this.reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            Thread drain = new Thread(() -> drain(process.getErrorStream()), "pkb-cold-stderr");
            drain.setDaemon(true);
            drain.start();
        }

        private void drain(InputStream stream) {
            byte[] buffer = new byte[1024];
            try {
                int read;
                while ((read = stream.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    synchronized (stderr) {
                        if (stderr.length() < 4000) {
                            int room = 4000 - stderr.length();
                            stderr.append(new String(buffer, 0, Math.min(read, room), StandardCharsets.UTF_8));
                        }
                    }
                }
            } catch (IOException ignored) {
                // The child closed the pipe.
            }
        }

        private String stderr() {
            synchronized (stderr) {
                return stderr.toString();
            }
        }
    }
}
