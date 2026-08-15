package tools.dscode.control.api;

import java.io.PrintWriter;
import java.io.StringWriter;

/** Structured error information for retry-friendly control calls. */
public record ControlError(
        String type,
        String message,
        String stackTrace
) {
    public static ControlError from(Throwable error) {
        if (error == null) {
            return null;
        }
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        return new ControlError(
                error.getClass().getName(),
                error.getMessage() == null ? "" : error.getMessage(),
                writer.toString()
        );
    }
}
