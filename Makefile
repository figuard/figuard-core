.PHONY: run stop reset

run:
	docker compose up --build -d
	@echo ""
	@echo "[FiGuard] Starting... (first build takes ~2 minutes)"
	@echo "[FiGuard] Watch logs:  docker compose logs -f figuard"
	@echo "[FiGuard] Health:      curl http://localhost:8080/actuator/health"
	@echo "[FiGuard] Demo key:    ab_live_demo"

stop:
	docker compose down

reset:
	docker compose down -v
	@echo "[FiGuard] Data cleared. Run 'make run' to start fresh."
