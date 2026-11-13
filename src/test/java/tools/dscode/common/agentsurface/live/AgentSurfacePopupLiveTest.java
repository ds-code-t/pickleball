package tools.dscode.common.agentsurface.live;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentcontrol.PopupSurfaceBridge;
import tools.dscode.common.agentsurface.PopupBrowserLocator;
import tools.dscode.common.agentsurface.PopupHost;
import tools.dscode.common.agentsurface.PopupLedger;
import tools.dscode.common.agentsurface.PopupLog;

import java.nio.file.Files;
import java.security.MessageDigest;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real Chromium, headless. Skips with the locator reason when no browser is installed. */
class AgentSurfacePopupLiveTest {
    @TempDir
    Path project;

    @Test
    void headlessPopupRendersChatAndInstructionsThenExits() throws Exception {
        PopupBrowserLocator.Hit hit = PopupBrowserLocator.locate();
        Assumptions.assumeTrue(hit.path().isPresent(), "no Chromium: " + hit.probes());
        String browser = System.getProperty("pkb_agent_popup_browser");
        String headless = System.getProperty("pkb_agent_popup_headless");
        String onTop = System.getProperty("pkb_agent_popup_on_top");
        try {
            System.clearProperty("pkb_agent_popup_browser");
            System.setProperty("pkb_agent_popup_headless", "true");
            System.setProperty("pkb_agent_popup_on_top", "false");
            Map<String, Object> opened = PopupHost.open(project, "live");
            Assumptions.assumeTrue("open".equals(String.valueOf(opened.get("state"))), String.valueOf(opened));
            String html = "<p>instructions</p>";
            String chat = "hello from the test";
            PopupLedger.append(project, "live", "append", "instructions", "note", "", html, "", "");
            PopupLedger.append(project, "live", "chat", "chat", "", "", chat, "", "");
            PopupSurfaceBridge.sendChat(project, "live", "run", "human", chat);
            String rendered = waitRendered(project);
            assertTrue(rendered.contains(sha(html)), rendered);
            assertTrue(rendered.contains(sha(chat)), rendered);
            PopupLedger.append(project, "live", "close", "", "", "", "", "", "");
            long browserPid = opened.get("browserPid") instanceof Number number ? number.longValue() : -1;
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline && browserPid > 0 && ProcessHandle.of(browserPid).isPresent()) {
                Thread.sleep(100);
            }
            assertFalse(browserPid > 0 && ProcessHandle.of(browserPid).isPresent(), "browser pid " + browserPid);
        } finally {
            restore("pkb_agent_popup_browser", browser);
            restore("pkb_agent_popup_headless", headless);
            restore("pkb_agent_popup_on_top", onTop);
            try {
                PopupLedger.append(project, "live", "close", "", "", "", "", "", "");
            } catch (Exception ignored) {
                // The host may already be gone.
            }
        }
    }

    private static String waitRendered(Path project) throws Exception {
        Path file = PopupLog.popupDir(project, "live").resolve("rendered.jsonl");
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(file)) {
                String text = Files.readString(file);
                if (text.contains("textSha256") && !text.contains("\"textSha256\":\"\"")) return text;
            }
            Thread.sleep(100);
        }
        return Files.isRegularFile(file) ? Files.readString(file) : "";
    }

    private static String sha(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
