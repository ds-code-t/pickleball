package tools.dscode.launcher;

import java.io.IOException;

/** Formats a Workbench CLI failure so a wrapped IOException stays visible. */
final class WorkbenchCliErrors {
    private WorkbenchCliErrors() {
    }

    static String explain(Throwable failure) {
        if (failure == null) return "Workbench command failed.";
        StringBuilder text = new StringBuilder();
        String message = failure.getMessage();
        text.append(message == null || message.isBlank() ? failure.getClass().getSimpleName() : message);
        Throwable cause = failure.getCause();
        int depth = 0;
        while (cause != null && cause != failure && depth++ < 6) {
            if (cause instanceof IOException || (cause.getMessage() != null && !cause.getMessage().isBlank())) {
                text.append(" caused by ").append(cause.getClass().getSimpleName());
                if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
                    text.append(": ").append(cause.getMessage());
                }
            }
            cause = cause.getCause();
        }
        return text.toString();
    }
}
