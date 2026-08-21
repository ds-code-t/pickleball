
package tools.dscode.control.protocol;

public record ControlBridgeError(
        String type,
        String message,
        String stackTrace
) {
}
