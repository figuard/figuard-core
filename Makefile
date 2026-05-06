.PHONY: run stop reset logs

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
	@echo "[FiGuard] Demo API key: ab_live_demo"
	@echo "[FiGuard] Header: X-Agent-Budget-Key: ab_live_demo"
	@echo "========================================="

stop:
	docker compose down

reset:
	docker compose down -v
	@echo "[FiGuard] Data cleared. Run 'make run' to start fresh."

logs:
	docker compose logs -f figuard
