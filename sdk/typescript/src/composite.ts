/**
 * CompositeGuard — multi-resource authorization for agents that consume more than
 * one type of bounded resource per operation (e.g. tokens + USD).
 *
 * Usage:
 *   import { FiGuardClient, CompositeGuard, GuardedResource } from "figuard";
 *
 *   const guard = new CompositeGuard([
 *     new GuardedResource(client, tokenBudget.tokens![0].sessionToken!, "tokens"),
 *     new GuardedResource(client, usdBudget.tokens![0].sessionToken!, "USD"),
 *   ]);
 *
 *   const result = await guard.authorize({
 *     agentId: "travel_agent",
 *     actionType: "LLM_CALL",
 *     description: "search flights",
 *     requested: { tokens: 1500, USD: 0.09 },
 *     idempotencyKey: crypto.randomUUID(),
 *   });
 *
 *   if (result.allAuthorized) {
 *     // ... do the work ...
 *     await guard.confirm(result, { tokens: 1423, USD: 0.085 });
 *   } else {
 *     console.log(`Denied on ${result.firstDenialResource}: ${result.firstDenial?.denialReason}`);
 *   }
 *
 * Design notes:
 * - Resources are authorized in list order; first denial voids all prior authorizations.
 * - Idempotency key is namespaced per resource: "{key}:{resource}" — safe to retry.
 * - Confirm failures are swallowed (action already succeeded); logged at WARNING.
 * - Void failures on partial denial are logged but not thrown (auto-expiry will clean up).
 */

import { FiGuardClient } from "./client";
import { AuthorizationResult, SpendEventResponse } from "./models";
import { randomUUID } from "crypto";

export class GuardedResource {
  constructor(
    readonly client: FiGuardClient,
    readonly sessionToken: string,
    readonly resource: string,
  ) {}
}

export interface CompositeAuthorizationResult {
  readonly resources: string[];
  readonly authorizations: AuthorizationResult[];
  readonly allAuthorized: boolean;
  readonly firstDenial?: AuthorizationResult;
  readonly firstDenialResource?: string;
  /** IDs of all authorized events — pass to confirm() or fail(). */
  eventIds(): string[];
}

export interface CompositeAuthorizeOptions {
  agentId: string;
  actionType: string;
  description: string;
  /** Mapping of resource name → quantity, e.g. { tokens: 1500, USD: 0.09 }. */
  requested: Record<string, number>;
  /** Shared idempotency key; namespaced per resource internally. */
  idempotencyKey?: string;
  traceId?: string;
}

export class CompositeGuard {
  private readonly resources: GuardedResource[];

  constructor(resources: GuardedResource[]) {
    if (resources.length === 0) {
      throw new Error("CompositeGuard requires at least one GuardedResource");
    }
    this.resources = resources;
  }

  async authorize(options: CompositeAuthorizeOptions): Promise<CompositeAuthorizationResult> {
    const key = options.idempotencyKey ?? randomUUID();
    const authorized: Array<{ resource: GuardedResource; result: AuthorizationResult }> = [];

    for (const resource of this.resources) {
      const qty = options.requested[resource.resource] ?? 0;
      let result: AuthorizationResult;

      try {
        result = await resource.client.authorize({
          sessionToken: resource.sessionToken,
          agentId: options.agentId,
          actionType: options.actionType,
          description: options.description,
          requestedQuantity: qty,
          idempotencyKey: `${key}:${resource.resource}`,
          traceId: options.traceId,
        });
      } catch (err) {
        console.error(
          `CompositeGuard authorize error — resource=${resource.resource}:`,
          err,
        );
        await this.voidAll(authorized, "COMPOSITE_AUTHORIZE_ERROR");
        throw err;
      }

      if (!result.isAuthorized) {
        await this.voidAll(authorized, "COMPOSITE_PARTIAL_DENIAL");
        const allResults = [...authorized.map((a) => a.result), result];
        const resourceNames = [...authorized.map((a) => a.resource.resource), resource.resource];
        return makeResult(resourceNames, allResults, false, result, resource.resource);
      }

      authorized.push({ resource, result });
    }

    return makeResult(
      this.resources.map((r) => r.resource),
      authorized.map((a) => a.result),
      true,
    );
  }

  async confirm(
    result: CompositeAuthorizationResult,
    confirmed: Record<string, number>,
  ): Promise<SpendEventResponse[]> {
    const events: SpendEventResponse[] = [];
    const pairs = this.resources.slice(0, result.authorizations.length);

    for (let i = 0; i < pairs.length; i++) {
      const resource = pairs[i];
      const auth = result.authorizations[i];
      if (!auth || !auth.isAuthorized) continue;
      const qty = confirmed[resource.resource] ?? 0;
      try {
        const event = await resource.client.confirmEvent({
          eventId: auth.eventId,
          confirmedQuantity: qty,
        });
        events.push(event);
      } catch (err) {
        console.warn(
          `CompositeGuard confirm swallowed — resource=${resource.resource} event=${auth.eventId}:`,
          err,
        );
      }
    }
    return events;
  }

  async fail(
    result: CompositeAuthorizationResult,
    reason = "TOOL_ERROR",
    errorMessage?: string,
  ): Promise<void> {
    const pairs = this.resources.slice(0, result.authorizations.length);
    for (let i = 0; i < pairs.length; i++) {
      const resource = pairs[i];
      const auth = result.authorizations[i];
      if (!auth || !auth.isAuthorized) continue;
      try {
        await resource.client.failEvent({ eventId: auth.eventId, reason, errorMessage });
      } catch (err) {
        console.warn(
          `CompositeGuard failEvent error — resource=${resource.resource} event=${auth.eventId}:`,
          err,
        );
      }
    }
  }

  private async voidAll(
    authorized: Array<{ resource: GuardedResource; result: AuthorizationResult }>,
    reason: string,
  ): Promise<void> {
    for (const { resource, result } of authorized) {
      try {
        await resource.client.voidEvent({ eventId: result.eventId, reason });
      } catch (err) {
        console.warn(
          `CompositeGuard voidEvent error — resource=${resource.resource} event=${result.eventId} ` +
            "(auto-expiry will clean up if authorizationExpirySeconds is set):",
          err,
        );
      }
    }
  }
}

function makeResult(
  resources: string[],
  authorizations: AuthorizationResult[],
  allAuthorized: boolean,
  firstDenial?: AuthorizationResult,
  firstDenialResource?: string,
): CompositeAuthorizationResult {
  return {
    resources,
    authorizations,
    allAuthorized,
    firstDenial,
    firstDenialResource,
    eventIds() {
      return authorizations.filter((r) => r.isAuthorized).map((r) => r.eventId);
    },
  };
}
