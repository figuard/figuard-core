#!/usr/bin/env python3
"""
Scenario: LangGraph Research Loop → Unconstrained Cost Runup

THE PROBLEM
-----------
LangGraph's power is conditional edges: the LLM decides when the graph
moves to the next node. A research agent that loops through
search → analyze → search is correct by design. The LLM keeps searching
until it's satisfied. On a clear query it stops at 5 iterations. On an
ambiguous one it runs 40+. Same graph, 8x the cost.

You can add max_iterations=20 but that's a count ceiling, not a cost
ceiling. A session with 20 cheap searches costs the same as one with
5 expensive ones — the count guard doesn't help you when per-call cost
varies (Serper vs Perplexity vs a paid financial data API).

THE FIX
-------
FiGuard wraps the search tool. Each call authorizes the per-call cost
before it fires. When the session budget is exhausted the authorization
is denied, the tool raises BudgetExhausted, and the graph exits the loop
with whatever it has already gathered. Cost is bounded by dollars, not
by iteration count.

MODES
-----
simulation  No API keys needed. Search tool returns pre-scripted results.
            The loop iteration count and per-call cost are configurable
            at the top of this file. FiGuard runs against the live sandbox.

real        Uses a real LangGraph agent (OpenAI) with a real web search
            tool (Serper). Requires OPENAI_API_KEY and SERPER_API_KEY.
            Each search call costs ~$0.001 against your Serper quota.

USAGE
-----
python langgraph_research_loop.py                        # simulation
python langgraph_research_loop.py --mode real \\
    --openai-key sk-... --serper-key abc123              # real

DASHBOARD
---------
https://figuard-sandbox-g1ha.onrender.com/ui
Open the printed budget ID. Watch the reserved quantity climb each
iteration and hit the ceiling in Part 2.
"""

import argparse
import sys
import uuid
from typing import Annotated, Optional, TypedDict

# ── Scenario tuning ────────────────────────────────────────────────────────────
# How many search iterations the simulated agent will want to run.
SIMULATED_ITERATIONS = 30

# Cost FiGuard charges per search call (in USD).
COST_PER_SEARCH = 0.01

# Budget ceiling for Part 2. At $0.01/call this allows 20 iterations.
BUDGET_LIMIT = 0.20

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


# ── Search tool ────────────────────────────────────────────────────────────────

class SearchClient:
    """Real Serper search or a simulator that returns canned results."""

    def __init__(self, mode: str, api_key: Optional[str] = None):
        self.mode = mode
        self.call_count = 0
        if mode == "real":
            import requests
            self._requests = requests
            self._api_key  = api_key

    def search(self, query: str) -> str:
        self.call_count += 1
        if self.mode == "real":
            resp = self._requests.post(
                "https://google.serper.dev/search",
                headers={"X-API-KEY": self._api_key, "Content-Type": "application/json"},
                json={"q": query},
                timeout=10,
            )
            resp.raise_for_status()
            snippets = [r.get("snippet", "") for r in resp.json().get("organic", [])[:3]]
            return " | ".join(snippets) or "No results."
        else:
            # Simulated: always returns a plausible-but-inconclusive result
            # so the agent keeps searching (realistic for ambiguous queries).
            return (
                f"[sim result {self.call_count}] Found partial information about '{query}'. "
                f"Several sources conflict. Further research recommended."
            )

    def reset(self) -> None:
        self.call_count = 0


# ── Simulation: pre-scripted agent loop ───────────────────────────────────────

class BudgetExhausted(Exception):
    """Raised by the search tool when FiGuard denies the authorization."""
    pass


def run_simulated_loop(
    search_client: SearchClient,
    figuard=None,
    token: Optional[str] = None,
    label: str = "",
) -> tuple[int, float]:
    """
    Runs a simulated research loop. Returns (iterations_run, total_cost).
    If figuard + token are provided, each search is authorized first.
    """
    total_cost = 0.0
    query = "impact of AI agents on enterprise software spending 2024"

    for i in range(1, SIMULATED_ITERATIONS + 1):
        if figuard and token:
            auth = figuard.authorize(
                session_token=token,
                agent_id="research_agent",
                action_type="SEARCH",
                description=f"Web search iteration {i}: {query[:60]}",
                requested_quantity=COST_PER_SEARCH,
                idempotency_key=f"{label}-search-iter-{i}",
            )
            if not auth.is_authorized:
                bad(f"[FiGuard] Search {i} denied — {auth.denial_reason}")
                info(f"Agent exits loop with results from {i - 1} searches.")
                return i - 1, total_cost

            step(f"Search {i:>2}: authorized ${COST_PER_SEARCH:.2f} (event {auth.event_id[:8]}…)")
        else:
            step(f"Search {i:>2}: calling search API…")

        result = search_client.search(query)
        total_cost += COST_PER_SEARCH

        if figuard and token:
            figuard.confirm_event(auth.event_id, confirmed_quantity=COST_PER_SEARCH)

        # Simulated LLM decision: "do I have enough?" — always no until forced to stop.
        # In real mode this is the LLM's actual conditional edge decision.
        info(f"           result: {result[:80]}…")
        info(f"           LLM: insufficient — keep searching")

    return SIMULATED_ITERATIONS, total_cost


# ── Real LangGraph agent ───────────────────────────────────────────────────────

def build_langgraph_agent(
    openai_key: str,
    search_client: SearchClient,
    figuard=None,
    token: Optional[str] = None,
    label: str = "",
):
    """
    Builds a minimal LangGraph research agent.
    The search tool is wrapped with FiGuard if figuard + token are provided.
    """
    import os
    os.environ["OPENAI_API_KEY"] = openai_key

    from langchain_openai import ChatOpenAI
    from langchain_core.messages import HumanMessage, AIMessage, ToolMessage
    from langchain_core.tools import tool
    from langgraph.graph import StateGraph, END

    class AgentState(TypedDict):
        messages: Annotated[list, lambda x, y: x + y]
        search_count: int
        done: bool

    iteration_counter = {"n": 0}

    @tool
    def web_search(query: str) -> str:
        """Search the web for information on a topic."""
        iteration_counter["n"] += 1
        i = iteration_counter["n"]

        if figuard and token:
            auth = figuard.authorize(
                session_token=token,
                agent_id="research_agent",
                action_type="SEARCH",
                description=f"Web search iteration {i}: {query[:60]}",
                requested_quantity=COST_PER_SEARCH,
                idempotency_key=f"{label}-search-iter-{i}",
            )
            if not auth.is_authorized:
                raise BudgetExhausted(
                    f"Search budget exhausted after {i - 1} searches "
                    f"(${(i - 1) * COST_PER_SEARCH:.2f} spent). "
                    f"Reason: {auth.denial_reason}"
                )
            step(f"Search {i:>2}: authorized (event {auth.event_id[:8]}…)")

        result = search_client.search(query)

        if figuard and token:
            figuard.confirm_event(auth.event_id, confirmed_quantity=COST_PER_SEARCH)

        return result

    llm = ChatOpenAI(model="gpt-4o-mini", temperature=0)
    llm_with_tools = llm.bind_tools([web_search])

    def agent_node(state: AgentState) -> AgentState:
        response = llm_with_tools.invoke(state["messages"])
        return {"messages": [response], "search_count": state["search_count"], "done": False}

    def tool_node(state: AgentState) -> AgentState:
        last = state["messages"][-1]
        results = []
        for call in last.tool_calls:
            try:
                result = web_search.invoke(call["args"])
            except BudgetExhausted as e:
                result = f"BUDGET_EXHAUSTED: {e}"
                return {"messages": [ToolMessage(content=result, tool_call_id=call["id"])],
                        "search_count": state["search_count"] + 1, "done": True}
            results.append(ToolMessage(content=result, tool_call_id=call["id"]))
        return {"messages": results,
                "search_count": state["search_count"] + len(results), "done": False}

    def should_continue(state: AgentState) -> str:
        if state.get("done"):
            return END
        last = state["messages"][-1]
        if hasattr(last, "tool_calls") and last.tool_calls:
            return "tools"
        return END

    graph = StateGraph(AgentState)
    graph.add_node("agent", agent_node)
    graph.add_node("tools", tool_node)
    graph.set_entry_point("agent")
    graph.add_conditional_edges("agent", should_continue, {"tools": "tools", END: END})
    graph.add_edge("tools", "agent")

    return graph.compile(), iteration_counter


# ── Scenario runner ────────────────────────────────────────────────────────────

def run(mode: str, openai_key: Optional[str], serper_key: Optional[str]) -> None:

    try:
        from figuard import FiGuardClient
    except ImportError:
        print("Install FiGuard: pip install figuard")
        sys.exit(1)

    figuard = FiGuardClient(api_key=FIGUARD_API_KEY, base_url=FIGUARD_BASE_URL)
    search  = SearchClient(mode=mode, api_key=serper_key)

    # Create the Part 2 budget now so the sandbox is already warm when
    # Part 2 starts. Part 1 makes no FiGuard calls and can take a while —
    # creating the budget first keeps the connection alive.
    print("\nConnecting to FiGuard sandbox…", end=" ", flush=True)
    run_id = uuid.uuid4().hex[:8]
    budget = figuard.create_budget(
        user_id="research_agent",
        total_limit=BUDGET_LIMIT,
        currency="USD",
        expires_in="1h",
    )
    print("ready.")

    print(f"\nMode            : {mode}")
    print(f"Simulated iters : {SIMULATED_ITERATIONS}  (agent keeps searching until stopped)")
    print(f"Cost per search : ${COST_PER_SEARCH:.3f}")
    print(f"Budget ceiling  : ${BUDGET_LIMIT:.2f}  ({int(BUDGET_LIMIT / COST_PER_SEARCH)} searches)")
    print(f"Dashboard       : {FIGUARD_BASE_URL}/ui")

    # ── PART 1: Without FiGuard ────────────────────────────────────────────────

    section("PART 1 — Without FiGuard")
    info(f"Query: 'impact of AI agents on enterprise software spending 2024'")
    info(f"The LLM controls the loop. It never decides it has enough.")
    info(f"The graph runs all {SIMULATED_ITERATIONS} iterations.\n")

    search.reset()

    if mode == "simulation":
        iterations, cost = run_simulated_loop(search_client=search)
    else:
        graph, counter = build_langgraph_agent(
            openai_key=openai_key, search_client=search
        )
        graph.invoke({
            "messages": [HumanMessage(content=(
                "Research the impact of AI agents on enterprise software spending in 2024. "
                "Keep searching until you have comprehensive coverage from multiple angles."
            ))],
            "search_count": 0,
            "done": False,
        })
        iterations = counter["n"]
        cost = iterations * COST_PER_SEARCH

    print()
    bad(f"{iterations} search calls — ${cost:.2f} spent")
    bad(f"No ceiling. Cost grows with query complexity, not your budget.")

    # ── PART 2: With FiGuard ───────────────────────────────────────────────────

    section("PART 2 — With FiGuard")
    info(f"Same query, same graph. ${BUDGET_LIMIT:.2f} budget for this research session.")
    info(f"FiGuard authorizes each search call. Loop exits when budget is hit.\n")

    search.reset()
    token = budget.session_token
    ok(f"Budget: {budget.id}  (limit: ${BUDGET_LIMIT:.2f})")
    info("")

    if mode == "simulation":
        iterations, cost = run_simulated_loop(
            search_client=search,
            figuard=figuard,
            token=token,
            label=run_id,
        )
    else:
        from langchain_core.messages import HumanMessage
        graph, counter = build_langgraph_agent(
            openai_key=openai_key,
            search_client=search,
            figuard=figuard,
            token=token,
            label=run_id,
        )
        graph.invoke({
            "messages": [HumanMessage(content=(
                "Research the impact of AI agents on enterprise software spending in 2024. "
                "Keep searching until you have comprehensive coverage from multiple angles."
            ))],
            "search_count": 0,
            "done": False,
        })
        iterations = counter["n"]
        cost = iterations * COST_PER_SEARCH

    print()
    ok(f"{iterations} search calls — ${cost:.2f} spent")
    ok(f"Loop stopped at budget ceiling. Graph exited with partial results.")
    info(f"Without FiGuard: {SIMULATED_ITERATIONS} calls, "
         f"${SIMULATED_ITERATIONS * COST_PER_SEARCH:.2f}. "
         f"Saving: ${(SIMULATED_ITERATIONS - iterations) * COST_PER_SEARCH:.2f}")

    # ── Budget summary ─────────────────────────────────────────────────────────

    b = figuard.get_budget(budget.id)

    section("What FiGuard recorded")
    info(f"Dashboard : {FIGUARD_BASE_URL}/ui")
    info(f"Budget    : {budget.id}")
    info("")
    ok(f"Spent     : ${b.quantity_spent:.2f} of ${BUDGET_LIMIT:.2f}")
    ok(f"Remaining : ${b.available_quantity:.2f}")
    info("")
    info("Open the budget → Ledger. You'll see one CONFIRMED event per")
    info("search call, then one final DENIED / INSUFFICIENT_FUNDS.")
    info("That denial is where the loop stopped.")
    info("")
    info("Open Overview → the spend bar is at 100%. Status: EXHAUSTED.")
    info("No search call slipped through after the ceiling was hit.")


# ── Entry point ────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="LangGraph research loop — unconstrained cost runup scenario"
    )
    parser.add_argument(
        "--mode", choices=["simulation", "real"], default="simulation",
        help="simulation (default) requires no keys.",
    )
    parser.add_argument("--openai-key", default=None, help="Required for --mode real")
    parser.add_argument("--serper-key", default=None,
                        help="Serper API key for real web search. Required for --mode real.")
    args = parser.parse_args()

    if args.mode == "real":
        missing = []
        if not args.openai_key: missing.append("--openai-key")
        if not args.serper_key: missing.append("--serper-key")
        if missing:
            print(f"real mode requires: {', '.join(missing)}")
            sys.exit(1)

    run(mode=args.mode, openai_key=args.openai_key, serper_key=args.serper_key)
