/**
 * FiGuard TypeScript SDK — pre-flight spend authorization for AI agents.
 *
 * Quick start:
 *   import { FiGuardClient, FiGuardDeniedException } from "figuard";
 *
 *   const client = new FiGuardClient({ apiKey: "ab_live_..." });
 *
 *   const budget = await client.createBudget({
 *     userId: "user_123",
 *     totalLimit: 500,
 *     expiresIn: "24h",
 *     currency: "USD",
 *   });
 *
 *   try {
 *     const result = (await client.authorize({
 *       sessionToken: budget.sessionToken!,
 *       agentId: "agent_001",
 *       actionType: "PURCHASE",
 *       description: "NYC flight",
 *       requestedQuantity: 299,
 *       idempotencyKey: "txn-abc-001",
 *     })).raiseIfDenied();
 *
 *     // proceed with transaction...
 *     await client.confirmEvent({ eventId: result.eventId, confirmedQuantity: 299 });
 *   } catch (e) {
 *     if (e instanceof FiGuardDeniedException) {
 *       console.log("Spend denied:", e.denialReason);
 *     }
 *   }
 */

export const VERSION = "0.1.0";

// Client
export { FiGuardClient, resolveExpiresAt } from "./client";
export type {
  FiGuardClientOptions,
  AllocationInput,
  CreateBudgetOptions,
  AuthorizeOptions,
  ConfirmEventOptions,
  FailEventOptions,
  VoidEventOptions,
  ResumeBudgetOptions,
  GetLedgerOptions,
} from "./client";

// Errors
export {
  FiGuardError,
  FiGuardApiError,
  FiGuardDeniedException,
  FiGuardConnectionError,
} from "./errors";

// Models
export type {
  AllocationResponse,
  AllocationSnapshot,
  AuthorizationResult,
  Budget,
  BudgetSnapshot,
  LedgerPage,
  SpendEventResponse,
  SpendTree,
  SpendTreeNode,
  VoidResult,
} from "./models";

// Multi-resource
export { CompositeGuard, GuardedResource } from "./composite";
export type {
  CompositeAuthorizationResult,
  CompositeAuthorizeOptions,
} from "./composite";
