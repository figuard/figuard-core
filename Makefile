.PHONY: run stop reset logs test test-live publish

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
	@echo "[FiGuard] Dashboard: http://localhost:5173"
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
	@echo "[FiGuard] Unit tests passed."

test-live: test
	@echo "[FiGuard] Running live integration tests (container must be running)..."
	@cd sdk/python && python -m pytest tests/live/ -v --tb=short
	@echo "[FiGuard] Live tests passed."

publish: test-live
	@echo "[FiGuard] Building wheel..."
	@cd sdk/python && python -m build
	@echo "[FiGuard] Publishing to PyPI..."
	@cd sdk/python && python -m twine upload dist/figuard-$$(python -c "import figuard; print(figuard.__version__)")*
	@echo "[FiGuard] Published."
