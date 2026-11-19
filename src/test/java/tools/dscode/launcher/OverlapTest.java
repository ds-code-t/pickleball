package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Phase 7: a second sized placement names the first when their boxes intersect. */
class OverlapTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void stop() {
        ConsumerReadyCommands.stopQuiet(temp);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void secondOverlayNamesTheFirst() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("overlap.html");
        Files.writeString(page, "<!DOCTYPE html><html><head><title>Overlap</title></head><body>Overlap</body></html>");
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless", "--run-id=overlap");
        assertEquals(0, started.code, started.err + started.out);
        assertTrue(started.out.contains("navigated=true"), started.out);

        Capture first = run(
                "overlay", "--text=First box", "--x=10", "--y=10", "--width=80", "--height=40",
                "--target=browser:auto", "--run-id=overlap");
        assertEquals(0, first.code, first.err + first.out);
        JsonNode firstBody = JSON.readTree(first.out);
        assertEquals(80, firstBody.path("width").asInt(), first.out);
        assertEquals(40, firstBody.path("height").asInt(), first.out);
        assertTrue(firstBody.has("overlaps"), first.out);
        assertEquals(0, firstBody.path("overlaps").size(), first.out);
        String firstId = firstBody.path("id").asText();
        assertTrue(!firstId.isBlank(), first.out);

        Capture second = run(
                "overlay", "--text=Second box", "--x=20", "--y=20", "--width=80", "--height=40",
                "--target=browser:auto", "--run-id=overlap");
        assertEquals(0, second.code, second.err + second.out);
        JsonNode secondBody = JSON.readTree(second.out);
        assertEquals(1, secondBody.path("overlaps").size(), second.out);
        assertEquals(firstId, secondBody.path("overlaps").get(0).asText(), second.out);

        Capture read = run(
                "eval", "--target=browser:auto", "--world=main", "--run-id=overlap",
                "--expr=(function(){var text=document.body?document.body.innerText:'';var layers=document.querySelectorAll('pkb-layer');for(var i=0;i<layers.length;i++){if(layers[i].shadowRoot)text+='\\n'+layers[i].shadowRoot.textContent;}return text;})()");
        assertEquals(0, read.code, read.err + read.out);
        String text = JSON.readTree(read.out).path("value").asText();
        assertTrue(text.contains("First box"), text);
        assertTrue(text.contains("Second box"), text);
    }

    private Capture run(String... args) {
        String[] command = new String[args.length + 1];
        command[0] = args[0];
        command[1] = temp.toString();
        System.arraycopy(args, 1, command, 2, args.length - 1);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                command,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
