package tools.dscode.control.api;

/** Result returned by exploratory control calls instead of propagating failures into scenario traversal. */
public record ControlCallResult<T>(
        ControlCallStatus status,
        T value,
        ControlError error
) {
    public boolean successful() {
        return status == ControlCallStatus.SUCCESS;
    }

    public static <T> ControlCallResult<T> success(T value) {
        return new ControlCallResult<>(ControlCallStatus.SUCCESS, value, null);
    }

    public static <T> ControlCallResult<T> failed(Throwable error) {
        return new ControlCallResult<>(ControlCallStatus.FAILED, null, ControlError.from(error));
    }

    public static <T> ControlCallResult<T> unavailable(String message) {
        return new ControlCallResult<>(
                ControlCallStatus.UNAVAILABLE,
                null,
                new ControlError("UNAVAILABLE", message == null ? "" : message, "")
        );
    }
}
