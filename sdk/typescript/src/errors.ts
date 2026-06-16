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
 * Raised when a server-only capability (delegation, subscriptions, webhooks, replay, …) is
 * used while the client is on the embedded backend. Pass apiKey/baseUrl to use a server.
 */
export class FiGuardCapabilityError extends FiGuardError {
  readonly feature: string;
  constructor(feature: string) {
    super(
      `${feature} requires the FiGuard server (multi-agent fleets). ` +
        `You're on the embedded backend — pass apiKey/baseUrl to use a server.`,
    );
    this.name = "FiGuardCapabilityError";
    this.feature = feature;
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

/**
 * Thrown by `FiGuardClient.verifyWebhook()` when the HMAC-SHA256 signature
 * on an incoming webhook does not match the expected value.
 *
 * Always respond with HTTP 400 when you catch this — do not process the payload.
 * Common causes: wrong webhook secret, payload was modified in transit, or the
 * raw request body was re-encoded before verification (e.g. by a body-parser
 * middleware). Pass the raw Buffer, not a parsed object.
 */
export class FiGuardWebhookVerificationError extends FiGuardError {
  constructor(message: string) {
    super(message);
    this.name = "FiGuardWebhookVerificationError";
    Object.setPrototypeOf(this, new.target.prototype);
  }
}
