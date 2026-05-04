package io.figuard.exception;

/**
 * Thrown by {@code AuthorizationResult.raiseIfDenied()} when the API returned DENIED.
 *
 * <pre>{@code
 * AuthorizationResult result = client.authorize(request)
 *     .raiseIfDenied();   // throws if denied, returns self if authorized
 * }</pre>
 */
public class FiGuardDeniedException extends FiGuardException {

    private final String denialReason;
    private final String denialMessage;
    private final String originalEventId;

    public FiGuardDeniedException(String denialReason, String denialMessage, String originalEventId) {
        super("Spend denied: " + denialReason
              + (denialMessage != null ? " — " + denialMessage : ""));
        this.denialReason = denialReason;
        this.denialMessage = denialMessage;
        this.originalEventId = originalEventId;
    }

    /** The denial code, e.g. {@code "INSUFFICIENT_FUNDS"}, {@code "ANOMALY_DETECTED"}. */
    public String getDenialReason() {
        return denialReason;
    }

    /** Human-readable explanation from the API. */
    public String getDenialMessage() {
        return denialMessage;
    }

    /**
     * Set when {@code denialReason == "ENTITY_ALREADY_AUTHORIZED"}.
     * The UUID of the existing authorized or confirmed event for this entityId.
     */
    public String getOriginalEventId() {
        return originalEventId;
    }
}
