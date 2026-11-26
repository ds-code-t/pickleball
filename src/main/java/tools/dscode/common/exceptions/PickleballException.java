package tools.dscode.common.exceptions;

import java.util.Locale;

/**
 * Runtime failure with a severity and a scope. Scope is resolved when the run
 * is recorded unless the constructor chose one.
 */
public class PickleballException extends RuntimeException implements PickleBallExceptionInterface {
    public static final String INFO = "info";
    public static final String WARN = "warn";
    public static final String ERROR = "error";
    public static final String FATAL = "fatal";

    private final String severity;
    private final String scope;
    private final boolean scopeChosen;

    public PickleballException() {
        this(null, null, true, true);
    }

    public PickleballException(String message) {
        this(message, null, true, true);
    }

    public PickleballException(String message, Throwable cause) {
        this(message, cause, true, true);
    }

    public PickleballException(Throwable cause) {
        super(cause);
        this.severity = ERROR;
        this.scope = "universal";
        this.scopeChosen = false;
    }

    protected PickleballException(
            String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace
    ) {
        super(message, cause, enableSuppression, writableStackTrace);
        this.severity = ERROR;
        this.scope = "universal";
        this.scopeChosen = false;
    }

    public PickleballException(String message, String severity, String scope) {
        super(message);
        this.severity = severity(severity);
        this.scope = scope(scope);
        this.scopeChosen = true;
    }

    public String severity() {
        return severity;
    }

    public String scope() {
        return scope;
    }

    public boolean scopeChosen() {
        return scopeChosen;
    }

    private static String severity(String value) {
        String token = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (INFO.equals(token) || WARN.equals(token) || ERROR.equals(token) || FATAL.equals(token)) return token;
        throw new IllegalArgumentException(
                "error: exception severity must be info, warn, error, or fatal\n"
                        + "fix: pass one of those four severities");
    }

    private static String scope(String value) {
        String token = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if ("universal".equals(token) || "environmental".equals(token) || "entity".equals(token)) return token;
        throw new IllegalArgumentException(
                "error: exception scope must be universal, environmental, or entity\n"
                        + "fix: pass one of those three scopes");
    }
}
