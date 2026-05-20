# Contributing to FiGuard

Issues, PRs, and integration requests welcome.

---

## Prerequisites

- [Docker](https://docs.docker.com/get-docker/) and Docker Compose (for running the service)
- Python 3.9+ (for the Python SDK)
- Java 21+ and Maven (for the Java service, only if editing backend code)

---

## Running the service locally

```bash
git clone https://github.com/figuard/figuard-core.git
cd figuard-core
make run
```

This builds the Docker image, starts Postgres, and waits until the service is healthy. First build takes ~2 minutes.

When ready:
```
=========================================
[FiGuard] Ready at http://localhost:8080
[FiGuard] Demo API key: fg_live_demo
[FiGuard] Header: X-Agent-Budget-Key: fg_live_demo
=========================================
```

Other Makefile targets:

| Command | What it does |
|---|---|
| `make run` | Build and start (detached) |
| `make stop` | Stop containers |
| `make reset` | Stop and wipe all data |
| `make logs` | Tail service logs |
| `make test` | Run Python SDK unit tests |
| `make test-live` | Run unit + live tests (service must be running) |

---

## Running the Python SDK tests

```bash
cd sdk/python
pip install -e ".[dev,langchain,crewai,openai-agents,openai,anthropic]"

# Unit tests only (no service needed)
pytest tests/ --ignore=tests/live -q

# Live tests (requires make run first)
pytest tests/live/ -v
```

---

## Running the Java service tests

```bash
./mvnw test
```

---

## Running the demo

With the service running (`make run`):

```bash
cd demo
pip install figuard
python demo.py
```

---

## Pull request guidelines

- One logical change per PR — don't bundle unrelated fixes
- New integrations must include unit tests (see `sdk/python/tests/integrations/` for examples)
- No internal codenames or planning documents in commits or PR descriptions
- Tests must pass: `make test` before opening a PR

---

## Good first issues

| Area | What to build |
|---|---|
| New framework integration | [LlamaIndex](https://www.llamaindex.ai/), [Vercel AI SDK](https://sdk.vercel.ai/), [DSPy](https://dspy.ai/) — follow the pattern in `sdk/python/figuard/integrations/` |
| Go SDK | Client with `authorize`, `confirm_event`, `fail_event`, `void_event` — same scope as the TypeScript SDK |
| Java SDK on Maven Central | `sdk/java/` exists at 0.3.0 but is not yet published — set up OSSRH + GPG signing in `pom.xml` |
| Example agents | Add to `examples/` — must be self-contained and runnable |

---

## Running the TypeScript SDK tests

```bash
cd sdk/typescript
npm install
npm test
```

## Running the MCP server tests

```bash
cd packages/mcp
npm install
npm test
```

## Running the dashboard locally

```bash
cd dashboard
npm install
npm run dev
# Opens at http://localhost:5173
# Point it at your running FiGuard instance (default: http://localhost:8080)
```

---

## Project structure

```
figuard-core/
├── src/                     # Java Spring Boot service
│   └── main/
│       ├── java/io/figuard/
│       └── resources/
├── sdk/
│   ├── python/
│   │   ├── figuard/         # SDK source
│   │   │   └── integrations/    # LangChain, CrewAI, OpenAI Agents, OpenAI, Anthropic
│   │   └── tests/
│   │       ├── integrations/
│   │       └── live/
│   ├── typescript/          # TypeScript SDK (npm: figuard)
│   │   └── src/
│   └── java/                # Java SDK (Maven, 0.3.0)
│       └── src/
├── packages/
│   └── mcp/                 # MCP server (npm: figuard-mcp, npx figuard-mcp)
│       └── src/
├── dashboard/               # React operator dashboard (Vite)
│   └── src/
├── demo/                    # Standalone demo script
├── examples/                # Runnable examples
├── Makefile
└── docker-compose.yml
```
