
package tools.dscode.studio.runtime;

public record RuntimeControlResult(
        String status,
        String valueType,
        String valueText,
        RuntimeBridgeError error,
        RuntimeBridgeStatus runtime
) {
    public boolean successful() {
        return "SUCCESS".equals(status);
    }
}
