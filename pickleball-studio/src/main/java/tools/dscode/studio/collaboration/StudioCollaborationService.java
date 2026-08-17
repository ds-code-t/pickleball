package tools.dscode.studio.collaboration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Bounded, Studio-session-scoped collaboration state shared by Desktop and MCP. */
public final class StudioCollaborationService {
    private static final int MAX_ACTIVITIES = 1_000;
    private static final int MAX_AGENT_SESSIONS = 50;
    private static final int DEFAULT_ACTIVITY_LIMIT = 100;
    private static final int MAX_ACTIVITY_LIMIT = 500;

    private final List<StudioActivity> activities = new ArrayList<>();
    private final List<StudioAgentSession> agentSessions = new ArrayList<>();
    private final List<StudioEditorState> editorStates = new ArrayList<>();
    private long nextSequence = 1L;

    public synchronized StudioActivity record(
            StudioClientKind clientKind,
            String clientSessionId,
            String operation,
            String target,
            String detail
    ) {
        return appendActivity(
                clientKind,
                normalize(clientSessionId),
                required(operation, "operation"),
                normalize(target),
                normalize(detail)
        );
    }

    public synchronized StudioAgentSession startAgentSession(String name) {
        String now = Instant.now().toString();
        StudioAgentSession session = new StudioAgentSession(
                UUID.randomUUID().toString(),
                normalize(name).isBlank() ? "Agent" : name.trim(),
                now,
                now,
                true
        );
        agentSessions.add(session);
        trimAgentSessions();
        appendActivity(
                StudioClientKind.MCP,
                session.id(),
                "agent.session.start",
                session.name(),
                "Agent session started"
        );
        return session;
    }

    public synchronized StudioAgentSession endAgentSession(String sessionId) {
        int index = agentIndex(sessionId);
        StudioAgentSession current = agentSessions.get(index);
        StudioAgentSession ended = new StudioAgentSession(
                current.id(),
                current.name(),
                current.startedAt(),
                Instant.now().toString(),
                false
        );
        agentSessions.set(index, ended);
        appendActivity(
                StudioClientKind.MCP,
                current.id(),
                "agent.session.end",
                current.name(),
                "Agent session ended"
        );
        return ended;
    }

    public synchronized StudioActivity note(String sessionId, String message) {
        touchAgent(sessionId);
        return appendActivity(
                StudioClientKind.MCP,
                sessionId,
                "agent.note",
                "",
                required(message, "message")
        );
    }

    public synchronized void requireActiveAgent(String sessionId) {
        StudioAgentSession session = agentSessions.get(agentIndex(sessionId));
        if (!session.active()) {
            throw new IllegalStateException("Agent session is not active: " + sessionId);
        }
    }

    public synchronized StudioActivityPage activity(Long afterSequence, Integer limit) {
        long after = afterSequence == null ? 0L : Math.max(0L, afterSequence);
        int boundedLimit = limit == null
                ? DEFAULT_ACTIVITY_LIMIT
                : Math.max(1, Math.min(MAX_ACTIVITY_LIMIT, limit));
        long oldest = activities.isEmpty() ? nextSequence : activities.getFirst().sequence();
        long latest = activities.isEmpty() ? Math.max(0L, nextSequence - 1L) : activities.getLast().sequence();
        boolean gap = after > 0 && after < oldest - 1;
        List<StudioActivity> page = activities.stream()
                .filter(activity -> activity.sequence() > after)
                .limit(boundedLimit)
                .toList();
        return new StudioActivityPage(oldest, latest, gap, page);
    }

    public synchronized List<StudioAgentSession> agentSessions(boolean includeInactive) {
        return agentSessions.stream()
                .filter(session -> includeInactive || session.active())
                .sorted(Comparator.comparing(StudioAgentSession::startedAt).reversed())
                .toList();
    }

    public synchronized List<StudioEditorState> editorStates() {
        return editorStates.stream()
                .sorted(Comparator.comparing(StudioEditorState::path)
                        .thenComparing(StudioEditorState::clientSessionId))
                .toList();
    }

    public synchronized StudioEditorState editorState(
            String clientSessionId,
            String path,
            boolean dirty,
            String baseSha256
    ) {
        String session = required(clientSessionId, "clientSessionId");
        String file = required(path, "path");
        int index = editorIndex(session, file);
        StudioEditorState previous = index < 0 ? null : editorStates.get(index);
        StudioEditorState current = new StudioEditorState(
                session,
                file,
                dirty,
                normalize(baseSha256),
                Instant.now().toString()
        );
        if (index < 0) {
            editorStates.add(current);
        } else {
            editorStates.set(index, current);
        }
        if (previous == null || previous.dirty() != dirty) {
            appendActivity(
                    StudioClientKind.DESKTOP,
                    session,
                    dirty ? "editor.dirty" : "editor.clean",
                    file,
                    dirty ? "Unsaved editor changes" : "Editor matches saved content"
            );
        }
        return current;
    }

    public synchronized void closeClient(String clientSessionId) {
        String session = normalize(clientSessionId);
        if (session.isBlank()) {
            return;
        }
        editorStates.removeIf(editor -> editor.clientSessionId().equals(session));
        appendActivity(
                StudioClientKind.DESKTOP,
                session,
                "desktop.session.close",
                "",
                "Desktop session closed"
        );
    }

    private StudioActivity appendActivity(
            StudioClientKind clientKind,
            String clientSessionId,
            String operation,
            String target,
            String detail
    ) {
        StudioActivity activity = new StudioActivity(
                nextSequence++,
                Instant.now().toString(),
                clientKind == null ? StudioClientKind.MCP : clientKind,
                clientSessionId,
                operation,
                target,
                detail
        );
        activities.add(activity);
        while (activities.size() > MAX_ACTIVITIES) {
            activities.removeFirst();
        }
        if (!clientSessionId.isBlank()) {
            touchAgentIfPresent(clientSessionId);
        }
        return activity;
    }

    private void touchAgent(String sessionId) {
        int index = agentIndex(sessionId);
        StudioAgentSession current = agentSessions.get(index);
        if (!current.active()) {
            throw new IllegalStateException("Agent session is not active: " + sessionId);
        }
        agentSessions.set(index, touched(current));
    }

    private void touchAgentIfPresent(String sessionId) {
        for (int index = 0; index < agentSessions.size(); index++) {
            StudioAgentSession current = agentSessions.get(index);
            if (current.id().equals(sessionId) && current.active()) {
                agentSessions.set(index, touched(current));
                return;
            }
        }
    }

    private static StudioAgentSession touched(StudioAgentSession session) {
        return new StudioAgentSession(
                session.id(),
                session.name(),
                session.startedAt(),
                Instant.now().toString(),
                true
        );
    }

    private int agentIndex(String sessionId) {
        String id = required(sessionId, "sessionId");
        for (int index = 0; index < agentSessions.size(); index++) {
            if (agentSessions.get(index).id().equals(id)) {
                return index;
            }
        }
        throw new IllegalArgumentException("Unknown agent session: " + id);
    }

    private int editorIndex(String clientSessionId, String path) {
        for (int index = 0; index < editorStates.size(); index++) {
            StudioEditorState editor = editorStates.get(index);
            if (editor.clientSessionId().equals(clientSessionId) && editor.path().equals(path)) {
                return index;
            }
        }
        return -1;
    }

    private void trimAgentSessions() {
        while (agentSessions.size() > MAX_AGENT_SESSIONS) {
            int inactive = -1;
            for (int index = 0; index < agentSessions.size(); index++) {
                if (!agentSessions.get(index).active()) {
                    inactive = index;
                    break;
                }
            }
            agentSessions.remove(inactive >= 0 ? inactive : 0);
        }
    }

    private static String required(String value, String name) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
