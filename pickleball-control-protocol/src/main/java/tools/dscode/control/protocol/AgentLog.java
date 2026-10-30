package tools.dscode.control.protocol;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

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
}
