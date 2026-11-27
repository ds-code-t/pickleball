package tools.dscode.common.agentcontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import tools.dscode.common.composite.CompositeLog;
import tools.dscode.control.protocol.VerbTiers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Session-level human lock ({@code start --human}, {@code session-mode --human=on|off}). In a locked session only
 * human-tier verbs run; a control-tier verb fails with error and fix lines. Queries default to the human exposure.
 * The session owner may pass {@code --human-override=<reason>} on one call; that call is logged. Leaving human mode
 * is explicit and is not a scenario failure. This is separate from the Workbench "Take control" lease.
 */
public final class HumanLock {
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Verbs that run in any mode: they change or read the mode itself. */
    private static final Set<String> ALWAYS = Set.of("session-mode", "help", "doctor");

    private HumanLock() {
    }

    static String key(String runId) {
        String run = runId == null || runId.isBlank() ? "session" : runId.trim();
        return run.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    static Path file(Path project, String runId) {
        Path root = project == null ? Path.of(".") : project;
        return root.resolve(".pickleball").resolve("human-lock").resolve(key(runId) + ".json");
    }

    /** Turns the lock on; {@code owner} is the agent id allowed to override one call (blank means the default owner). */
    public static void lock(Path project, String runId, String owner) throws IOException {
        Path file = file(project, runId);
        Files.createDirectories(file.getParent());
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("human", true);
        state.put("owner", owner == null ? "" : owner.trim());
        state.put("since", System.currentTimeMillis());
        Files.writeString(file, JSON.writeValueAsString(state));
        CompositeLog.info("human-lock", "on " + key(runId) + (owner == null || owner.isBlank() ? "" : " owner=" + owner.trim()));
    }

    public static boolean unlock(Path project, String runId) throws IOException {
        boolean was = Files.deleteIfExists(file(project, runId));
        CompositeLog.info("human-lock", "off " + key(runId));
        return was;
    }

    public static boolean locked(Path project, String runId) {
        return Files.isRegularFile(file(project, runId));
    }

    @SuppressWarnings("unchecked")
    static String owner(Path project, String runId) {
        try {
            Map<String, Object> state = JSON.readValue(Files.readString(file(project, runId)), Map.class);
            Object owner = state.get("owner");
            return owner == null ? "" : String.valueOf(owner);
        } catch (Exception unreadable) {
            return "";
        }
    }

    /**
     * Null when the call may run, otherwise the error and fix text. In a locked session it also sets
     * {@code exposure=human} on queries that did not choose one and marks the call {@code human=on}.
     */
    public static String check(Path project, String runId, String verb, Map<String, String> args, String agentId) {
        if (verb == null || !locked(project, runId) || ALWAYS.contains(verb)) return null;
        String override = args == null ? null : args.get("human-override");
        if (override != null && !override.isBlank()) {
            String owner = owner(project, runId);
            String caller = agentId == null ? "" : agentId.trim();
            if (!owner.equals(caller)) {
                return "error: --human-override is only for the session owner" + (owner.isBlank() ? "" : " (" + owner + ")") + "\n"
                        + "fix: run it as the owner, or leave human mode with session-mode --human=off";
            }
            CompositeLog.info("human-lock", "override " + key(runId) + " " + verb + " reason=" + override.trim());
            return null;
        }
        String tier = VerbTiers.tier(verb);
        if (VerbTiers.HUMAN.equals(tier)) {
            if ("act".equals(verb) && args != null) {
                String kind = args.get("kind") == null || args.get("kind").isBlank() ? "click" : args.get("kind").trim();
                if (!VerbTiers.HUMAN_ACT_KINDS.contains(kind)) {
                    return "error: act --kind=" + kind + " is not human input and this session is human-locked\n"
                            + "fix: use click, type, press, hover, or select; file attachment and OS-level actions are unavailable in human mode "
                            + "(leave it with session-mode --human=off)";
                }
            }
            if ("input".equals(verb) && args != null && !inputIsHuman(project, args.get("actions"))) {
                return "error: input with an element-anchored or unreadable action chain is not human input and this session is human-locked\n"
                        + "fix: use viewport or pointer coordinates and key actions only, or leave human mode with session-mode --human=off";
            }
            if (args != null) {
                args.put("human", "on");
                if (("snap".equals(verb) || "find".equals(verb)) && (args.get("exposure") == null || args.get("exposure").isBlank())) {
                    args.put("exposure", "human");
                }
            }
            return null;
        }
        String extra = "upload".equals(verb) || "attach".equals(verb)
                ? " File attachment and OS-level actions are unavailable in human mode." : "";
        return "error: " + verb + " is a control-tier verb and this session is human-locked." + extra + "\n"
                + "fix: use human-tier verbs (snap, find, act, wait, capture, chat-read, doctor), or leave human mode with session-mode --human=off";
    }

    /** A W3C action chain is human input when it is only key, pointer, wheel and pause actions at coordinates. */
    public static boolean inputIsHuman(Path project, String actions) {
        if (actions == null || actions.isBlank() || "true".equals(actions)) return true;
        try {
            String raw = actions.startsWith("@") ? Files.readString((project == null ? Path.of(".") : project).resolve(actions.substring(1)).normalize()) : actions;
            com.fasterxml.jackson.databind.JsonNode chain = JSON.readTree(raw);
            if (chain.isObject() && chain.has("actions")) chain = chain.get("actions");
            for (com.fasterxml.jackson.databind.JsonNode source : chain) {
                if (!Set.of("key", "pointer", "wheel", "none").contains(source.path("type").asText())) return false;
                for (com.fasterxml.jackson.databind.JsonNode a : source.path("actions")) if (a.path("origin").isObject()) return false;
            }
            return true;
        } catch (Exception unreadable) {
            return false;
        }
    }

    /** Mouse and keyboard block for one spine session. A clean session ({@code inject=off}) is refused by the CLI. */
    public static void blockInput(Path project, String sessionId, boolean on) throws IOException {
        SpineSession.setInputBlocked(project, sessionId, on);
        PageSurfaces.applyInputBlock(project, sessionId, on, on ? "human" : "");
    }

    /** Lets the agent protocol input pass the page shield. The human has no caller for this. */
    public static void passAgentInput(Path project, String sessionId) {
        if (!inputBlocked(project, sessionId)) return;
        PageSurfaces.applyInputBlock(project, sessionId, true, "agent");
    }

    /** Puts the human shield back after one agent input. */
    public static void restoreInputBlock(Path project, String sessionId) {
        if (!inputBlocked(project, sessionId)) return;
        PageSurfaces.applyInputBlock(project, sessionId, true, "human");
    }

    public static boolean inputBlocked(Path project, String sessionId) {
        return SpineSession.inputBlocked(project, sessionId);
    }
}
