package io.figuard.exception;

/**
 * Base class for all FiGuard SDK exceptions.
 */
public class FiGuardException extends RuntimeException {

    public FiGuardException(String message) {
        super(message);
    }

    public FiGuardException(String message, Throwable cause) {
        super(message, cause);
    }
}
