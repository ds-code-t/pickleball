package tools.dscode.launcher;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartHealthMessageTest {
    @Test
    void unhealthyMessageNamesTheStageAndSaysChromeWasStopped() {
        String text = ConsumerReadyCommands.unhealthyMessage(
                "launching",
                "browser=chrome\nerror: DevToolsActivePort was not written\n",
                Path.of("live-browser.log")
        );
        assertTrue(text.contains("error: live browser host did not become healthy within 50s"), text);
        assertTrue(text.contains("(stage=launching)"), text);
        assertTrue(text.contains("error: DevToolsActivePort was not written"), text);
        assertTrue(text.contains("fix: the partial Chrome process was stopped"), text);
        assertTrue(text.contains("live-browser.log"), text);
        assertFalse(text.contains("stage=healthy"), text);
    }
}
