package dev.keel.model;

public class ModelException extends RuntimeException {

    private final boolean retryable;

    public ModelException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
