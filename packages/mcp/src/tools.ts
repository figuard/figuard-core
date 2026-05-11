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
      "Optionally add allocations to ring-fence spend by category (e.g. separate limits for flights and hotels).",
    inputSchema: {
      type: "object",
      properties: {
        user_id: {
          type: "string",
          description: "Identifier for the user or agent session this budget belongs to.",
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
          description: "When true, FiGuard auto-pauses the budget if a single spend request is statistically unusual. Recommended for production.",
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
                description: "Spend is only allowed when claimed_category matches one of these values.",
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
      "After the action succeeds call figuard_confirm; if it fails call figuard_fail.",
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
          description: "Unique key for this spend intent. Use a UUID or stable identifier. Reuse on retries — never generate a new key for the same logical spend.",
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
          description: "event_id of the parent spend if this is a sub-agent call. Used to build the causal chain.",
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
      required: ["session_token", "agent_id", "action_type", "description", "requested_quantity", "idempotency_key"],
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
      "Resume a budget that was paused by anomaly detection. " +
      "A budget is auto-paused when a spend request is statistically unusual (anomaly detection). " +
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
] as const;
