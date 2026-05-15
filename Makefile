.PHONY: run stop reset logs test test-ts test-mcp test-all test-live dashboard publish

run:
	docker compose up --build -d
	@echo "[FiGuard] Building and starting... (first build takes ~2 minutes)"
	@echo "[FiGuard] Waiting for service to be ready..."
	@until docker compose exec -T figuard wget -qO- http://localhost:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; do \
		printf '.'; sleep 3; \
	done
	@echo ""
	@echo "========================================="
	@echo "[FiGuard] Ready at http://localhost:8080"
	@echo "[FiGuard] Dashboard: run 'make dashboard' in a separate terminal"
	@echo "[FiGuard] Demo API key: ab_live_demo"
	@echo "[FiGuard] Header: X-Agent-Budget-Key: ab_live_demo"
	@echo ""
	@echo "[FiGuard] Smoke test:"
	@echo "  curl -s -H 'X-Agent-Budget-Key: ab_live_demo' \\"
	@echo "    http://localhost:8080/api/v1/budgets | head -c 200"
	@echo ""
	@echo "[FiGuard] Run examples:"
	@echo "  pip install figuard"
	@echo "  python examples/rogue_agent_scenarios.py"
	@echo "========================================="

stop:
	docker compose down

reset:
	docker compose down -v
	@echo "[FiGuard] Data cleared. Run 'make run' to start fresh."

logs:
	docker compose logs -f figuard

# ── SDK testing ──────────────────────────────────────────────────────────────

test:
	@echo "[FiGuard] Running Python SDK unit tests..."
	@cd sdk/python && python -m pytest tests/ --ignore=tests/live -q --tb=short
	@echo "[FiGuard] Python SDK tests passed."

test-ts:
	@echo "[FiGuard] Running TypeScript SDK tests..."
	@cd sdk/typescript && npm install --silent && npm test
	@echo "[FiGuard] TypeScript SDK tests passed."

test-mcp:
	@echo "[FiGuard] Running MCP server tests..."
	@cd packages/mcp && npm install --silent && npm test
	@echo "[FiGuard] MCP server tests passed."

test-all: test test-ts test-mcp
	@echo "[FiGuard] All test suites passed."

test-live: test
	@echo "[FiGuard] Running live integration tests (container must be running)..."
	@cd sdk/python && python -m pytest tests/live/ -v --tb=short
	@echo "[FiGuard] Live tests passed."

# ── Dashboard ─────────────────────────────────────────────────────────────────

dashboard:
	@echo "[FiGuard] Starting dashboard at http://localhost:5173"
	@echo "[FiGuard] Point it at your running FiGuard instance (default: http://localhost:8080)"
	@cd dashboard && npm install --silent && npm run dev

publish: test-all
	@echo "[FiGuard] Building wheel..."
	@cd sdk/python && python -m build
	@echo "[FiGuard] Publishing to PyPI..."
	@cd sdk/python && python -m twine upload dist/figuard-$$(python -c "import figuard; print(figuard.__version__)")*
	@echo "[FiGuard] Published."
