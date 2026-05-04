package io.figuard.model;

/** Thin wrapper so void has a distinct return type from confirm/fail. */
public record VoidResult(SpendEventResponse event) {

    /** True when the underlying event decision is VOIDED. */
    public boolean isVoided() {
        return "VOIDED".equals(event.decision());
    }
}
