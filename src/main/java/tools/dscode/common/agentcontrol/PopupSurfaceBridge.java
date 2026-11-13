package tools.dscode.common.agentcontrol;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Public door into package-private surface helpers. G1 keeps those signatures;
 * this class is the only G4 file in the package.
 */
public final class PopupSurfaceBridge {
    private PopupSurfaceBridge() {
    }

    public static String neutralize(String html) {
        return PageSurfaces.neutralize(html);
    }

    public static String label(String html, String kind) {
        String use = kind == null ? "html" : kind;
        return PageSurfaces.labelElements(html, use, sentence(use));
    }

    public static String sentence(String kind) {
        if ("overlay".equals(kind)) return PageSurfaces.OVERLAY_SENTENCE;
        if ("style".equals(kind)) return PageSurfaces.STYLE_SENTENCE;
        return PageSurfaces.HTML_SENTENCE;
    }

    public static void recordInput(Path project, String runId, Map<String, String> event) throws IOException {
        PageSurfaces.recordInput(project, runId, event);
    }

    public static Path agentRoot(Path project, String runId) {
        return AgentStore.root(project, runId);
    }

    public static void appendCapped(Path file, String line) throws IOException {
        AgentStore.appendCapped(file, line);
    }

    public static boolean hasBrowserPage(Path project, String runId) {
        if (!PageSurfaces.managed().isEmpty()) return true;
        return !WindowRegistry.list(project, runId).isEmpty();
    }

    public static void sendChat(Path project, String runId, String channel, String source, String text) throws IOException {
        sendChat(project, runId, channel, source, "", text);
    }

    public static Map<String, Object> sendChat(Path project, String runId, String channel, String source, String agent, String text) throws IOException {
        ChatLog.Message message = ChatLog.send(project, runId, channel, source, agent, text);
        return ChatLog.toMap(message);
    }

    public static List<Map<String, Object>> pollInput(Path project, String runId) throws IOException {
        return PageSurfaces.pollInput(project, runId);
    }

    public static List<String> readLines(Path file) throws IOException {
        return AgentStore.readGroup(file);
    }
}
