package io.figuard.exception;

import java.util.Map;

/**
 * An HTTP error response from the FiGuard API (4xx or 5xx after all retries).
 */
public class FiGuardApiException extends FiGuardException {

    private final int statusCode;
    private final Map<String, Object> raw;

    public FiGuardApiException(int statusCode, String message, Map<String, Object> raw) {
        super("FiGuard API error " + statusCode + ": " + message);
        this.statusCode = statusCode;
        this.raw = raw;
    }

    /** HTTP status code returned by the API. */
    public int getStatusCode() {
        return statusCode;
    }

    /** Full response body parsed as a map, may be null for non-JSON responses. */
    public Map<String, Object> getRaw() {
        return raw;
    }
}
