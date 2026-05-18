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

figuard = FiGuardClient(
    api_key="ab_live_demo",  # sandbox: use "sb_live_demo"
    base_url="http://localhost:8080",  # sandbox: use "https://figuard-sandbox-1.onrender.com"
)

budget = figuard.create_budget(
    user_id="ap_agent",
    total_limit=50_000.00,
    currency="USD",
    expires_in="1h",
    entity_dedup_enabled=True,
)

invoice_id = "INV-2026-0342"
amount = 1500.00

print(f"Processing invoice {invoice_id} for ${amount:.2f}")
print()

# Attempt 1 — authorized, response lost in transit
print("Attempt 1: authorizing...")
auth1 = figuard.authorize(
    session_token=budget.primary_token.session_token,
    agent_id="ap_agent",
    action_type="PAYMENT",
    description=f"Vendor payment {invoice_id}",
    requested_quantity=amount,
    idempotency_key=f"invoice-{invoice_id}",
)
print(f"  {auth1.decision} — event {auth1.event_id}")
print("  ← network timeout: agent never received this response")
print()

# Retry — same idempotency key returns original event
print("Network timeout. Retrying with same idempotency key...")
auth2 = figuard.authorize(
    session_token=budget.primary_token.session_token,
    agent_id="ap_agent",
    action_type="PAYMENT",
    description=f"Vendor payment {invoice_id}",
    requested_quantity=amount,
    idempotency_key=f"invoice-{invoice_id}",
)
print(f"  {auth2.decision} — event {auth2.event_id}")
print()

print(f"Same event returned: {auth1.event_id == auth2.event_id}")
print(f"Amount authorized:   ${amount:.2f}  (not ${amount * 2:.2f})")

if auth1.is_authorized:
    figuard.confirm_event(auth1.event_id, confirmed_quantity=amount)
    print(f"Confirmed once:      ${amount:.2f}")

print()
print("✓ Duplicate prevented. Payment processor called once.")
