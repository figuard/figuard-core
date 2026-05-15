# Rogue Agent Scenarios

Five real AI agent failure modes — each with a runnable before/after.

The `without_figuard/` scripts show what goes wrong. The `with_figuard/` scripts
show the same scenario with FiGuard enforcing a budget. All `with_figuard`
examples run against the live sandbox — no local setup required.

```bash
pip install figuard anthropic
python with_figuard/scenario_1_infinite_loop.py
```

---

## Scenarios

| # | Problem | What goes wrong | FiGuard fix |
|---|---------|-----------------|-------------|
| 1 | **Infinite quality loop** | Agent loops 847 times overnight; score never reaches threshold | Budget ceiling stops it at 250 iterations |
| 2 | **Duplicate invoice payment** | Network timeout → retry → same invoice paid twice | Idempotency key returns original event on retry |
| 3 | **Concurrent fleet overspend** | 10 agents read same $1k balance simultaneously; all approved; $2k spent | SERIALIZABLE isolation — exactly 5 authorized, budget never exceeded |
| 4 | **Rogue sub-agent** | One agent hallucinates, loops on search API, exhausts entire fleet budget | Delegation token caps researcher at $200; analyst and writer unaffected |
| 5 | **Category violation** | Travel agent charges hotel to flight allocation; discrepancy found in month-end review | CATEGORY_CONSTRAINED enforcement blocks wrong-category spend at authorization time |

---

## File Structure

```
rogue_agent_scenarios/
├── README.md
├── without_figuard/
│   ├── scenario_1_infinite_loop.py        ← loops until killed
│   ├── scenario_2_duplicate_payment.py    ← simulates duplicate charge
│   ├── scenario_3_concurrent_overspend.py ← race condition on shared balance
│   ├── scenario_4_rogue_subagent_fleet.py ← one agent takes down the fleet
│   └── scenario_5_category_violation.py   ← hotel silently charged to flight budget
└── with_figuard/
    ├── scenario_1_infinite_loop.py        ← hard ceiling, clean stop
    ├── scenario_2_duplicate_payment.py    ← idempotent retry, one charge
    ├── scenario_3_concurrent_overspend.py ← exactly 5/10 approved, $1k not exceeded
    ├── scenario_4_rogue_subagent_fleet.py ← researcher capped, fleet completes
    └── scenario_5_category_violation.py   ← denial + correct booking both shown
```

---

## Running the Scenarios

All `with_figuard` scripts use the live sandbox. No account or local server needed.

```bash
pip install figuard anthropic

# See the problem
python without_figuard/scenario_1_infinite_loop.py

# See FiGuard stop it
python with_figuard/scenario_1_infinite_loop.py
```

After running, open the dashboard to see your spend tree:
https://figuard-sandbox-1.onrender.com/ui

---

## Sandbox Details

- **API key:** `sb_live_demo`
- **Base URL:** `https://figuard-sandbox-1.onrender.com`
- **Dashboard:** `https://figuard-sandbox-1.onrender.com/ui`

Data resets periodically. Safe to run repeatedly.
