# Rogue Agent Scenarios

Five real AI agent failure modes — each as a single runnable Python file showing the problem and the FiGuard fix, plus an interactive Colab notebook.

```bash
pip install figuard anthropic
python scenario_1_infinite_loop.py
```

---

## Scenarios

| # | Problem | What goes wrong | FiGuard fix | Colab |
|---|---------|-----------------|-------------|-------|
| 1 | **Infinite quality loop** | Agent loops 847 times overnight; score never reaches threshold | Budget ceiling stops it at 250 iterations | [![Open in Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/figuard/figuard-notebooks/blob/main/agent-incidents/01_infinite_loop.ipynb) |
| 2 | **Duplicate invoice payment** | Network timeout → retry → same invoice paid twice | Idempotency key returns original event on retry | [![Open in Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/figuard/figuard-notebooks/blob/main/agent-incidents/02_duplicate_payment.ipynb) |
| 3 | **Concurrent fleet overspend** | 10 agents read same $1k balance simultaneously; all approved; $2k spent | SERIALIZABLE isolation — exactly 5 authorized, budget never exceeded | [![Open in Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/figuard/figuard-notebooks/blob/main/agent-incidents/03_concurrent_overspend.ipynb) |
| 4 | **Rogue sub-agent** | One agent hallucinates, loops on search API, exhausts entire fleet budget | Delegation token caps researcher at $200; analyst and writer unaffected | [![Open in Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/figuard/figuard-notebooks/blob/main/agent-incidents/04_rogue_subagent_fleet.ipynb) |
| 5 | **Category violation** | Travel agent charges hotel to flight allocation; discrepancy found in month-end review | CATEGORY_CONSTRAINED enforcement blocks wrong-category spend at authorization time | [![Open in Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/figuard/figuard-notebooks/blob/main/agent-incidents/05_category_violation.ipynb) |

---

## File Structure

Each scenario is a single Python file. The docstring explains the incident and the fix. The code demonstrates both the failure mode and the FiGuard solution.

```
rogue_agent_scenarios/
├── README.md
├── scenario_1_infinite_loop.py        ← unconstrained loop → budget ceiling
├── scenario_2_duplicate_payment.py    ← duplicate charge → idempotency key
├── scenario_3_concurrent_overspend.py ← race condition → serializable isolation
├── scenario_4_rogue_subagent_fleet.py ← fleet drain → delegation token caps
└── scenario_5_category_violation.py   ← category contamination → constrained allocations
```

---

## Running

All scripts use the live sandbox. No account or local server needed.

```bash
pip install figuard anthropic

python scenario_1_infinite_loop.py
python scenario_2_duplicate_payment.py
python scenario_3_concurrent_overspend.py
python scenario_4_rogue_subagent_fleet.py
python scenario_5_category_violation.py
```

After running, open the dashboard to see your spend tree:
https://figuard-sandbox-1.onrender.com/ui

---

## Sandbox Details

- **API key:** `sb_live_demo`
- **Base URL:** `https://figuard-sandbox-1.onrender.com`
- **Dashboard:** `https://figuard-sandbox-1.onrender.com/ui`

Data resets periodically. Safe to run repeatedly.
