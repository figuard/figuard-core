"""
Scenario 1 — The Infinite Quality Loop (WITHOUT FiGuard)

The problem: a quality-checking agent loops until score exceeds 0.95.
The score oscillates between 0.82 and 0.91 and never reaches the threshold.
The agent ran 847 iterations overnight before someone noticed.

Run this to see what uncontrolled looping looks like.
The demo stops at 20 iterations — in production it would never stop.
"""

import anthropic

client = anthropic.Anthropic()


def quality_check_loop(content: str) -> None:
    iteration = 0
    total_cost = 0.0

    print("Starting quality check loop...")
    print("Target: score > 0.95")
    print("(Demo stops at 20 — in production this ran 847 times)")
    print()

    while True:
        iteration += 1
        cost_per_call = 0.02

        response = client.messages.create(
            model="claude-haiku-4-5-20251001",
            max_tokens=100,
            messages=[{
                "role": "user",
                "content": f"Rate this content quality 0-1: {content[:100]}",
            }],
        )

        # Score oscillates — never reaches 0.95
        score = 0.85 + (iteration % 7) * 0.01
        total_cost += cost_per_call

        print(
            f"Iteration {iteration:4d}: score={score:.2f}  "
            f"cost=${cost_per_call:.2f}  total=${total_cost:.2f}"
        )

        if score > 0.95:
            print(f"\nQuality threshold reached at iteration {iteration}.")
            break

        if iteration >= 20:
            print()
            print("--- DEMO STOPPED AT 20 ITERATIONS ---")
            print(f"Score never reached 0.95 (max was 0.91).")
            print(f"In production this ran 847 iterations:")
            print(f"  Cost: ${847 * cost_per_call:.2f}")
            print(f"  Time: ~14 hours")
            print()
            print("Nothing stopped it. No ceiling. No alert.")
            break


quality_check_loop("Example content that needs quality checking before publication.")
