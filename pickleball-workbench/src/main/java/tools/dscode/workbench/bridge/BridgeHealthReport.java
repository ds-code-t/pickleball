package tools.dscode.workbench.bridge;

/** Result of {@code GET /v1/health}. Unhealthy answers are immediate; they are not a 60 second page timeout. */
public record BridgeHealthReport(boolean healthy, String reason) {
    public BridgeHealthReport {
        reason = reason == null ? "" : reason;
    }

    public static BridgeHealthReport ok() {
        return new BridgeHealthReport(true, "");
    }

    public static BridgeHealthReport failed(String reason) {
        String text = reason == null || reason.isBlank() ? "unreachable" : reason;
        return new BridgeHealthReport(false, text);
    }
}
