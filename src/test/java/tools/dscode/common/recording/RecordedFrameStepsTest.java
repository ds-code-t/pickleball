package tools.dscode.common.recording;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentfast.PushChannel;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PKB-HCR-36/37: agent typing on the main document recorded `, in the "<top-frame CDP id>" IFrame,` steps,
 * and two typings became three steps (the act row and the page row for one typing were not merged).
 */
class RecordedFrameStepsTest {
    private static final String TOP_CDP_ID = "8F3A6C2D9B1E4F70A5C3D2E1B0A99887";

    @TempDir
    Path temp;

    @BeforeEach
    @AfterEach
    void resetPushChannel() throws Exception {
        var reset = PushChannel.class.getDeclaredMethod("resetForTests");
        reset.setAccessible(true);
        reset.invoke(null);
    }

    @Test
    void agentTypingOnTheTopDocumentIsNotAnIFrameAndIsOneStepPerTyping() {
        RecordingSession.start(temp, "rec-hcr36");
        typeLikeTheLiveSession("user", "Username", "ava");
        typeLikeTheLiveSession("search", "Search", "pickleball");

        StepGenerator.Script script = StepGenerator.generate(InteractionLog.read(temp), RecordingSession.read(temp));
        List<String> steps = script.steps();
        assertEquals(2, steps.size(), String.valueOf(steps));
        for (String step : steps) {
            assertFalse(step.contains("IFrame"), step);
            assertFalse(step.contains(TOP_CDP_ID), step);
            assertTrue(step.startsWith(", enter "), step);
        }
        assertTrue(steps.get(0).contains("\"Username\""), steps.get(0));
        assertTrue(steps.get(1).contains("\"Search\""), steps.get(1));
    }

    @Test
    void aRealIFrameStillNamesTheFrame() {
        RecordingSession.start(temp, "rec-hcr36-frame");
        Map<String, String> page = new LinkedHashMap<>();
        page.put("type", "click");
        page.put("kind", "click");
        page.put("name", "Pay");
        page.put("role", "button");
        page.put("frame", "billing");
        InteractionLog.accept(temp, page);
        List<String> steps = StepGenerator.generate(InteractionLog.read(temp), RecordingSession.read(temp)).steps();
        assertEquals(1, steps.size(), String.valueOf(steps));
        assertTrue(steps.get(0).startsWith(", in the \"billing\" IFrame, click"), steps.get(0));
    }

    /** One `act --kind=type`: the page runtime row (source=agent while acting) then the act reply note. */
    private void typeLikeTheLiveSession(String id, String label, String value) {
        Map<String, String> page = new LinkedHashMap<>();
        page.put("type", "input");
        page.put("kind", "input");
        page.put("source", "agent");
        page.put("id", id);
        page.put("name", label);
        page.put("role", "textbox");
        page.put("value", value);
        page.put("frame", "top");
        InteractionLog.accept(temp, page);

        Map<String, String> args = new LinkedHashMap<>();
        args.put("kind", "type");
        args.put("query", label);
        args.put("value", value);
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("ok", true);
        reply.put("frame", TOP_CDP_ID);
        reply.put("frameTop", true);
        RecordControl.noteAgent(temp, "rec-hcr36", "act", args, reply);
    }
}
