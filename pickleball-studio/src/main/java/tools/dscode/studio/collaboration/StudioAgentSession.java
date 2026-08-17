package tools.dscode.studio.collaboration;

public record StudioAgentSession(
        String id,
        String name,
        String startedAt,
        String lastActivityAt,
        boolean active
) {
}
