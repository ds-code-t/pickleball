package tools.dscode.engine.model;

/** Outcome of one step. */
public record StepResult(
        Status status,
        String keyword,
        String text,
        String message,
        long durationMillis,
        String returned,
        String frame
) {
    public StepResult {
        status = status == null ? Status.UNDEFINED : status;
        keyword = keyword == null ? "" : keyword;
        text = text == null ? "" : text;
        message = message == null ? "" : message;
        returned = returned == null ? "" : returned;
        frame = frame == null ? "" : frame;
        if (durationMillis < 0) durationMillis = 0;
    }

    /** Steps recorded before the frame field existed. */
    public StepResult(
            Status status,
            String keyword,
            String text,
            String message,
            long durationMillis,
            String returned
    ) {
        this(status, keyword, text, message, durationMillis, returned, "");
    }

    public static StepResult of(Status status, String text, String message) {
        return new StepResult(status, "", text, message, 0, "");
    }
}
