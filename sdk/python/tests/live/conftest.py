"""
Shared fixtures for live integration tests.

Two modes:

  1. Manual (default):
         make run          # from figuard-core repo root
         pytest tests/live/
     Tests are skipped automatically if the container is not reachable.
     Override the URL:
         FIGUARD_URL=http://localhost:8080 pytest tests/live/

  2. Testcontainers (CI / automatic):
         FIGUARD_USE_TESTCONTAINERS=true pytest tests/live/
         # or just set CI=true — same effect
     Spins up a fresh figuard stack using docker-compose.sdk-test.yml,
     runs all tests, tears down on completion.
     Requires the figuard:latest image to be built first:
         docker build -t figuard:latest /path/to/figuard-core
"""

from __future__ import annotations

import os
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
import requests

from figuard import FiGuardClient

_USE_TESTCONTAINERS = bool(
    os.environ.get("FIGUARD_USE_TESTCONTAINERS") or os.environ.get("CI")
)
_MANUAL_URL = os.environ.get("FIGUARD_URL", "http://localhost:8080")
_TC_URL = "http://localhost:18080"           # matches port in docker-compose.sdk-test.yml
_SDK_DIR = Path(__file__).parent.parent.parent   # sdk/python/

DEMO_API_KEY = os.environ.get("FIGUARD_API_KEY", "fg_live_testkey123")


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _expires_at() -> str:
    ts = datetime.now(timezone.utc) + timedelta(hours=23)
    return ts.strftime("%Y-%m-%dT%H:%M:%SZ")


def _is_healthy(url: str) -> bool:
    try:
        return requests.get(f"{url}/actuator/health", timeout=3).status_code == 200
    except Exception:
        return False


def _wait_for_health(url: str, timeout: int = 180, interval: int = 5) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if _is_healthy(url):
            return True
        time.sleep(interval)
    return False


# ---------------------------------------------------------------------------
# Session-scoped stack fixture
# ---------------------------------------------------------------------------

@pytest.fixture(scope="session")
def figuard_url() -> str:
    return _TC_URL if _USE_TESTCONTAINERS else _MANUAL_URL


@pytest.fixture(scope="session", autouse=True)
def figuard_stack(figuard_url: str):
    """
    Ensures a figuard stack is running for the duration of the test session.

    Testcontainers mode (FIGUARD_USE_TESTCONTAINERS=true or CI=true):
      - Spins up postgres + figuard via docker-compose.sdk-test.yml
      - Waits for the health endpoint to return 200
      - Tears down after all tests complete
      - Requires: docker, figuard:latest image

    Manual mode (default):
      - Checks that the stack is reachable at FIGUARD_URL
      - Skips all live tests if it is not
    """
    if _USE_TESTCONTAINERS:
        from testcontainers.compose import DockerCompose

        compose = DockerCompose(
            str(_SDK_DIR),
            compose_file_name="docker-compose.sdk-test.yml",
        )
        compose.start()
        try:
            if not _wait_for_health(_TC_URL, timeout=180):
                pytest.skip(
                    "figuard stack (Testcontainers) did not become healthy within 3 minutes — "
                    "check that figuard:latest image is built"
                )
            yield
        finally:
            compose.stop()
    else:
        if not _is_healthy(_MANUAL_URL):
            pytest.skip(
                f"figuard container not reachable at {_MANUAL_URL} — "
                "run `make run` first, or set FIGUARD_USE_TESTCONTAINERS=true"
            )
        yield


# Fallback: mark individual test items as skipped when running without the autouse fixture
def pytest_collection_modifyitems(config, items):
    if not _USE_TESTCONTAINERS and not _is_healthy(_MANUAL_URL):
        skip = pytest.mark.skip(
            reason=(
                f"figuard container not reachable at {_MANUAL_URL} — "
                "run `make run` first, or set FIGUARD_USE_TESTCONTAINERS=true"
            )
        )
        for item in items:
            if "live" in str(item.fspath):
                item.add_marker(skip)


# ---------------------------------------------------------------------------
# Test fixtures
# ---------------------------------------------------------------------------

@pytest.fixture(scope="session")
def api_key() -> str:
    return DEMO_API_KEY


@pytest.fixture(scope="session")
def client(figuard_url: str, api_key: str) -> FiGuardClient:
    return FiGuardClient(api_key=api_key, base_url=figuard_url)


@pytest.fixture
def flat_budget(client: FiGuardClient):
    """A flat (no-allocation) $500 budget for general spend tests."""
    return client.create_budget(
        user_id="live_test_user",
        total_limit=500.00,
        expires_at=_expires_at(),
    )


@pytest.fixture
def allocated_budget(client: FiGuardClient):
    """A $400 budget with flight ($300) and hotel ($100) allocations."""
    return client.create_budget(
        user_id="live_test_user",
        total_limit=400.00,
        expires_at=_expires_at(),
        allocations=[
            {
                "category": "flight",
                "allowedCategories": ["flight"],
                "limit": 300.00,
                "enforcementMode": "CATEGORY_CONSTRAINED",
            },
            {
                "category": "hotel",
                "allowedCategories": ["hotel"],
                "limit": 100.00,
                "enforcementMode": "CATEGORY_CONSTRAINED",
            },
        ],
    )


@pytest.fixture
def tiny_budget(client: FiGuardClient):
    """A $10 budget — useful for triggering INSUFFICIENT_FUNDS."""
    return client.create_budget(
        user_id="live_test_user",
        total_limit=10.00,
        expires_at=_expires_at(),
    )
