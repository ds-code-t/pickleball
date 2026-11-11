package tools.dscode.engine.model;

/** Outcome of one step. */
public record StepResult(
        Status status,
        String keyword,
        String text,
        String message,
        long durationMillis,
        String returned
) {
    public StepResult {
        status = status == null ? Status.UNDEFINED : status;
        keyword = keyword == null ? "" : keyword;
        text = text == null ? "" : text;
        message = message == null ? "" : message;
        returned = returned == null ? "" : returned;
        if (durationMillis < 0) durationMillis = 0;
    }

    public static StepResult of(Status status, String text, String message) {
        return new StepResult(status, "", text, message, 0, "");
    }
}
