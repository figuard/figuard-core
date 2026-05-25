#!/usr/bin/env python3
"""
FiGuard + LangChain refund agent demo.

Simulates a multi-step refund orchestration agent using LangChain tools.
Three scenarios run in sequence — all visible on the FiGuard dashboard.

Scenario 1 — Normal refund      : $120 for ORD-001. AUTHORIZED → CONFIRMED.
Scenario 2 — Oversized refund   : $4,800 for ORD-002. DENIED (INSUFFICIENT_FUNDS).
Scenario 3 — Duplicate refund   : ORD-001 again. DENIED (ENTITY_ALREADY_AUTHORIZED).

Usage (no LLM required — tools run directly):
    cd sdk/python && pip install -e ".[langchain]"
    cd ../../examples
    python langchain_refund_demo.py

With a real LangChain agent (requires OpenAI key):
    python langchain_refund_demo.py --openai-key sk-...

Dashboard:
    http://localhost:8080/ui
    Open the budget printed at startup to watch events appear in real time.
"""

import argparse
import sys
import uuid
from typing import Any, Optional

sys.path.insert(0, "../sdk/python")

try:
    from langchain_core.tools import BaseTool
    from langchain_core.callbacks import CallbackManagerForToolRun
except ImportError:
    print(
        "Install LangChain:\n"
        "  pip install langchain-core langchain langchain-openai"
    )
    sys.exit(1)

from figuard import FiGuardClient
from figuard.integrations.langchain import FiGuardCallbackHandler


# ── Helpers ───────────────────────────────────────────────────────────────────

def section(title: str) -> None:
    print(f"\n{'═' * 62}")
    print(f"  {title}")
    print(f"{'═' * 62}")

def ok(msg: str) -> None:   print(f"  ✓  {msg}")
def bad(msg: str) -> None:  print(f"  ✗  {msg}")
def info(msg: str) -> None: print(f"     {msg}")


# ── Fake order database ───────────────────────────────────────────────────────

ORDERS = {
    "ORD-001": {"amount": 120.00,  "currency": "USD", "status": "cancelled", "customer": "Alice Chen"},
    "ORD-002": {"amount": 4800.00, "currency": "USD", "status": "cancelled", "customer": "Bob Martinez"},
    "ORD-003": {"amount": 89.50,   "currency": "USD", "status": "cancelled", "customer": "Carol Singh"},
}


# ── LangChain tools ───────────────────────────────────────────────────────────

class OrderLookupTool(BaseTool):
    """
    Read-only order lookup. Not guarded — no money moves here.
    Demonstrates partial-tree instrumentation: the FiGuardCallbackHandler
    records this node in the topology but never creates a FiGuard event for it.
    The walk-up algorithm skips it transparently when resolving parentEventId
    for the downstream refund tool.
    """
    name: str = "lookup_order"
    description: str = (
        "Look up an order by ID. Returns status, amount, currency, and customer name."
    )

    def _run(
        self,
        order_id: str,
        run_manager: Optional[CallbackManagerForToolRun] = None,
    ) -> str:
        order = ORDERS.get(order_id)
        if not order:
            return f"Order {order_id} not found."
        return (
            f"Order {order_id}: status={order['status']}, "
            f"amount={order['currency']} {order['amount']:.2f}, "
            f"customer={order['customer']}"
        )


class RefundProcessorTool(BaseTool):
    """
    Issues a refund. Calls FiGuard authorize() before touching any payment.
    If denied, returns the denial reason to the caller — no money moves.

    entity_id=order_id on the authorize call activates entity deduplication:
    a second refund for the same order is denied with ENTITY_ALREADY_AUTHORIZED
    without any application-level dedup code required.
    """
    name: str = "process_refund"
    description: str = (
        "Issue a refund to a customer for a cancelled or disputed order. "
        "Args: order_id (str), amount (float), currency (str), reason (str)."
    )

    # Set by the demo runner after construction.
    client: Optional[Any] = None
    session_token: Optional[str] = None
    trace_id: Optional[str] = None

    class Config:
        arbitrary_types_allowed = True

    def _run(
        self,
        order_id: str,
        amount: float,
        currency: str = "USD",
        reason: str = "customer_cancellation",
        run_manager: Optional[CallbackManagerForToolRun] = None,
    ) -> str:
        result = self.client.authorize(
            session_token=self.session_token,
            agent_id="refund_processor",
            action_type="REFUND",
            description=f"Refund {currency} {amount:.2f} for order {order_id} — {reason}",
            requested_quantity=amount,
            currency=currency,
            claimed_category="refunds",
            entity_id=order_id,                    # dedup: one refund per order
            idempotency_key=f"refund-{order_id}",  # safe to retry
            trace_id=self.trace_id,
        )

        if result.is_authorized:
            # Simulate payment processor (Stripe/Adyen/etc). In production:
            # call the real payment API here, then confirm with actual amount.
            self.client.confirm_event(result.event_id, confirmed_quantity=amount)
            return (
                f"Refund of {currency} {amount:.2f} issued for order {order_id}. "
                f"FiGuard event: {result.event_id}"
            )
        else:
            return (
                f"Refund denied for order {order_id}. "
                f"Reason: {result.denial_reason}. "
                f"{result.denial_message or ''}"
            )


# ── Demo runner ───────────────────────────────────────────────────────────────

def run(base_url: str, api_key: str, openai_key: Optional[str]) -> None:
    client = FiGuardClient(api_key=api_key, base_url=base_url)

    # ── 1. Create a $500 refund budget ────────────────────────────────────────
    section("1 / Setup — Create refund budget")

    budget = client.create_budget(
        user_id="ops_team",
        total_limit=500.00,
        currency="USD",
        expires_in="2h",
        entity_dedup_enabled=True,    # prevents double-refund for the same order
        velocity_max_per_minute=10,   # catches runaway loops
        allocations=[
            {
                "category": "refunds",
                "limit": 500.00,
                "allowedCategories": ["refunds"],
            }
        ],
    )

    trace_id = f"demo-{uuid.uuid4().hex[:8]}"

    ok(f"Budget ID    : {budget.id}")
    ok(f"Total limit  : USD {budget.total_limit:.2f}")
    ok(f"Available    : USD {budget.available_quantity:.2f}")
    ok(f"Trace ID     : {trace_id}")
    info(f"")
    info(f"Open dashboard now: {base_url}/ui")
    info(f"Find budget: {budget.id}")
    info(f"Filter ledger by traceId: {trace_id}")

    # ── 2. Wire tools ─────────────────────────────────────────────────────────
    order_lookup = OrderLookupTool()

    refund_tool = RefundProcessorTool()
    refund_tool.client = client
    refund_tool.session_token = budget.session_token
    refund_tool.trace_id = trace_id

    # FiGuardCallbackHandler threads parent_event_id through the LangChain
    # call graph automatically — each tool call becomes a child of the
    # chain/agent node that invoked it. Only needed for the LLM path below.
    handler = FiGuardCallbackHandler(
        client=client,
        session_token=budget.session_token,
        ignore_tools={"lookup_order"},  # read-only tool, no spend
    )

    # ── 3. Scenario 1: Normal refund ($120, ORD-001) ──────────────────────────
    section("2 / Scenario 1 — Normal refund  (ORD-001, $120.00)")
    info("Expected: AUTHORIZED → CONFIRMED")

    lookup = order_lookup._run("ORD-001")
    info(f"Order lookup : {lookup}")

    result = refund_tool._run(order_id="ORD-001", amount=120.00)
    if "issued" in result:
        ok(result)
    else:
        bad(result)

    # ── 4. Scenario 2: Oversized refund ($4,800, ORD-002) ────────────────────
    section("3 / Scenario 2 — Oversized refund  (ORD-002, $4,800.00)")
    info("Expected: DENIED — ALLOCATION_EXHAUSTED  ($4,800 exceeds the $500 refund allocation)")

    lookup = order_lookup._run("ORD-002")
    info(f"Order lookup : {lookup}")

    result = refund_tool._run(order_id="ORD-002", amount=4800.00)
    if "issued" in result:
        ok(result)
    else:
        bad(result)

    # ── 5. Scenario 3: Duplicate refund (ORD-001 again) ──────────────────────
    section("4 / Scenario 3 — Duplicate refund  (ORD-001, attempt 2)")
    info("Expected: DENIED — ENTITY_ALREADY_AUTHORIZED  (same order, already refunded)")

    # Use a fresh idempotency key so this doesn't hit the cache from Scenario 1.
    # Without a fresh key, the server returns the original CONFIRMED response
    # and never reaches the entity dedup check.
    auth = client.authorize(
        session_token=budget.session_token,
        agent_id="refund_processor",
        action_type="REFUND",
        description="Refund USD 120.00 for order ORD-001 — retry_attempt",
        requested_quantity=120.00,
        currency="USD",
        claimed_category="refunds",
        entity_id="ORD-001",
        idempotency_key=f"refund-ORD-001-retry-{uuid.uuid4().hex[:8]}",
        trace_id=trace_id,
    )
    result = (
        f"Refund denied for order ORD-001. Reason: {auth.denial_reason}. {auth.denial_message or ''}"
        if not auth.is_authorized
        else f"Refund issued (unexpected). FiGuard event: {auth.event_id}"
    )
    if "issued" in result:
        ok(result)
    else:
        bad(result)

    # ── 6. (Optional) Run as a real LangChain agent ───────────────────────────
    if openai_key:
        section("5 / LangChain agent run  (GPT-4o-mini)")
        info("Asking the agent to process ORD-003 via natural language...")

        import os
        os.environ["OPENAI_API_KEY"] = openai_key

        from langchain_openai import ChatOpenAI
        from langchain.agents import AgentExecutor, create_tool_calling_agent
        from langchain_core.prompts import ChatPromptTemplate, MessagesPlaceholder

        llm = ChatOpenAI(model="gpt-4o-mini", temperature=0)
        prompt = ChatPromptTemplate.from_messages([
            ("system",
             "You are a refund processing agent. When asked to process a refund, "
             "first look up the order, then issue the refund. "
             "Report the outcome clearly, including any denial reason."),
            ("human", "{input}"),
            MessagesPlaceholder("agent_scratchpad"),
        ])
        agent = create_tool_calling_agent(llm, [order_lookup, refund_tool], prompt)
        executor = AgentExecutor(
            agent=agent,
            tools=[order_lookup, refund_tool],
            handle_tool_error=True,
            verbose=True,
        )

        agent_result = executor.invoke(
            {"input": "Please process the refund for order ORD-003."},
            config={"callbacks": [handler]},
        )
        info(f"Agent response: {agent_result['output']}")

    # ── 7. Ledger ─────────────────────────────────────────────────────────────
    section("6 / Ledger — all events in this run")

    ledger = client.get_ledger(budget_id=budget.id, page=0, size=20)
    state_icons = {
        "AUTHORIZED": "⬤ AUTHORIZED",
        "CONFIRMED":  "✓ CONFIRMED ",
        "DENIED":     "✗ DENIED    ",
        "VOIDED":     "○ VOIDED    ",
        "FAILED":     "✗ FAILED    ",
    }
    for ev in ledger.events:
        icon = state_icons.get(ev.decision, ev.decision)
        denial = f"  [{ev.denial_reason}]" if ev.denial_reason else ""
        print(f"  {icon}  USD {ev.requested_quantity:>8.2f}  {ev.description[:50]}{denial}")

    # ── 8. Budget summary ─────────────────────────────────────────────────────
    section("7 / Budget summary")
    b = client.get_budget(budget.id)
    ok(f"Spent       : USD {b.quantity_spent:.2f}")
    ok(f"Reserved    : USD {b.quantity_reserved:.2f}")
    ok(f"Remaining   : USD {b.available_quantity:.2f}")
    info("")
    info(f"Dashboard → {base_url}/ui")
    info(f"Budget ID  → {budget.id}")
    info(f"Trace ID   → {trace_id}")


# ── Entry point ───────────────────────────────────────────────────────────────

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="FiGuard + LangChain refund demo")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--api-key", default="fg_live_demo")
    parser.add_argument("--openai-key", default=None,
                        help="Optional. Runs Scenarios 1-3 as a real LangChain agent.")
    args = parser.parse_args()
    run(base_url=args.base_url, api_key=args.api_key, openai_key=args.openai_key)
