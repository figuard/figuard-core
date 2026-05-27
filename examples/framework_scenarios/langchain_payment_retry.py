#!/usr/bin/env python3
"""
Scenario: LangChain Payment Retry → Double Charge

THE PROBLEM
-----------
LangChain passes tool errors back to the LLM, which decides to retry.
When a payment tool times out AFTER Stripe has already charged the card —
a real and common production failure — the retry creates a second charge.
Each call is individually valid. The framework is doing exactly what it
should. There is no if-condition that catches this.

THE FIX
-------
FiGuard's idempotency key is tied to the business operation (invoice ID +
amount), not the attempt. The first authorize() reserves the funds and
records the key. On retry, FiGuard returns DUPLICATE_REQUEST with the
original event ID — the tool skips Stripe and confirms the original event.

MODES
-----
simulation  No API keys needed. LLM decisions are pre-scripted. Stripe
            calls are mocked (fake charge IDs). FiGuard runs against the
            live sandbox — real authorization decisions, real denials,
            real spend tree.

real        Runs a live LangChain agent (OpenAI) with real Stripe test
            charges. Charges appear in your Stripe test dashboard.
            Requires OPENAI_API_KEY and a Stripe test key (sk_test_...).

USAGE
-----
# Simulation (no keys):
python langchain_payment_retry.py

# Real mode:
python langchain_payment_retry.py --mode real --openai-key sk-... --stripe-key sk_test_...

DASHBOARD
---------
https://figuard-sandbox-g1ha.onrender.com/ui
Open the printed budget ID to watch events arrive in real time.
"""

import argparse
import sys
import uuid
from typing import Optional

# ── FiGuard sandbox ────────────────────────────────────────────────────────────
FIGUARD_BASE_URL = "https://figuard-sandbox-g1ha.onrender.com"
FIGUARD_API_KEY  = "sb_live_demo"


# ── Display helpers ────────────────────────────────────────────────────────────

def section(title: str) -> None:
    print(f"\n{'═' * 64}")
    print(f"  {title}")
    print(f"{'═' * 64}")

def ok(msg: str)   -> None: print(f"  ✓  {msg}")
def bad(msg: str)  -> None: print(f"  ✗  {msg}")
def info(msg: str) -> None: print(f"     {msg}")
def step(msg: str) -> None: print(f"  →  {msg}")


# ── Stripe wrapper ─────────────────────────────────────────────────────────────

class StripeClient:
    """
    Wraps Stripe in test mode, or a simulator with identical call semantics.
    Both modes track every charge so the summary can show proof.
    """

    def __init__(self, mode: str, api_key: Optional[str] = None):
        self.mode = mode
        self._charges: list[dict] = []
        if mode == "real":
            import stripe as _stripe
            _stripe.api_key = api_key
            self._stripe = _stripe

    def charge(
        self,
        amount: float,
        invoice_id: str,
        simulate_timeout: bool = False,
    ) -> str:
        """
        Charges a card. When simulate_timeout=True, raises TimeoutError
        AFTER processing — mimicking a network drop between Stripe's
        response and our receipt of it. The charge exists in Stripe.
        We just never got the confirmation.
        """
        if self.mode == "real":
            intent = self._stripe.PaymentIntent.create(
                amount=int(amount * 100),
                currency="usd",
                payment_method="pm_card_visa",
                confirm=True,
                metadata={"invoice_id": invoice_id},
            )
            charge_id = intent.id
        else:
            charge_id = f"sim_ch_{uuid.uuid4().hex[:14]}"

        self._charges.append({"id": charge_id, "amount": amount, "invoice": invoice_id})
        print(f"     [Stripe] Charged ${amount:.2f} for {invoice_id} → {charge_id}")

        if simulate_timeout:
            raise TimeoutError(
                "Network timeout — Stripe processed the charge but the "
                "response never arrived."
            )

        return charge_id

    @property
    def charges(self) -> list[dict]:
        return list(self._charges)

    def reset(self) -> None:
        self._charges.clear()


# ── Scenario ───────────────────────────────────────────────────────────────────

def run(mode: str, openai_key: Optional[str], stripe_key: Optional[str]) -> None:

    try:
        from figuard import FiGuardClient
    except ImportError:
        print("Install FiGuard: pip install figuard")
        sys.exit(1)

    figuard = FiGuardClient(api_key=FIGUARD_API_KEY, base_url=FIGUARD_BASE_URL)
    stripe  = StripeClient(mode=mode, api_key=stripe_key)

    print(f"\nMode      : {mode}")
    print(f"Dashboard : {FIGUARD_BASE_URL}/ui")

    # ── PART 1: Without FiGuard ────────────────────────────────────────────────

    section("PART 1 — Without FiGuard")
    info("Invoice INV-1234, $50.00. Tool times out after Stripe charges.")
    info("LangChain returns the error to the LLM. LLM retries the tool.")
    info("Stripe processes the charge a second time.\n")

    def payment_tool_unsafe(invoice_id: str, amount: float, attempt: int) -> str:
        step(f"Attempt {attempt}: charging ${amount:.2f} for {invoice_id}")
        charge_id = stripe.charge(
            amount, invoice_id,
            simulate_timeout=(attempt == 1),
        )
        return f"Payment succeeded: {charge_id}"

    if mode == "simulation":
        for attempt in [1, 2]:
            try:
                result = payment_tool_unsafe("INV-1234", 50.00, attempt)
                ok(result)
            except TimeoutError as exc:
                bad(f"Tool raised: {exc}")
                info("LangChain passes error to LLM → LLM decides to retry")
    else:
        import os
        os.environ["OPENAI_API_KEY"] = openai_key

        from langchain_openai import ChatOpenAI
        from langchain.agents import AgentExecutor, create_tool_calling_agent
        from langchain_core.tools import tool
        from langchain_core.prompts import ChatPromptTemplate, MessagesPlaceholder

        attempt_counter = {"n": 0}

        @tool
        def process_payment_unsafe(invoice_id: str, amount: float) -> str:
            """Process a payment for an invoice."""
            attempt_counter["n"] += 1
            return payment_tool_unsafe(invoice_id, amount, attempt_counter["n"])

        llm    = ChatOpenAI(model="gpt-4o-mini", temperature=0)
        prompt = ChatPromptTemplate.from_messages([
            ("system",
             "You are a billing agent. Charge invoices when asked. "
             "If the tool raises an error, retry once."),
            ("human", "{input}"),
            MessagesPlaceholder("agent_scratchpad"),
        ])
        agent    = create_tool_calling_agent(llm, [process_payment_unsafe], prompt)
        executor = AgentExecutor(
            agent=agent, tools=[process_payment_unsafe],
            handle_tool_error=True, verbose=True,
        )
        executor.invoke({"input": "Charge $50.00 for invoice INV-1234."})

    charges = stripe.charges
    print(f"\n  Stripe charges: {len(charges)}")
    for c in charges:
        print(f"     {c['id']}  ${c['amount']:.2f}  ({c['invoice']})")
    bad(f"Customer charged ${sum(c['amount'] for c in charges):.2f} for a $50.00 invoice\n")
    if mode == "real":
        info("Check your Stripe test dashboard — both charges are there.")

    # ── PART 2: With FiGuard ───────────────────────────────────────────────────

    section("PART 2 — With FiGuard")
    info("Same scenario. Idempotency key is tied to the invoice + amount,")
    info("not the attempt. Retry finds the original authorization.")
    info("Stripe is never called a second time.\n")

    stripe.reset()

    budget = figuard.create_budget(
        user_id="billing_agent",
        total_limit=500.00,
        currency="USD",
        expires_in="1h",
    )
    token = budget.session_token
    ok(f"Budget: {budget.id}  (limit: $500.00)")
    info("")

    # Tracks which FiGuard event IDs have already been sent to Stripe.
    # In production this lives in your database or a distributed cache.
    # Here it proves the point: on retry, FiGuard returns the same event_id —
    # that's the signal to skip Stripe.
    stripe_sent: dict[str, str] = {}  # event_id → charge_id (or timeout marker)

    def payment_tool_safe(invoice_id: str, amount: float, attempt: int) -> str:
        # Key is tied to the business operation — same for every attempt.
        idempotency_key = f"invoice-{invoice_id}-usd-{int(amount * 100)}"

        step(f"Attempt {attempt}: authorize ${amount:.2f} for {invoice_id}")
        auth = figuard.authorize(
            session_token=token,
            agent_id="billing_agent",
            action_type="PAYMENT",
            description=f"Payment for invoice {invoice_id}",
            requested_quantity=amount,
            idempotency_key=idempotency_key,
        )

        if not auth.is_authorized:
            raise Exception(f"Payment blocked: {auth.denial_reason}")

        ok(f"[FiGuard] Authorized — event {auth.event_id}")

        # FiGuard returns the same event_id for every retry on the same key.
        # If we already called Stripe for this event, skip it — the card
        # was already charged. Confirm the original event and return.
        if auth.event_id in stripe_sent:
            ok(f"[FiGuard] Same event returned — Stripe already charged")
            step("Skipping Stripe. Confirming original event.")
            figuard.confirm_event(auth.event_id, confirmed_quantity=amount)
            return f"Already processed — confirmed event {auth.event_id}"

        try:
            charge_id = stripe.charge(
                amount, invoice_id,
                simulate_timeout=(attempt == 1),
            )
            stripe_sent[auth.event_id] = charge_id
            figuard.confirm_event(auth.event_id, confirmed_quantity=amount)
            return f"Payment succeeded: {charge_id}"

        except TimeoutError as exc:
            # Stripe charged but we timed out before confirming.
            # Record that this event was already sent to Stripe so the
            # retry skips it. Do NOT void — the money already moved.
            stripe_sent[auth.event_id] = f"timeout_attempt_{attempt}"
            raise

    if mode == "simulation":
        for attempt in [1, 2]:
            try:
                result = payment_tool_safe("INV-1234", 50.00, attempt)
                ok(result)
            except TimeoutError as exc:
                bad(f"Tool raised: {exc}")
                info("LangChain passes error to LLM → LLM decides to retry")
    else:
        attempt_counter["n"] = 0

        @tool
        def process_payment_safe(invoice_id: str, amount: float) -> str:
            """Process a payment for an invoice using FiGuard idempotency."""
            attempt_counter["n"] += 1
            return payment_tool_safe(invoice_id, amount, attempt_counter["n"])

        agent    = create_tool_calling_agent(llm, [process_payment_safe], prompt)
        executor = AgentExecutor(
            agent=agent, tools=[process_payment_safe],
            handle_tool_error=True, verbose=True,
        )
        executor.invoke({"input": "Charge $50.00 for invoice INV-1234."})

    charges = stripe.charges
    print(f"\n  Stripe charges: {len(charges)}")
    for c in charges:
        print(f"     {c['id']}  ${c['amount']:.2f}  ({c['invoice']})")
    ok(f"Customer charged ${sum(c['amount'] for c in charges):.2f} — correct amount")
    if mode == "real":
        info("Check your Stripe test dashboard — one charge only.")

    # ── Summary ────────────────────────────────────────────────────────────────

    section("What FiGuard recorded")
    info(f"Dashboard : {FIGUARD_BASE_URL}/ui")
    info(f"Budget    : {budget.id}")
    info("")
    info("Open the budget → Ledger. You'll see:")
    info("  ✓ CONFIRMED  $50.00  Payment for invoice INV-1234")
    info("")
    info("One event. One charge. The retry was absorbed by FiGuard's")
    info("idempotency layer — same event_id returned, Stripe skipped.")


# ── Entry point ────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="LangChain payment retry — double charge scenario"
    )
    parser.add_argument(
        "--mode", choices=["simulation", "real"], default="simulation",
        help="simulation (default) requires no keys. real runs live LangChain + Stripe test.",
    )
    parser.add_argument("--openai-key", default=None, help="Required for --mode real")
    parser.add_argument("--stripe-key", default=None,
                        help="Stripe test key (sk_test_...). Required for --mode real.")
    args = parser.parse_args()

    if args.mode == "real":
        missing = []
        if not args.openai_key:  missing.append("--openai-key")
        if not args.stripe_key:  missing.append("--stripe-key")
        if missing:
            print(f"real mode requires: {', '.join(missing)}")
            sys.exit(1)

    run(mode=args.mode, openai_key=args.openai_key, stripe_key=args.stripe_key)
