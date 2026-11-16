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
import java.time.Duration;
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
            boolean exited = PopupHost.closeAndWait(project, "live", Duration.ofSeconds(10));
            assertTrue(exited, () -> closeDiagnostics(project));
            long browserPid = opened.get("browserPid") instanceof Number number ? number.longValue() : -1;
            assertFalse(browserPid > 0 && ProcessHandle.of(browserPid).isPresent(), "browser pid " + browserPid);
            assertFalse(Files.exists(PopupLog.popupDir(project, "live").resolve("profile")));
        } finally {
            restore("pkb_agent_popup_browser", browser);
            restore("pkb_agent_popup_headless", headless);
            restore("pkb_agent_popup_on_top", onTop);
            try {
                PopupHost.closeAndWait(project, "live", Duration.ofSeconds(2));
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

    /** What still holds the popup after close: status pids, profile, surviving processes, and the log tail. */
    private static String closeDiagnostics(Path project) {
        StringBuilder out = new StringBuilder("popup did not exit.");
        try {
            Map<String, Object> status = tools.dscode.common.agentsurface.PopupStatus.read(project, "live");
            out.append(" status=").append(status);
            for (String key : java.util.List.of("pid", "browserPid")) {
                if (status.get(key) instanceof Number number) {
                    var handle = ProcessHandle.of(number.longValue());
                    out.append(" ").append(key).append(" alive=").append(handle.map(ProcessHandle::isAlive).orElse(false))
                            .append(" cmd=").append(handle.flatMap(h -> h.info().commandLine()).orElse("?"));
                }
            }
            Path dir = PopupLog.popupDir(project, "live");
            Path profile = dir.resolve("profile");
            out.append(" profileExists=").append(Files.exists(profile));
            if (Files.isDirectory(profile)) {
                try (var walk = Files.walk(profile, 2)) {
                    out.append(" profileEntries=").append(walk.limit(20).map(path -> profile.relativize(path).toString()).toList());
                }
            }
            String marker = profile.toString();
            out.append(" holders=").append(ProcessHandle.allProcesses()
                    .filter(handle -> handle.info().commandLine().map(line -> line.contains(marker)).orElse(false))
                    .map(handle -> handle.pid() + ":" + handle.info().command().orElse("?"))
                    .toList());
            Path log = dir.resolve("popup.log");
            if (Files.isRegularFile(log)) {
                java.util.List<String> lines = Files.readAllLines(log);
                out.append(" log=").append(lines.subList(Math.max(0, lines.size() - 15), lines.size()));
            }
        } catch (Exception failure) {
            out.append(" diagnostics failed: ").append(failure);
        }
        return out.toString();
    }

    private static String sha(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
