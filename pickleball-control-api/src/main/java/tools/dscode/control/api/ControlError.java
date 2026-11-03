package tools.dscode.control.api;

import tools.dscode.control.protocol.FailureRecord;

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
        FailureRecord record = FailureRecord.fromThrowable(error, null);
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        String message = record == null || record.message() == null || record.message().isBlank()
                ? (error.getMessage() == null ? "" : error.getMessage())
                : record.message();
        String head = record == null ? null : record.stackHead();
        String stack = head == null || head.isBlank() ? writer.toString() : head + System.lineSeparator() + writer;
        return new ControlError(error.getClass().getName(), message, stack);
    }
}