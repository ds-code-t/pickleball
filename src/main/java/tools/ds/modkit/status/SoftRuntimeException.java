package tools.ds.modkit.status;

public class SoftRuntimeException extends RuntimeException {

    public SoftRuntimeException() {
        super();
    }

    public SoftRuntimeException(String message) {
        super(message);
    }

    public SoftRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }

    public SoftRuntimeException(Throwable cause) {
        super(cause);
    }

    protected SoftRuntimeException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}