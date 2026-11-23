package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.recording.InteractionLog;
import tools.dscode.control.protocol.PickleballLocalLayout;
import tools.dscode.control.protocol.PickleballLocalStore;
import tools.dscode.control.protocol.PickleballVersion;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Consumer smoke that does not open Chrome. The recorded feature is the example
 * shape. Replaying it in a browser is a separate human check.
 */
public class ConsumerReadinessSmoke {
    @TempDir
    Path temp;

    @Test
    void exportGuidanceShipsTheRecordingExampleAndThePageEngine() throws Exception {
        Path pickleball = temp.resolve(".pickleball");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int status = PickleballLocalStore.exportGuidance(
                pickleball,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)
        );
        assertEquals(0, status, out.toString(StandardCharsets.UTF_8));
        String version = PickleballVersion.running(PickleballLocalStore.class);
        Path docs = pickleball.resolve("v").resolve(version).resolve("docs");
        String recording = Files.readString(docs.resolve("collaborative-recording.md"));
        assertTrue(recording.contains("recorded-collaboration.feature"), recording);
        assertTrue(recording.contains("record --editor"));
        String notes = Files.readString(docs.resolve("release-notes.md"));
        assertTrue(notes.contains("Chrome DevTools"), notes);
        assertTrue(notes.contains("--engine=selenium"), notes);
        String detail = Files.readString(docs.resolve("workbench-agent-detail.md"));
        assertTrue(detail.contains("--browser=selenium"), detail);
        String selfTest = Files.readString(docs.resolve("collaborative-self-test.md"));
        assertTrue(selfTest.contains("selftest ui --component=feedback"), selfTest);
        assertTrue(selfTest.contains("selftest record"), selfTest);
        assertTrue(selfTest.contains("dashboard query --project="), selfTest);
        assertTrue(selfTest.contains("in the \"billing\" IFrame"), selfTest);
        assertTrue(selfTest.contains("## Human participation"), selfTest);
        assertTrue(selfTest.contains("cannot run as unattended regression"), selfTest);
        String guide = Files.readString(pickleball.resolve("AGENT-GUIDE.md"));
        assertTrue(guide.contains("docs/collaborative-self-test.md"), guide);
        assertTrue(guide.contains("start --url-key"), guide);
        assertTrue(guide.contains("chat-turn"), guide);
        assertTrue(guide.contains("act --query"), guide);
        assertTrue(guide.contains("ui-verify"), guide);
        assertTrue(guide.contains("doctor"), guide);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("NEXT: follow AGENT-GUIDE"));
        assertEquals("3.0.0", version);
        assertTrue(PickleballLocalLayout.readCurrent(pickleball).orElseThrow().usable());
    }

    @Test
    void nonBrowserVerbsAndTheRecordedExample() throws Exception {
        var feature = ConsumerReadinessSmoke.class.getResource("/features/recorded-collaboration.feature");
        assertTrue(feature != null);
        String text = Files.readString(Path.of(feature.toURI()));
        assertTrue(text.contains("record --editor"));
        assertTrue(text.contains("--popout"));
        assertTrue(text.contains("| Ava | Green |"));
        assertTrue(text.contains("| Mia | Blue |"));

        Capture feedback = agent("selftest", temp.toString(), "ui", "--component=feedback");
        assertEquals(0, feedback.code, feedback.out + feedback.err);
        assertTrue(feedback.out.contains("sending"), feedback.out);
        assertTrue(feedback.out.contains("delivered"), feedback.out);
        assertTrue(feedback.out.contains("read"), feedback.out);

        Capture started = agent("record", temp.toString(), "--start", "--session=consumer");
        assertEquals(0, started.code, started.out + started.err);
        Map<String, String> click = new LinkedHashMap<>();
        click.put("type", "click");
        click.put("kind", "click");
        click.put("name", "Submit");
        click.put("role", "button");
        click.put("source", "human");
        click.put("frame", "top");
        InteractionLog.accept(temp, click);
        Capture script = agent("record", temp.toString(), "--script");
        assertEquals(0, script.code, script.out + script.err);
        assertTrue(script.out.contains("Submit"), script.out);
        Capture saved = agent("record", temp.toString(), "--save", "--name=consumer");
        assertEquals(0, saved.code, saved.out + saved.err);
        assertTrue(Files.isRegularFile(temp.resolve(".pickleball/recordings/routines/consumer.json")));

        Capture help = agent("help", "start");
        assertEquals(0, help.code, help.out + help.err);
        assertTrue(help.out.contains("Chrome DevTools"), help.out);
        assertTrue(help.out.contains("--engine=selenium"), help.out);
    }

    private static Capture agent(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
