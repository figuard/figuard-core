/**
 * FiGuard exception hierarchy.
 *
 * All SDK errors derive from FiGuardError so callers can catch broadly
 * or narrowly depending on their needs.
 */

export class FiGuardError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "FiGuardError";
    Object.setPrototypeOf(this, new.target.prototype);
  }
}

/**
 * An HTTP error response from the FiGuard API (4xx or 5xx that was not retried away).
 */
export class FiGuardApiError extends FiGuardError {
  readonly statusCode: number;
  readonly raw?: unknown;

  constructor(statusCode: number, message: string, raw?: unknown) {
    super(`FiGuard API error ${statusCode}: ${message}`);
    this.name = "FiGuardApiError";
    this.statusCode = statusCode;
    this.raw = raw;
    Object.setPrototypeOf(this, new.target.prototype);
  }
}

/**
 * Thrown by AuthorizationResult.raiseIfDenied() when the API returned DENIED.
 */
export class FiGuardDeniedException extends FiGuardError {
  readonly denialReason: string;
  readonly denialMessage?: string;
  /** Set when denialReason === "ENTITY_ALREADY_AUTHORIZED" */
  readonly originalEventId?: string;

  constructor(
    denialReason: string,
    denialMessage?: string,
    originalEventId?: string,
  ) {
    const msg = denialMessage
      ? `Spend denied: ${denialReason} — ${denialMessage}`
      : `Spend denied: ${denialReason}`;
    super(msg);
    this.name = "FiGuardDeniedException";
    this.denialReason = denialReason;
    this.denialMessage = denialMessage;
    this.originalEventId = originalEventId;
    Object.setPrototypeOf(this, new.target.prototype);
  }
}

/**
 * Network-level error (timeout, DNS failure, etc.) after all retries exhausted.
 */
export class FiGuardConnectionError extends FiGuardError {
  constructor(message: string) {
    super(message);
    this.name = "FiGuardConnectionError";
    Object.setPrototypeOf(this, new.target.prototype);
  }
}
