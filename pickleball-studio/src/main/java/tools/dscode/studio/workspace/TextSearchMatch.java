package tools.dscode.studio.workspace;

public record TextSearchMatch(
        String path,
        int line,
        String text
) {
}
