package tools.dscode.engine.model;

/**
 * Neutral doc string. Cucumber {@code DocString} exists only at the adapter edge.
 */
public record DocText(String content, String mediaType) {
    public DocText {
        content = content == null ? "" : content;
        mediaType = mediaType == null || mediaType.isBlank() ? "" : mediaType;
    }

    public static DocText of(String content) {
        return new DocText(content, "");
    }
}
