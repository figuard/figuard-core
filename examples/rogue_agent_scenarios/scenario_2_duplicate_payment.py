"""
Scenario 2 — The Duplicate Invoice Payment

THE INCIDENT
An AP agent processed an invoice. Network timeout on the first attempt.
The agent retried. The same invoice was paid twice — $1,500 charged twice.
Finance found the duplicate three weeks later during reconciliation.

THE FIX
An idempotency key tied to the invoice ID. A retry with the same key returns
the original authorization event — same event_id, same decision. The payment
processor is only called once regardless of how many retries occur.

Run:
    pip install figuard
    python scenario_2_duplicate_payment.py
"""

from figuard import FiGuardClient

# ── ANSI colours ──────────────────────────────────────────────────────────────
RED    = "\033[91m"
GREEN  = "\033[32m"
YELLOW = "\033[93m"
BOLD   = "\033[1m"
DIM    = "\033[2m"
RESET  = "\033[0m"

figuard = FiGuardClient(
    api_key="fg_live_demo",            # sandbox: use "sb_live_demo"
    base_url="http://localhost:8080",  # sandbox: use "https://figuard-sandbox-1.onrender.com"
)

invoice_id = "INV-2026-0342"
amount     = 1500.00

# ── WITHOUT FIGUARD ───────────────────────────────────────────────────────────
print(f"\n{BOLD}{RED}━━━  WITHOUT FIGUARD  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━{RESET}")
print(f"  Processing invoice {invoice_id} for ${amount:,.2f}")
print()
print(f"  {RED}Attempt 1: AUTHORIZED — ${amount:,.2f} charged{RESET}")
print(f"  {DIM}  ← network timeout: response lost in transit{RESET}")
print(f"  {RED}Retry:     AUTHORIZED — ${amount:,.2f} charged again{RESET}")
print()
print(f"  {RED}✗  Vendor paid twice — ${amount * 2:,.2f} total charged{RESET}")
print(f"  {DIM}  Finance caught the duplicate 3 weeks later{RESET}")

# ── WITH FIGUARD ──────────────────────────────────────────────────────────────
print(f"\n{BOLD}{GREEN}━━━  WITH FIGUARD  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━{RESET}")

budget = figuard.create_budget(
    user_id="ap_agent",
    total_limit=50_000.00,
    currency="USD",
    expires_in="1h",
    entity_dedup_enabled=True,
)

print(f"  Invoice: {BOLD}{invoice_id}{RESET}  |  Amount: ${amount:,.2f}")
print()

# Attempt 1 — authorized, response lost in transit
print(f"  Attempt 1: authorizing...")
auth1 = figuard.authorize(
    session_token=budget.primary_token.session_token,
    agent_id="ap_agent",
    action_type="PAYMENT",
    description=f"Vendor payment {invoice_id}",
    requested_quantity=amount,
    idempotency_key=f"invoice-{invoice_id}",
)
print(f"  {GREEN}✓ {auth1.decision} — event {auth1.event_id}{RESET}")
print(f"  {DIM}  ← network timeout: agent never received this response{RESET}")
print()

# Retry — same idempotency key returns original event
print(f"  {YELLOW}Network timeout. Retrying with same idempotency key...{RESET}")
auth2 = figuard.authorize(
    session_token=budget.primary_token.session_token,
    agent_id="ap_agent",
    action_type="PAYMENT",
    description=f"Vendor payment {invoice_id}",
    requested_quantity=amount,
    idempotency_key=f"invoice-{invoice_id}",
)
print(f"  {GREEN}✓ {auth2.decision} — event {auth2.event_id}  (same event returned){RESET}")
print()

same_event = auth1.event_id == auth2.event_id
print(f"  Same event ID:     {GREEN}{same_event}{RESET}")
print(f"  Amount charged:    {GREEN}${amount:,.2f}{RESET}  (not ${amount * 2:,.2f})")

if auth1.is_authorized:
    figuard.confirm_event(auth1.event_id, confirmed_quantity=amount)
    print(f"  Confirmed once:    {GREEN}${amount:,.2f}{RESET}")

print()
print(f"  {GREEN}✓  Duplicate prevented — payment processor called exactly once{RESET}")
