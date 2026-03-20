package tools.dscode.common.exceptions;

public class StepCreationException extends RuntimeException implements PickleBallExceptionInterface {

    public StepCreationException() {
        super();
    }

    public StepCreationException(String message) {
        super(message);
    }

    public StepCreationException(String message, Throwable cause) {
        super(message, cause);
    }

    public StepCreationException(Throwable cause) {
        super(cause);
    }

    protected StepCreationException(
            String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace
    ) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
