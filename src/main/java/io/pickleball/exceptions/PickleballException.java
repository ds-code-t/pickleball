package io.pickleball.exceptions;

import io.cucumber.core.exception.CucumberException;

public class PickleballException extends CucumberException {

    public PickleballException(String message) {
        super(message);
    }

    public PickleballException(String message, Throwable cause) {
        super(message, cause);
    }

    public PickleballException(Throwable cause) {
        super(cause);
    }

}