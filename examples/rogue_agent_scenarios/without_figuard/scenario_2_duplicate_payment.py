"""
Scenario 2 — The Duplicate Invoice Payment (WITHOUT FiGuard)

The problem: AP agent processes an invoice. Network timeout on attempt 1.
Agent retries. Without idempotency the same invoice is paid twice.
Finance found the $1,500 duplicate three weeks later during reconciliation.

This demo simulates the retry flow without a real Stripe key.
"""


def process_payment(invoice_id: str, amount: float, attempt: int) -> dict:
    """Simulate a payment processor call. No idempotency."""
    # In production: stripe.PaymentIntent.create(amount=..., metadata={"invoice_id": invoice_id})
    charge_id = f"ch_{invoice_id}_{attempt}_{'x' * 8}"
    return {"id": charge_id, "amount": amount, "status": "succeeded"}


invoice_id = "INV-2026-0342"
amount = 1500.00

print(f"Processing invoice {invoice_id} for ${amount:.2f}")
print()

# Attempt 1 — succeeds on the server but the response is lost (timeout)
charge_1 = process_payment(invoice_id, amount, attempt=1)
print(f"Attempt 1: sent — charge {charge_1['id']}")
print(f"           ← network timeout: agent never received the response")
print()

# Agent retries — it doesn't know attempt 1 succeeded
charge_2 = process_payment(invoice_id, amount, attempt=2)
print(f"Attempt 2: sent — charge {charge_2['id']}")
print(f"           ← AUTHORIZED again: ${amount:.2f} charged a second time")
print()

print("Result:")
print(f"  Expected:  ${amount:.2f}")
print(f"  Actual:    ${amount * 2:.2f}  (two separate charges)")
print(f"  Duplicate: ${amount:.2f}")
print()
print("Finance discovered the duplicate 3 weeks later during reconciliation.")
print("Dispute resolution took 2 weeks.")
print()
print("Root cause: no idempotency key — retries create new charges.")
