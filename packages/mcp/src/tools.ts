/**
 * Tool definitions for the FiGuard MCP server.
 *
 * Each tool maps directly to one FiGuardClient method.
 * Descriptions are written for Claude — specific enough that it can fill
 * arguments correctly without hand-holding.
 */

export const TOOLS = [
  {
    name: "figuard_create_budget",
    description:
      "Create a new FiGuard budget for an agent session. Returns a session_token that must be passed to figuard_authorize. " +
      "The session_token is only returned once — store it for the duration of the agent session. " +
      "Use currency (e.g. 'USD') for monetary budgets. Use unit (e.g. 'tokens') for resource budgets. " +
      "Optionally add allocations to ring-fence spend by category (e.g. separate limits for flights and hotels). " +
      "Pass external_reference to make this call idempotent: if a live budget with that reference already exists it is " +
      "returned (HTTP 200) instead of creating a duplicate. If the reference exists but with a different configuration, " +
      "a 409 is returned. Use this to safely handle orchestrator restarts.",
    inputSchema: {
      type: "object",
      properties: {
        user_id: {
          type: "string",
          description: "Identifier for the user or agent session this budget belongs to.",
        },
        external_reference: {
          type: "string",
          description:
            "Optional stable identifier for this budget (e.g. 'run-abc-123', 'order-456'). " +
            "When provided, re-calling figuard_create_budget with the same external_reference returns " +
            "the existing live budget instead of creating a duplicate — safe for orchestrator restarts.",
        },
        total_limit: {
          type: "number",
          description: "Maximum total amount the agent is allowed to spend. For USD budgets this is dollars, not cents.",
        },
        currency: {
          type: "string",
          description: "ISO 4217 currency code (e.g. 'USD', 'EUR'). Required for monetary budgets. Omit for resource budgets.",
        },
        unit: {
          type: "string",
          description: "Resource unit label (e.g. 'tokens', 'api_calls'). Required for resource budgets. Omit for monetary budgets.",
        },
        expires_in: {
          type: "string",
          description: "How long the budget is valid. Accepts '24h', '7d', '30m', or a number of seconds. Defaults to '24h'.",
        },
        intent_context: {
          type: "string",
          description: "Optional human-readable description of what this budget is for (e.g. 'Book travel to NYC for user_123').",
        },
        anomaly_detection_enabled: {
          type: "boolean",
          description: "When true, FiGuard monitors spend requests for statistical anomalies. Recommended for production.",
        },
        auto_pause_on_anomaly: {
          type: "boolean",
          description:
            "Controls anomaly response mode. When true (default), an anomalous request pauses the budget — no further spend until a human resumes it. " +
            "When false (advisory mode), the anomalous request is still denied and ANOMALY_DETECTED webhook fires, but the budget stays ACTIVE. " +
            "Use false for high-throughput agents where a single spike should not halt the entire fleet.",
        },
        allocations: {
          type: "array",
          description: "Optional category-level budget caps. Use to split the total limit across categories (e.g. $300 for flights, $200 for hotels).",
          items: {
            type: "object",
            properties: {
              category: { type: "string", description: "Category name (e.g. 'flights', 'hotels')." },
              limit: { type: "number", description: "Maximum spend for this category." },
              enforcement_mode: {
                type: "string",
                enum: ["OPEN", "CATEGORY_CONSTRAINED", "STRICT"],
                description: "STRICT blocks any claimed_item_type in forbidden_item_types. CATEGORY_CONSTRAINED checks claimed_category. OPEN allows anything within the limit.",
              },
              allowed_categories: {
                type: "array",
                items: { type: "string" },
                description: "Required when enforcement_mode is CATEGORY_CONSTRAINED or STRICT. " +
                  "Omit for OPEN mode. Defines which claimedCategory values this allocation will accept. " +
                  "Example: [\"flight\"] for a flights allocation.",
              },
              forbidden_item_types: {
                type: "array",
                items: { type: "string" },
                description: "Specific item types to block (e.g. 'gift_card', 'upgrade'). Only applies in STRICT mode.",
              },
            },
            required: ["category", "limit"],
          },
        },
      },
      required: ["user_id", "total_limit"],
    },
  },

  {
    name: "figuard_authorize",
    description:
      "Pre-flight authorization — ask FiGuard if a spend is permitted before taking any action. " +
      "Returns AUTHORIZED (with event_id) or DENIED (with denial_reason). " +
      "Always call this before spending. Only proceed if the result is AUTHORIZED. " +
      "The idempotency_key must be unique per logical spend intent — reuse the same key on retries so the same spend is never double-authorized. " +
      "After the action succeeds call figuard_confirm; if it fails call figuard_fail. " +
      "IMPORTANT: When the budget has allocations, pass the exact category string from the budget's allocation_categories field — do not use synonyms or plural forms (e.g. use 'flight' not 'flights').",
    inputSchema: {
      type: "object",
      properties: {
        session_token: {
          type: "string",
          description: "The session_token returned by figuard_create_budget. Never expose this in logs.",
        },
        agent_id: {
          type: "string",
          description: "Identifier for the agent making this request (e.g. 'booking-agent', 'refund-agent').",
        },
        action_type: {
          type: "string",
          description: "Type of action being authorized (e.g. 'PURCHASE', 'REFUND', 'LLM_CALL', 'API_CALL').",
        },
        description: {
          type: "string",
          description: "Human-readable description of what the agent is about to do (e.g. 'Book NYC to LAX flight').",
        },
        requested_quantity: {
          type: "number",
          description: "Amount to reserve. For USD budgets this is dollars. For token budgets this is the token count.",
        },
        idempotency_key: {
          type: "string",
          description:
            "Optional. Unique key for this spend intent. Use a UUID or stable identifier. " +
            "Reuse on retries — never generate a new key for the same logical spend. " +
            "When omitted, the SDK auto-generates a UUID v4 (safe for fire-and-forget calls).",
        },
        claimed_category: {
          type: "string",
          description: "Category of this spend (e.g. 'flight', 'hotel'). Required when the budget has allocations.",
        },
        claimed_item_type: {
          type: "string",
          description: "Specific item type (e.g. 'economy_ticket', 'hotel_room'). Used for STRICT enforcement mode.",
        },
        parent_event_id: {
          type: "string",
          description:
            "event_id of the parent spend if this is a sub-agent call. Used to build the causal chain. " +
            "The parent event must be in AUTHORIZED or CONFIRMED state — passing a DENIED or VOIDED " +
            "parent_event_id will result in a 400 INVALID_PARENT_EVENT error.",
        },
        trace_id: {
          type: "string",
          description: "Optional run ID to link all events from a single agent run together for debugging.",
        },
        dry_run: {
          type: "boolean",
          description: "When true, runs all enforcement checks and returns AUTHORIZED/DENIED but writes nothing to the ledger. Use for testing.",
        },
      },
      required: ["session_token", "agent_id", "action_type", "description", "requested_quantity"],
    },
  },

  {
    name: "figuard_confirm",
    description:
      "Confirm a previously authorized spend after the action succeeds. " +
      "Pass the actual amount spent — it may differ from the authorized amount (e.g. authorized $340, flight cost $337.50). " +
      "This releases the difference back to the budget. Always confirm after a successful action.",
    inputSchema: {
      type: "object",
      properties: {
        event_id: {
          type: "string",
          description: "The event_id returned by figuard_authorize.",
        },
        confirmed_quantity: {
          type: "number",
          description: "Actual amount consumed. May be less than the authorized amount.",
        },
        external_transaction_id: {
          type: "string",
          description: "Optional reference from your payment processor (e.g. Stripe charge ID) for audit purposes.",
        },
      },
      required: ["event_id", "confirmed_quantity"],
    },
  },

  {
    name: "figuard_fail",
    description:
      "Mark an authorized spend as failed when the action did not succeed (e.g. payment processor declined, API error). " +
      "This releases the reserved funds back to the budget so they can be used again.",
    inputSchema: {
      type: "object",
      properties: {
        event_id: {
          type: "string",
          description: "The event_id returned by figuard_authorize.",
        },
        reason: {
          type: "string",
          description: "Short reason code for the failure (e.g. 'PAYMENT_DECLINED', 'API_ERROR', 'TOOL_ERROR').",
        },
        error_message: {
          type: "string",
          description: "Optional human-readable error detail for the audit log.",
        },
      },
      required: ["event_id", "reason"],
    },
  },

  {
    name: "figuard_void",
    description:
      "Cancel an authorized reservation that was never used. " +
      "Use this when the agent decides not to proceed with an action after authorizing it (e.g. user cancelled, better option found).",
    inputSchema: {
      type: "object",
      properties: {
        event_id: {
          type: "string",
          description: "The event_id returned by figuard_authorize.",
        },
        reason: {
          type: "string",
          description: "Short reason for voiding (e.g. 'USER_CANCELLED', 'BETTER_OPTION_FOUND', 'PLAN_CHANGED').",
        },
        void_child_events: {
          type: "boolean",
          description: "When true, also void child events in the causal chain. Defaults to false.",
        },
      },
      required: ["event_id", "reason"],
    },
  },

  {
    name: "figuard_get_budget",
    description:
      "Get the current state of a budget — total limit, amount spent, amount reserved, amount available, status, and per-category allocations. " +
      "Use this to check remaining budget before planning an agent's actions.",
    inputSchema: {
      type: "object",
      properties: {
        budget_id: {
          type: "string",
          description: "The budget ID (starts with 'bgt_').",
        },
      },
      required: ["budget_id"],
    },
  },

  {
    name: "figuard_get_ledger",
    description:
      "Query the authorization ledger for a budget — returns a paginated list of spend events (authorized, confirmed, denied, voided, failed). " +
      "Use this to review what an agent has spent or to debug a denial.",
    inputSchema: {
      type: "object",
      properties: {
        budget_id: {
          type: "string",
          description: "The budget ID (starts with 'bgt_').",
        },
        page: {
          type: "number",
          description: "Page number, zero-indexed. Defaults to 0.",
        },
        size: {
          type: "number",
          description: "Events per page. Defaults to 20, max 100.",
        },
        decision: {
          type: "string",
          enum: ["AUTHORIZED", "CONFIRMED", "DENIED", "VOIDED", "FAILED"],
          description: "Filter by event decision. Omit to return all events.",
        },
        trace_id: {
          type: "string",
          description: "Filter by trace ID to see only events from a specific agent run.",
        },
      },
      required: ["budget_id"],
    },
  },

  {
    name: "figuard_resume_budget",
    description:
      "Resume a budget that was paused by anomaly detection or manually. " +
      "A budget is paused when a spend request is statistically unusual (autoPauseOnAnomaly=true) or via PATCH /budgets/{id}. " +
      "A human must review and provide an override reason before the budget can be used again. " +
      "Requires override_reason explaining why the pause was reviewed and cleared.",
    inputSchema: {
      type: "object",
      properties: {
        budget_id: {
          type: "string",
          description: "The budget ID (starts with 'bgt_').",
        },
        override_reason: {
          type: "string",
          description: "Required explanation for why the budget is being resumed (e.g. 'Reviewed — legitimate bulk purchase approved by ops team').",
        },
        override_by: {
          type: "string",
          description: "Optional identifier for the operator or system performing the override (e.g. 'ops-team', 'user_456').",
        },
      },
      required: ["budget_id", "override_reason"],
    },
  },

  {
    name: "figuard_extend_budget",
    description:
      "Extend a budget's expiry window. Use this to keep a long-running agent alive past its original expiry. " +
      "The new expiry must be later than the current one and at most 24 hours from now. " +
      "Can be called repeatedly (e.g. extend by 2 hours every 2 hours for a 6-hour task). " +
      "Returns 409 if the budget is CANCELLED or EXHAUSTED. Returns 400 if the new expiry is earlier than the current one.",
    inputSchema: {
      type: "object",
      properties: {
        budget_id: {
          type: "string",
          description: "The budget ID to extend.",
        },
        expires_in: {
          type: "string",
          description: "Relative duration from now (e.g. '2h', '30m'). Mutually exclusive with expires_at.",
        },
        expires_at: {
          type: "string",
          description: "Absolute ISO 8601 expiry timestamp. Mutually exclusive with expires_in.",
        },
      },
      required: ["budget_id"],
    },
  },

  {
    name: "figuard_create_delegation_token",
    description:
      "Create a scoped delegation token for a fleet budget. Each sub-agent (e.g. per-customer refund agent) " +
      "gets its own token with per-category spend caps. The sub-agent calls figuard_authorize with this token " +
      "exactly as it would with a normal session token — FiGuard enforces both the per-token caps and the " +
      "fleet-level allocations transparently. " +
      "The session_token is returned once — hand it to the sub-agent immediately and store it securely.",
    inputSchema: {
      type: "object",
      properties: {
        budget_id: {
          type: "string",
          description: "The fleet budget ID to delegate from.",
        },
        label: {
          type: "string",
          description: "Human-readable label for this token, e.g. 'refund-agent-order-123'.",
        },
        caps: {
          type: "array",
          description:
            "Per-category spend caps for this sub-agent. Only listed categories are cap-enforced at the " +
            "delegation level. Categories not listed pass through to the fleet allocation only.",
          items: {
            type: "object",
            properties: {
              category: { type: "string", description: "Category name (must match a fleet allocation category)." },
              limit: { type: "number", description: "Maximum spend for this category via this token." },
            },
            required: ["category", "limit"],
          },
        },
      },
      required: ["budget_id", "label", "caps"],
    },
  },

  {
    name: "figuard_revoke_delegation_token",
    description:
      "Revoke a delegation token immediately. Any subsequent figuard_authorize call using this token " +
      "will be rejected with INVALID_SESSION_TOKEN. Already-authorized events are not affected. " +
      "Fires DELEGATION_TOKEN_REVOKED webhook. Idempotent.",
    inputSchema: {
      type: "object",
      properties: {
        token_id: {
          type: "string",
          description: "The delegation token ID to revoke.",
        },
      },
      required: ["token_id"],
    },
  },

  {
    name: "figuard_get_delegation_token",
    description:
      "Get the current state of a delegation token — label, status, per-category cap usage. " +
      "The raw session_token is never returned, only the prefix.",
    inputSchema: {
      type: "object",
      properties: {
        token_id: {
          type: "string",
          description: "The delegation token ID.",
        },
      },
      required: ["token_id"],
    },
  },

  {
    name: "figuard_cancel_batch",
    description:
      "Cancel up to 100 budgets in a single call. " +
      "Already-terminal budgets (EXPIRED, CANCELLED, EXHAUSTED) are included in the response without an error — the call is idempotent per budget. " +
      "Use this for bulk teardown of agent sessions (e.g. cancel all budgets for a deactivated user or a completed workflow).",
    inputSchema: {
      type: "object",
      properties: {
        budget_ids: {
          type: "array",
          items: { type: "string" },
          description: "List of budget IDs to cancel. Maximum 100.",
        },
      },
      required: ["budget_ids"],
    },
  },
] as const;
