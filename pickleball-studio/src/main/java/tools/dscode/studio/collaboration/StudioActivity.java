package tools.dscode.studio.collaboration;

public record StudioActivity(
        long sequence,
        String timestamp,
        StudioClientKind clientKind,
        String clientSessionId,
        String operation,
        String target,
        String detail
) {
}
