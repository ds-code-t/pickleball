package tools.dscode.common.dataelements;

public final class DataQueryException extends RuntimeException {
    public DataQueryException(String message) {
        super(message);
    }

    public DataQueryException(String message, Throwable cause) {
        super(message, cause);
    }
}
