package tools.dscode.control.protocol;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one writer for {@code .pickleball/agent-log}.
 * {@code AgentCoordination.appendLog} and Workbench MCP retrieval both call this
 * so a CLI {@code query} and the matching MCP tool append the same line.
 */
public final class AgentLog {
    public static final String FILE_NAME = "agent-log";
    /** Collapsed purpose and other fields stop here. Tabs and newlines are spaces. */
    public static final int PURPOSE_LIMIT = 240;

    private AgentLog() {
    }

    public static Path file(Path project) {
        return PickleballLocalLayout.root(project).resolve(FILE_NAME);
    }

    /**
     * One tab-separated line: instant, agent, run, event, purpose, record path or {@code -}.
     * A null record is {@code -}. Blank fields are {@code -}.
     */
    public static void append(
            Path project,
            Instant when,
            String agentId,
            String runId,
            String event,
            String purpose,
            String record
    ) throws IOException {
        Instant clock = when == null ? Instant.now() : when;
        String line = String.join("\t",
                clock.toString(),
                oneLine(agentId),
                oneLine(runId),
                oneLine(event),
                oneLine(purpose),
                record == null ? "-" : oneLine(record)
        );
        Path file = file(project);
        Files.createDirectories(file.getParent());
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(
                file,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND
        )) {
            channel.write(ByteBuffer.wrap(bytes));
        }
    }

    public static String oneLine(String value) {
        if (value == null || value.isBlank()) return "-";
        String collapsed = value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').strip();
        if (collapsed.isEmpty()) return "-";
        if (collapsed.length() <= PURPOSE_LIMIT) return collapsed;
        return collapsed.substring(0, PURPOSE_LIMIT);
    }

    private static final Pattern AGENT_ID = Pattern.compile("\"agentId\"\\s*:\\s*\"([^\"]*)\"");

    /**
     * Agent id stored on {@code .pickleball/runs/<runId>/record.json}, or null
     * when the run or the field is missing. Query lines use this when the
     * caller did not pass {@code --agent}.
     */
    public static String recordedAgent(Path project, String runId) {
        if (project == null || runId == null || runId.isBlank()) return null;
        if (runId.indexOf('/') >= 0 || runId.indexOf('\\') >= 0 || runId.contains("..")) return null;
        Path record = PickleballLocalLayout.root(project).resolve("runs").resolve(runId).resolve("record.json");
        if (!Files.isRegularFile(record)) return null;
        try {
            Matcher matcher = AGENT_ID.matcher(Files.readString(record, StandardCharsets.UTF_8));
            if (!matcher.find()) return null;
            String id = matcher.group(1).trim();
            return id.isEmpty() ? null : id;
        } catch (IOException ignored) {
            return null;
        }
    }
}
