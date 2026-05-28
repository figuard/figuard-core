/**
 * FiGuard TypeScript SDK — pre-flight spend authorization for AI agents.
 *
 * Quick start:
 *   import { FiGuardClient, FiGuardDeniedException } from "figuard";
 *
 *   const client = new FiGuardClient({ apiKey: "fg_live_..." });
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
 *       sessionToken: budget.primaryToken!.sessionToken!,
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

export const VERSION = "0.3.0";

// Client
export { FiGuardClient, resolveExpiresAt, buildAllocationsFromPercentages } from "./client";
export type {
  FiGuardClientOptions,
  AllocationInput,
  AllocationPercentageInput,
  CreateBudgetOptions,
  AuthorizeOptions,
  ConfirmEventOptions,
  FailEventOptions,
  VoidEventOptions,
  VoidTreeOptions,
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

// Denial reason constants
export { DenialReason } from "./models";
export type { DenialReasonCode } from "./models";

// Models
export type {
  AllocationResponse,
  AllocationSnapshot,
  ApiKey,
  AuthorizationResult,
  Budget,
  BudgetStateSnapshot,
  BudgetTimeline,
  BudgetToken,
  ReplayAllocationState,
  TimelineEvent,
  WebhookConfig,
  WebhookDelivery,
  WebhookTestResult,
  BudgetFundingResult,
  BudgetSnapshot,
  EntitlementItem,
  LedgerPage,
  SpendEventResponse,
  SpendTree,
  SpendTreeNode,
  Subscription,
  VoidResult,
  VoidTreeResult,
} from "./models";

// Multi-resource
export { CompositeGuard, GuardedResource } from "./composite";
export type {
  CompositeAuthorizationResult,
  CompositeAuthorizeOptions,
} from "./composite";
