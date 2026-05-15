#!/usr/bin/env python3
"""
check_snippets.py — validate that code in README, Colab notebooks, and Wizard.tsx
stays consistent with the current Python SDK API surface.

Three checks per snippet:
  1. Syntax      — compiles as valid Python (ast.parse)
  2. Imports     — every `from figuard.X import Y` resolves against the installed SDK
  3. API symbols — public methods/classes referenced in snippets exist on FiGuardClient
                   and in the integration modules

Wizard.tsx is also scanned for hard-coded import paths and method names that must
match the installed SDK, so a rename in the SDK is caught at push time.

Run standalone:
    python scripts/check_snippets.py

Exits 0 if all clean, 1 if any failure.
"""

from __future__ import annotations

import ast
import importlib
import json
import re
import sys
from pathlib import Path
from typing import NamedTuple

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

REPO_ROOT = Path(__file__).resolve().parent.parent
README_PATH = REPO_ROOT / "README.md"
NOTEBOOKS_DIR = Path("/Users/laharipriya/Documents/figuard-notebooks")
WIZARD_PATH = Path("/Users/laharipriya/Documents/figuard-site/src/components/Wizard.tsx")

# Public methods that must exist on FiGuardClient
REQUIRED_CLIENT_METHODS = [
    "create_budget",
    "authorize",
    "confirm_event",
    "fail_event",
    "void_event",
    "create_delegation_token",
]

# Import paths hard-coded in the wizard — (module, symbol)
WIZARD_IMPORTS = [
    ("figuard", "FiGuardClient"),
    ("figuard.integrations.langchain", "FiGuardCallbackHandler"),
    ("figuard.integrations.crewai", "FiGuardCrewGuard"),
    ("figuard.integrations.openai_agents", "guarded_function_tool"),
]

# Notebook cells starting with these are shell commands — skip Python checks
SHELL_PREFIXES = ("!", "%")


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

class Failure(NamedTuple):
    source: str   # e.g. "README line 112" or "langchain-shopping-agent.ipynb cell 1"
    check: str    # "syntax" | "import" | "symbol" | "wizard_import" | "wizard_method"
    detail: str


failures: list[Failure] = []


def fail(source: str, check: str, detail: str) -> None:
    failures.append(Failure(source, check, detail))
    print(f"  FAIL [{check}] {source}: {detail}")


def ok(msg: str) -> None:
    print(f"  ok   {msg}")


# ---------------------------------------------------------------------------
# 1. Syntax check
# ---------------------------------------------------------------------------

def syntax_check(code: str, source: str) -> bool:
    """Return True if code is syntactically valid Python."""
    # Strip Jupyter magic / shell lines before checking
    cleaned_lines = []
    for line in code.splitlines():
        stripped = line.lstrip()
        if stripped.startswith(SHELL_PREFIXES):
            continue  # skip !pip install, %matplotlib, etc.
        cleaned_lines.append(line)
    cleaned = "\n".join(cleaned_lines)
    if not cleaned.strip():
        return True
    try:
        ast.parse(cleaned)
        return True
    except SyntaxError as exc:
        fail(source, "syntax", str(exc))
        return False


# ---------------------------------------------------------------------------
# 2. Import resolution
# ---------------------------------------------------------------------------

def check_imports(code: str, source: str) -> None:
    """Check every `from figuard... import ...` line resolves."""
    for line in code.splitlines():
        line = line.strip()
        m = re.match(r"^from (figuard[\w.]*) import (.+)$", line)
        if not m:
            continue
        module_name, symbols_str = m.group(1), m.group(2)
        # Strip aliases: "FiGuardClient as FGC" → "FiGuardClient"
        symbols = [s.strip().split(" as ")[0].strip() for s in symbols_str.split(",")]
        try:
            mod = importlib.import_module(module_name)
        except ImportError as exc:
            fail(source, "import", f"cannot import module `{module_name}`: {exc}")
            continue
        for sym in symbols:
            if not hasattr(mod, sym):
                fail(source, "import", f"`{sym}` not found in `{module_name}`")
            else:
                ok(f"{source}: `from {module_name} import {sym}`")


# ---------------------------------------------------------------------------
# 3. API symbol check (FiGuardClient methods)
# ---------------------------------------------------------------------------

def check_client_symbols() -> None:
    """Verify required methods exist on FiGuardClient."""
    try:
        from figuard import FiGuardClient
    except ImportError as exc:
        fail("FiGuardClient", "symbol", f"cannot import FiGuardClient: {exc}")
        return

    for method in REQUIRED_CLIENT_METHODS:
        if hasattr(FiGuardClient, method) and callable(getattr(FiGuardClient, method)):
            ok(f"FiGuardClient.{method} exists")
        else:
            fail("FiGuardClient", "symbol", f"method `{method}` missing or not callable")


# ---------------------------------------------------------------------------
# 4. Wizard import/method check
# ---------------------------------------------------------------------------

def check_wizard() -> None:
    """Check that every import path and method reference in Wizard.tsx resolves."""
    if not WIZARD_PATH.exists():
        print(f"  skip wizard check — {WIZARD_PATH} not found")
        return

    wizard_text = WIZARD_PATH.read_text()

    # 4a. Import paths
    for module_name, symbol in WIZARD_IMPORTS:
        try:
            mod = importlib.import_module(module_name)
        except ImportError as exc:
            fail("Wizard.tsx", "wizard_import", f"cannot import `{module_name}`: {exc}")
            continue
        if not hasattr(mod, symbol):
            fail("Wizard.tsx", "wizard_import", f"`{symbol}` not found in `{module_name}`")
        else:
            ok(f"Wizard.tsx: `from {module_name} import {symbol}`")

    # 4b. Python method calls referenced in generated code strings
    # Only check snake_case names — camelCase ones are TypeScript SDK calls
    py_method_pattern = re.compile(r"client\.([a-z][a-z0-9_]+)\(")
    wizard_methods = set(py_method_pattern.findall(wizard_text))

    try:
        from figuard import FiGuardClient
        for method in wizard_methods:
            if not hasattr(FiGuardClient, method):
                fail("Wizard.tsx", "wizard_method",
                     f"client.{method}() referenced in wizard but not found on FiGuardClient")
            else:
                ok(f"Wizard.tsx: client.{method}() exists on FiGuardClient")
    except ImportError:
        pass  # already reported above

    # 4c. Check that confirm_event is called correctly (confirmed_quantity kwarg)
    if "confirmed_quantity" not in wizard_text:
        fail("Wizard.tsx", "wizard_method",
             "wizard does not use `confirmed_quantity` kwarg in confirm_event call")
    else:
        ok("Wizard.tsx: confirm_event uses confirmed_quantity kwarg")


# ---------------------------------------------------------------------------
# Sources: README
# ---------------------------------------------------------------------------

def collect_readme_snippets() -> list[tuple[str, str]]:
    """Extract all ```python blocks from README.md. Returns (source_label, code)."""
    if not README_PATH.exists():
        print(f"  skip README — {README_PATH} not found")
        return []
    text = README_PATH.read_text()
    snippets = []
    for i, m in enumerate(re.finditer(r"```python\n(.*?)```", text, re.DOTALL), 1):
        line_no = text[: m.start()].count("\n") + 1
        snippets.append((f"README.md line {line_no}", m.group(1)))
    return snippets


# ---------------------------------------------------------------------------
# Sources: Colab notebooks
# ---------------------------------------------------------------------------

def collect_notebook_snippets() -> list[tuple[str, str]]:
    """Extract all code cells from every .ipynb in NOTEBOOKS_DIR."""
    if not NOTEBOOKS_DIR.exists():
        print(f"  skip notebooks — {NOTEBOOKS_DIR} not found")
        return []
    snippets = []
    for nb_path in sorted(NOTEBOOKS_DIR.glob("*.ipynb")):
        try:
            nb = json.loads(nb_path.read_text())
        except Exception as exc:
            fail(nb_path.name, "syntax", f"cannot parse notebook JSON: {exc}")
            continue
        for i, cell in enumerate(nb.get("cells", [])):
            if cell.get("cell_type") != "code":
                continue
            source = "".join(cell.get("source", []))
            if source.strip():
                snippets.append((f"{nb_path.name} cell {i}", source))
    return snippets


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> int:
    print("=" * 60)
    print("FiGuard snippet validator")
    print("=" * 60)

    # Step 1: API surface
    print("\n[1/4] Checking FiGuardClient API surface...")
    check_client_symbols()

    # Step 2: Wizard
    print("\n[2/4] Checking Wizard.tsx import paths and method references...")
    check_wizard()

    # Step 3: README
    print("\n[3/4] Checking README.md code snippets...")
    readme_snippets = collect_readme_snippets()
    if readme_snippets:
        for source, code in readme_snippets:
            if syntax_check(code, source):
                check_imports(code, source)
    else:
        print("  (no Python snippets found)")

    # Step 4: Notebooks
    print("\n[4/4] Checking Colab notebook code cells...")
    nb_snippets = collect_notebook_snippets()
    if nb_snippets:
        for source, code in nb_snippets:
            if syntax_check(code, source):
                check_imports(code, source)
    else:
        print("  (no notebooks found)")

    # Result
    print("\n" + "=" * 60)
    if failures:
        print(f"FAILED — {len(failures)} issue(s) found:\n")
        for f in failures:
            print(f"  [{f.check}] {f.source}")
            print(f"    {f.detail}")
        print()
        return 1
    else:
        print(f"All checks passed ({len(readme_snippets)} README snippets, "
              f"{len(nb_snippets)} notebook cells, wizard + API surface).")
        return 0


if __name__ == "__main__":
    sys.exit(main())
