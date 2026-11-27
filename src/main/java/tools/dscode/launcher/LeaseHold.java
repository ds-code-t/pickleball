package tools.dscode.launcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Pause lease requested by {@code start --hold}. The marker
 * {@code .pickleball/workbench/pause-hold} is the same file the worker already
 * treats as a held lease. {@code hold.json} stores when it runs out.
 */
final class LeaseHold {
    private static final ObjectMapper JSON = new ObjectMapper();

    private LeaseHold() {
    }

    /** Spine {@code lease take}. A browser target also arms the worker pause hold. */
    static void takeControl(Path project, String session, String target, String holder) throws java.io.IOException {
        tools.dscode.common.agentcontrol.UiLease.take(project, session, target, holder);
        String use = target == null ? "" : target.trim();
        if ("browser".equals(use) || "both".equals(use)) arm(project, "hold");
    }

    /** Spine {@code lease release}. */
    static void releaseControl(Path project, String session, String target) throws java.io.IOException {
        tools.dscode.common.agentcontrol.UiLease.release(project, session, target);
    }

    /**
     * The holder of {@code ui} or {@code both} may use UI controls. The holder of
     * {@code browser} or {@code both} may use browser controls. No file means the control is open.
     */
    static void require(Path project, String session, String area, String holder) {
        if (allows(project, session, area, holder)) return;
        String id = session == null ? "" : session;
        throw new IllegalArgumentException(
                "error: the " + area + " lease is held by another agent\n"
                        + "fix: lease release --target=" + area + " --session=" + id);
    }

    static boolean allows(Path project, String session, String area, String holder) {
        if (project == null || session == null || session.isBlank()) return true;
        String name = holder == null || holder.isBlank()
                ? tools.dscode.common.agentcontrol.SpineSession.DEFAULT_AGENT
                : holder.trim();
        String use = area == null ? "" : area.trim();
        if ("ui".equals(use)) {
            return open(leaseFile(project, session, "ui"), name) && open(leaseFile(project, session, "both"), name);
        }
        if ("browser".equals(use)) {
            return open(leaseFile(project, session, "browser"), name)
                    && open(leaseFile(project, session, "both"), name);
        }
        return true;
    }

    private static boolean open(Path file, String holder) {
        if (!java.nio.file.Files.isRegularFile(file)) return true;
        try {
            String text = java.nio.file.Files.readString(file);
            int at = text.indexOf("\"holder\"");
            if (at < 0) return false;
            int quote = text.indexOf('"', text.indexOf(':', at) + 1);
            int end = text.indexOf('"', quote + 1);
            if (quote < 0 || end < 0) return false;
            return holder.equals(text.substring(quote + 1, end));
        } catch (java.io.IOException failure) {
            return false;
        }
    }

    private static Path leaseFile(Path project, String session, String target) {
        return project.resolve(".pickleball").resolve("workbench-ui").resolve("leases")
                .resolve(session).resolve(target + ".json");
    }

    static void arm(Path project, String raw) throws java.io.IOException {
        if (project == null || raw == null || raw.isBlank()) return;
        long seconds = seconds(raw.trim());
        long now = System.currentTimeMillis();
        long expires = seconds < 0 ? Long.MAX_VALUE : now + seconds * 1000L;
        Path hold = project.resolve(".pickleball").resolve("workbench").resolve("pause-hold");
        Files.createDirectories(hold.getParent());
        Files.writeString(hold, raw.trim() + "\n");
        Path file = project.resolve(".pickleball").resolve("live-browser").resolve("hold.json");
        Files.createDirectories(file.getParent());
        ObjectNode node = JSON.createObjectNode();
        node.put("hold", raw.trim());
        node.put("leaseSeconds", seconds < 0 ? 3600 : seconds);
        node.put("forever", seconds < 0);
        node.put("armedAt", now);
        node.put("expires", expires);
        Files.writeString(file, JSON.writeValueAsString(node) + "\n");
    }

    /** Seconds still on the hold, {@code forever}, or {@code none}. */
    static String left(Path project) {
        return tools.dscode.control.protocol.LeaseClock.left(project);
    }

    /** Whole seconds. Negative means forever. */
    static long seconds(String raw) {
        String text = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) return 3600;
        if ("forever".equals(text) || "hold".equals(text)) return -1;
        long mul = 1;
        String number = text;
        if (text.endsWith("ms")) return Math.max(1, Long.parseLong(text.substring(0, text.length() - 2).trim()) / 1000L);
        if (text.endsWith("s")) {
            number = text.substring(0, text.length() - 1);
        } else if (text.endsWith("m")) {
            number = text.substring(0, text.length() - 1);
            mul = 60;
        } else if (text.endsWith("h")) {
            number = text.substring(0, text.length() - 1);
            mul = 3600;
        } else if (text.endsWith("d")) {
            number = text.substring(0, text.length() - 1);
            mul = 86400;
        }
        return Math.max(1, Long.parseLong(number.trim()) * mul);
    }
}
