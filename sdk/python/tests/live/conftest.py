"""
Shared fixtures for live integration tests.

Requires a running figuard-core container:
    make run          # from figuard-core repo root

Override the URL via environment variable:
    FIGUARD_URL=http://localhost:8080 pytest tests/live/

Tests are skipped automatically when the container is not reachable.
"""

from __future__ import annotations

import os
from datetime import datetime, timedelta, timezone

import pytest
import requests

from figuard import FiGuardClient

FIGUARD_URL = os.environ.get("FIGUARD_URL", "http://localhost:8080")
DEMO_API_KEY = os.environ.get("FIGUARD_API_KEY", "ab_live_testkey123")


def _expires_at() -> str:
    """Return an ISO-8601 timestamp 23 hours from now (within the 24h budget limit)."""
    ts = datetime.now(timezone.utc) + timedelta(hours=23)
    return ts.strftime("%Y-%m-%dT%H:%M:%SZ")


def _container_is_up() -> bool:
    try:
        resp = requests.get(f"{FIGUARD_URL}/actuator/health", timeout=3)
        return resp.status_code == 200
    except Exception:
        return False


# Module-level skip: applied to every test in the live/ directory
def pytest_collection_modifyitems(config, items):
    if not _container_is_up():
        skip = pytest.mark.skip(
            reason=f"figuard container not reachable at {FIGUARD_URL} — run `make run` first"
        )
        for item in items:
            if "live" in str(item.fspath):
                item.add_marker(skip)


@pytest.fixture(scope="session")
def figuard_url() -> str:
    return FIGUARD_URL


@pytest.fixture(scope="session")
def api_key() -> str:
    return DEMO_API_KEY


@pytest.fixture(scope="session")
def client(figuard_url: str, api_key: str) -> FiGuardClient:
    return FiGuardClient(api_key=api_key, base_url=figuard_url)


@pytest.fixture
def flat_budget(client: FiGuardClient):
    """A flat (no-allocation) $500 budget for general spend tests."""
    budget = client.create_budget(
        user_id="live_test_user",
        total_limit=500.00,
        expires_at=_expires_at(),
    )
    return budget


@pytest.fixture
def allocated_budget(client: FiGuardClient):
    """A $400 budget with flight ($300) and hotel ($100) allocations."""
    budget = client.create_budget(
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
    return budget


@pytest.fixture
def tiny_budget(client: FiGuardClient):
    """A $10 budget — useful for triggering INSUFFICIENT_FUNDS."""
    budget = client.create_budget(
        user_id="live_test_user",
        total_limit=10.00,
        expires_at=_expires_at(),
    )
    return budget
