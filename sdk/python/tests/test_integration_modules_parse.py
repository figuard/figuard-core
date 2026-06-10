"""
Regression guard: every integration module must parse, even when its optional
framework dependency is not installed.

Why this exists: the framework integration tests use pytest.importorskip(), so when
(e.g.) crewai is not installed in CI, crewai.py is never imported and a syntax error
in it goes undetected — until a user with the framework installed does `from figuard
import ...` and hits a SyntaxError at parse time. A duplicate triple-quote in crewai.py
shipped in v1.1.0/v1.1.1 exactly this way. ast.parse checks the syntax without importing
(so no optional dependency is required).
"""

from __future__ import annotations

import ast
import pathlib

import pytest

INTEGRATIONS_DIR = pathlib.Path(__file__).resolve().parents[1] / "figuard" / "integrations"

INTEGRATION_FILES = sorted(
    p for p in INTEGRATIONS_DIR.glob("*.py") if p.name != "__init__.py"
)


@pytest.mark.parametrize("module_path", INTEGRATION_FILES, ids=lambda p: p.name)
def test_integration_module_has_valid_syntax(module_path: pathlib.Path):
    source = module_path.read_text(encoding="utf-8")
    # Raises SyntaxError with a clear message if the module is malformed.
    ast.parse(source, filename=str(module_path))


def test_at_least_the_known_integrations_are_present():
    names = {p.name for p in INTEGRATION_FILES}
    # Guard against the glob silently matching nothing.
    assert {"langchain.py", "crewai.py"} <= names, names
