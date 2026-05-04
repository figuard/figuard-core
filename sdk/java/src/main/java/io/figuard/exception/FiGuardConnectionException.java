package io.figuard.exception;

/**
 * Network-level failure (timeout, DNS failure) after all retries are exhausted.
 */
public class FiGuardConnectionException extends FiGuardException {

    public FiGuardConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
