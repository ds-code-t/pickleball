package tools.dscode.browser;

/**
 * A backend answered "unknown command" or "unsupported operation", or pickleball has no code for that capability on
 * that backend. {@link BrowserProtocols} catches it, marks the capability unavailable on that backend for the session,
 * and falls through to the next backend.
 */
public final class UnsupportedProtocolOperation extends RuntimeException {
    private final String capability;
    private final String backend;
    private final String detail;

    public UnsupportedProtocolOperation(String capability, String backend, String detail) {
        super("error: unsupported: " + capability + " on " + backend + (detail == null || detail.isBlank() ? "" : " (" + detail + ")"));
        this.capability = capability;
        this.backend = backend;
        this.detail = detail == null ? "" : detail;
    }

    /** Why the backend could not serve it, or empty. */
    public String detail() {
        return detail;
    }

    public String capability() {
        return capability;
    }

    public String backend() {
        return backend;
    }

    /** True for the error shapes CDP and BiDi use for a method the browser does not implement. */
    public static boolean unknownCommand(String message) {
        if (message == null) return false;
        String text = message.toLowerCase(java.util.Locale.ROOT);
        return text.contains("unknown command") || text.contains("unsupported operation")
                || text.contains("wasn't found") || text.contains("was not found") || text.contains("not supported")
                || text.contains("method not found") || text.contains("'-32601'") || text.contains("-32601");
    }
}
