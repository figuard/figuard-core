# Observability — FiGuard Spans in Your Traces

FiGuard emits OpenTelemetry spans for every authorization decision. When your agent runs, FiGuard spans appear as child spans alongside your LLM calls in Langfuse, Jaeger, Honeycomb, Datadog, or any OTEL-compatible backend — so you can see exactly what was authorized, denied, and why, in the same trace as the LLM that triggered it.

---

## What you see

A LangChain agent that books a flight and attempts an over-budget hotel produces this trace:

```
AgentExecutor                                           487ms
├── ChatOpenAI                                          201ms
├── on_tool_start: book_flight
│   └── figuard.authorize                               12ms
│         figuard.agent_id       = "travel_agent"
│         figuard.action_type    = "PURCHASE"
│         figuard.requested_quantity = 280.0
│         figuard.claimed_category   = "flight"
│         figuard.decision       = "AUTHORIZED"
│         figuard.event_id       = "evt_abc123"
│         figuard.approved_quantity  = 280.0
│         figuard.budget_available   = 220.0
└── on_tool_start: book_hotel
    └── figuard.authorize                               8ms   ✗ ERROR
          figuard.agent_id       = "travel_agent"
          figuard.action_type    = "PURCHASE"
          figuard.requested_quantity = 400.0
          figuard.claimed_category   = "hotel"
          figuard.decision       = "DENIED"
          figuard.denial_reason  = "ALLOCATION_EXHAUSTED"
```

The `figuard.authorize` span is a child of the LangChain tool span — it appears exactly where the authorization decision happened in the call graph.

---

## Spans emitted

| Span | When | Key attributes |
|---|---|---|
| `figuard.authorize` | Every `authorize()` call | `decision`, `event_id`, `denial_reason`, `approved_quantity`, `budget_available` |
| `figuard.confirm` | `confirm_event()` | `event_id`, `confirmed_quantity` |
| `figuard.fail` | `fail_event()` | `event_id`, `reason` |
| `figuard.void_tree` | `void_tree()` | `root_event_id`, `voided_count`, `total_quantity_released` |

AUTHORIZED decisions set span status `OK`. DENIED decisions set status `ERROR` with `denial_reason` as the message — so denied authorizations appear as errors in your trace, which is what you want.

---

## Langfuse

Langfuse is the most natural backend for AI agent workloads — it traces LLM calls and tool executions, and FiGuard's financial spans appear alongside them in the same trace.

### Install

```bash
pip install figuard[opentelemetry] opentelemetry-exporter-otlp-proto-http langfuse
```

### Setup

```python
import base64
import os
from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from langfuse.callback import CallbackHandler

LANGFUSE_PUBLIC_KEY = os.environ["LANGFUSE_PUBLIC_KEY"]
LANGFUSE_SECRET_KEY = os.environ["LANGFUSE_SECRET_KEY"]
LANGFUSE_HOST = os.environ.get("LANGFUSE_HOST", "https://cloud.langfuse.com")

# Configure OTEL to export to Langfuse
auth = base64.b64encode(f"{LANGFUSE_PUBLIC_KEY}:{LANGFUSE_SECRET_KEY}".encode()).decode()
exporter = OTLPSpanExporter(
    endpoint=f"{LANGFUSE_HOST}/api/public/otel/v1/traces",
    headers={"Authorization": f"Basic {auth}"},
)
provider = TracerProvider()
provider.add_span_processor(BatchSpanProcessor(exporter))
trace.set_tracer_provider(provider)

# FiGuard picks up the OTEL provider automatically — no extra config needed
from figuard import FiGuardClient
client = FiGuardClient(api_key="sb_live_demo", base_url="https://sandbox.figuard.io")
```

### Full example with LangChain

```python
from langchain_openai import ChatOpenAI
from langchain.agents import AgentExecutor, create_tool_calling_agent
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.tools import tool
from langfuse.callback import CallbackHandler
from figuard.integrations.langchain import FiGuardCallbackHandler

budget = client.create_budget(
    total_limit=500,
    currency="USD",
    expires_in="1h",
    allocations=[
        {"category": "flight", "limit": 300, "enforcement_mode": "CATEGORY_CONSTRAINED",
         "allowed_categories": ["flight"]},
        {"category": "hotel",  "limit": 200, "enforcement_mode": "CATEGORY_CONSTRAINED",
         "allowed_categories": ["hotel"]},
    ],
)

@tool
def book_flight(destination: str, amount: float) -> str:
    """Book a flight. Args: destination (str), amount (float USD)"""
    return f"Flight to {destination} booked for ${amount}"

@tool
def book_hotel(city: str, amount: float) -> str:
    """Book a hotel. Args: city (str), amount (float USD)"""
    return f"Hotel in {city} booked for ${amount}"

llm = ChatOpenAI(model="gpt-4o")
prompt = ChatPromptTemplate.from_messages([
    ("system", "You are a travel booking agent."),
    ("human", "{input}"),
    ("placeholder", "{agent_scratchpad}"),
])
agent = create_tool_calling_agent(llm, [book_flight, book_hotel], prompt)
executor = AgentExecutor(
    agent=agent,
    tools=[book_flight, book_hotel],
    callbacks=[
        FiGuardCallbackHandler(client=client, budget=budget),
        CallbackHandler(),   # Langfuse — captures LLM calls + tool spans
    ],
)

result = executor.invoke({"input": "Book a flight to Rome for $280 and a hotel for $150."})
```

### What appears in Langfuse

Open your Langfuse project → Traces. Each agent run is one trace. Inside it you see:

- **LLM generation spans** — model, prompt tokens, completion tokens, latency
- **Tool spans** — tool name, input, output
- **`figuard.authorize` child spans** — nested under each tool span with the authorization decision, amount, category, and remaining budget

Click a `figuard.authorize` span to see:
- Whether it was `AUTHORIZED` or `DENIED`
- The `denial_reason` if denied (e.g. `ALLOCATION_EXHAUSTED`)
- How much budget remained at the moment of the decision
- The `event_id` — paste this into `GET /events/{id}` to pull the full ledger entry

The active trace ID is forwarded to FiGuard automatically. Filter the FiGuard ledger to a specific trace:

```python
events = list(client.iter_events(budget_id=budget.id, trace_id="<langfuse-trace-id>"))
```

---

## Jaeger (local)

Jaeger is the easiest way to explore FiGuard spans locally without a cloud account.

### Start Jaeger

```bash
docker run --rm -p 16686:16686 -p 4317:4317 jaegertracing/all-in-one:latest
```

Open `http://localhost:16686` to view traces.

### Setup

```bash
pip install figuard[opentelemetry] opentelemetry-exporter-otlp-proto-grpc
```

```python
from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter

exporter = OTLPSpanExporter(endpoint="http://localhost:4317", insecure=True)
provider = TracerProvider()
provider.add_span_processor(BatchSpanProcessor(exporter))
trace.set_tracer_provider(provider)

from figuard import FiGuardClient
client = FiGuardClient(api_key="sb_live_demo", base_url="https://sandbox.figuard.io")
```

Run your agent, then open Jaeger UI → search for service `figuard`. Every `authorize`, `confirm`, `fail`, and `void_tree` call appears as a span. Use the trace view to see them nested under your application spans.

---

## TypeScript

```bash
npm install @opentelemetry/api @opentelemetry/sdk-node @opentelemetry/exporter-trace-otlp-http
```

```typescript
import { NodeSDK } from '@opentelemetry/sdk-node';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';

// Langfuse
const exporter = new OTLPTraceExporter({
  url: `${process.env.LANGFUSE_HOST}/api/public/otel/v1/traces`,
  headers: {
    Authorization: `Basic ${Buffer.from(
      `${process.env.LANGFUSE_PUBLIC_KEY}:${process.env.LANGFUSE_SECRET_KEY}`
    ).toString('base64')}`,
  },
});

// Or Jaeger local: url: 'http://localhost:4318/v1/traces'

const sdk = new NodeSDK({ traceExporter: exporter });
sdk.start();

// FiGuard picks up the OTEL SDK automatically
import { FiGuardClient } from 'figuard';
const client = new FiGuardClient({ apiKey: 'sb_live_demo', baseUrl: 'https://sandbox.figuard.io' });
```

---

## Other backends

The setup pattern is the same for any OTEL-compatible backend — only the exporter endpoint and auth headers change:

| Backend | Exporter package | Endpoint |
|---|---|---|
| Honeycomb | `opentelemetry-exporter-otlp-proto-http` | `https://api.honeycomb.io/v1/traces` + `x-honeycomb-team` header |
| Datadog | `opentelemetry-exporter-otlp-proto-http` | `https://trace.agent.datadoghq.com/api/v0.2/traces` |
| Grafana Tempo | `opentelemetry-exporter-otlp-proto-grpc` | your Tempo OTLP gRPC endpoint |
| Self-hosted Jaeger | `opentelemetry-exporter-otlp-proto-grpc` | `http://localhost:4317` |

FiGuard's SDK has no opinion on the exporter — any exporter registered with the global `TracerProvider` will receive `figuard.*` spans.

---

## No-op behaviour

If `@opentelemetry/api` (TypeScript) or `opentelemetry-api` (Python) is not installed, all telemetry calls are silent no-ops. The SDK works identically. There is no performance cost — the no-op path is a single `if not _OTEL_AVAILABLE` check.
