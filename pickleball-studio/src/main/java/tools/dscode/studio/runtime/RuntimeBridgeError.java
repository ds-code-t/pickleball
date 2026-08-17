
package tools.dscode.studio.runtime;

public record RuntimeBridgeError(
        String type,
        String message,
        String stackTrace
) {
}
