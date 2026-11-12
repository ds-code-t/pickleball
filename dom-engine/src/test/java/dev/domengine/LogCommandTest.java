package dev.domengine;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.json.Jsons;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogCommandTest {
    @TempDir
    Path dir;

    @Test
    void listTailAndShowDoNotRewriteTheFiles() throws Exception {
        Path engine = dir.resolve("composite-logs");
        Path board = dir.resolve(".pickleball");
        Files.createDirectories(engine);
        Files.createDirectories(board);
        Files.writeString(engine.resolve("composite-2026-11-12.log"), "engine-line\n");
        Path history = board.resolve("history.log");
        Files.writeString(history, "old\nhistory-line\n");
        long before = Files.size(history);

        CliResult list = DomEngineMain.dispatch(List.of(
                "log", "list", "--dir", engine.toString(), "--dir", board.toString()));
        assertEquals(0, list.exit(), list.stderr());
        JsonNode files = Jsons.MAPPER.readTree(list.stdout()).path("files");
        assertEquals(2, files.size(), list.stdout());

        CliResult tail = DomEngineMain.dispatch(List.of(
                "log", "tail", "--dir", board.toString(), "--file", "history.log", "--lines", "1"));
        assertEquals(0, tail.exit(), tail.stderr());
        assertEquals("history-line", Jsons.MAPPER.readTree(tail.stdout()).path("lines").get(0).asText());

        CliResult show = DomEngineMain.dispatch(List.of(
                "log", "show", "--dir", engine.toString(), "--file=composite-2026-11-12.log"));
        assertEquals(0, show.exit(), show.stderr());
        assertTrue(Jsons.MAPPER.readTree(show.stdout()).path("text").asText().contains("engine-line"));
        assertEquals(before, Files.size(history));
        assertEquals("old\nhistory-line\n", Files.readString(history));

        CliResult missing = DomEngineMain.dispatch(List.of(
                "log", "show", "--dir", engine.toString(), "--file", "nope.log"));
        assertEquals(1, missing.exit(), missing.stderr());
        assertEquals(2, DomEngineMain.dispatch(List.of("log")).exit());
    }
}
