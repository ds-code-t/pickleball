package tools.dscode.common.agentsurface;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import tools.dscode.common.agentcontrol.PopupSurfaceBridge;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Append-only surface ledger. Sequence numbers stay monotonic across processes. */
public final class PopupLedger {
    private static final ObjectMapper JSON = new ObjectMapper();

    private PopupLedger() {
    }

    public record Row(long seq, String op, String pane, String handle, String where, String html, String codes, String sentence, String at) {
    }

    public static Path file(Path project, String runId) {
        return PopupLog.popupDir(project, runId).resolve("surfaces.jsonl");
    }

    public static Row append(Path project, String runId, String op, String pane, String handle, String where, String html, String codes, String sentence) throws IOException {
        Path target = file(project, runId);
        Files.createDirectories(target.getParent());
        Path lockFile = target.resolveSibling("surfaces.lock");
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            long seq = lastSeq(target) + 1;
            Row row = new Row(seq, text(op), text(pane), text(handle), text(where), html == null ? "" : html, text(codes), text(sentence), Instant.now().toString());
            PopupSurfaceBridge.appendCapped(target, JSON.writeValueAsString(row));
            PopupLog.log(project, runId, "ledger", runId, "seq=" + seq + " op=" + op);
            return row;
        }
    }

    public static List<Row> read(Path project, String runId) throws IOException {
        List<Row> rows = new ArrayList<>();
        for (String line : PopupSurfaceBridge.readLines(file(project, runId))) {
            JsonNode node = JSON.readTree(line);
            rows.add(new Row(
                    node.path("seq").asLong(),
                    node.path("op").asText(""),
                    node.path("pane").asText(""),
                    node.path("handle").asText(""),
                    node.path("where").asText(""),
                    node.path("html").asText(""),
                    node.path("codes").asText(""),
                    node.path("sentence").asText(""),
                    node.path("at").asText("")
            ));
        }
        rows.sort((left, right) -> Long.compare(left.seq(), right.seq()));
        return rows;
    }

    public static List<Row> after(Path project, String runId, long seq) throws IOException {
        List<Row> newer = new ArrayList<>();
        for (Row row : read(project, runId)) {
            if (row.seq() > seq) newer.add(row);
        }
        return newer;
    }

    public static void main(String[] args) throws Exception {
        if (args.length >= 4 && "--append".equals(args[0])) {
            append(Path.of(args[1]), args[2], args[3], "instructions", "", "", "<p>x</p>", "", "");
        }
    }

    private static long lastSeq(Path file) throws IOException {
        long max = 0;
        for (String line : PopupSurfaceBridge.readLines(file)) {
            if (line.isBlank()) continue;
            try {
                max = Math.max(max, JSON.readTree(line).path("seq").asLong());
            } catch (IOException ignored) {
                // A torn line is skipped; the lock keeps writers from overlapping.
            }
        }
        return max;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    public static Map<String, Object> toMap(Row row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("seq", row.seq());
        map.put("op", row.op());
        map.put("pane", row.pane());
        map.put("handle", row.handle());
        map.put("where", row.where());
        map.put("html", row.html());
        map.put("codes", row.codes());
        map.put("sentence", row.sentence());
        map.put("at", row.at());
        return map;
    }
}
