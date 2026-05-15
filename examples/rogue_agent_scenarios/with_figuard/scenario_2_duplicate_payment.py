"""
Scenario 2 — The Duplicate Invoice Payment (WITH FiGuard)

FiGuard's idempotency key deduplicates retries at the authorization layer.
A retry with the same key returns the original authorization event —
same event_id, same decision. The payment processor is only called once.

Run against the sandbox — no local setup required:
    pip install figuard
    python scenario_2_duplicate_payment.py
"""

from figuard import FiGuardClient

figuard = FiGuardClient(
    api_key="sb_live_demo",
    base_url="https://figuard-sandbox-1.onrender.com",
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

# Attempt 1 — authorized, but response is lost in transit (simulated timeout)
print("Attempt 1: authorizing...")
auth1 = figuard.authorize(
    session_token=budget.session_token,
    agent_id="ap_agent",
    action_type="PAYMENT",
    description=f"Vendor payment {invoice_id}",
    requested_quantity=amount,
    idempotency_key=f"invoice-{invoice_id}",
)
print(f"Attempt 1: {auth1.decision} — event {auth1.event_id}")
print("           ← network timeout: agent never received this response")
print()

# Agent retries with the same idempotency key
print("Network timeout. Retrying...")
print()
auth2 = figuard.authorize(
    session_token=budget.session_token,
    agent_id="ap_agent",
    action_type="PAYMENT",
    description=f"Vendor payment {invoice_id}",
    requested_quantity=amount,
    idempotency_key=f"invoice-{invoice_id}",  # same key → same event returned
)
print(f"Attempt 2: {auth2.decision} — event {auth2.event_id}")
print()

print(f"Same event returned: {auth1.event_id == auth2.event_id}")
print(f"Amount authorized:   ${amount:.2f}  (not ${amount * 2:.2f})")
print()

if auth1.is_authorized:
    # Confirm once — the retry didn't create a second reservation
    figuard.confirm_event(auth1.event_id, confirmed_quantity=amount)
    print(f"Confirmed: ${amount:.2f} against event {auth1.event_id}")
    print()

print("Duplicate prevented at authorization time.")
print("Payment processor called once. Finance reconciliation is clean.")
