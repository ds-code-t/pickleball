package io.pickleball.exceptions;

import io.cucumber.core.exception.CucumberException;

public class SoftFailureException extends CucumberException {

    public SoftFailureException(String message) {
        super(message);
    }

    public SoftFailureException(String message, Throwable cause) {
        super(message, cause);
    }

    public SoftFailureException(Throwable cause) {
        super(cause);
    }

}