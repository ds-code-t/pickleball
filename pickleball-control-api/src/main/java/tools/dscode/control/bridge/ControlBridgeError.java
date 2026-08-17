
package tools.dscode.control.bridge;

public record ControlBridgeError(
        String type,
        String message,
        String stackTrace
) {
}
