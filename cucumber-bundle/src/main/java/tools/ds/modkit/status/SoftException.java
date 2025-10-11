package tools.ds.modkit.status;

public class SoftException extends Exception {

    public SoftException() {
        super();
    }

    public SoftException(String message) {
        super(message);
    }

    public SoftException(String message, Throwable cause) {
        super(message, cause);
    }

    public SoftException(Throwable cause) {
        super(cause);
    }

    protected SoftException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}