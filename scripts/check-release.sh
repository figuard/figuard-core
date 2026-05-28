#!/usr/bin/env bash
# check-release.sh — pre-release consistency checks for figuard-core
#
# Run this before cutting a GitHub Release:
#   bash scripts/check-release.sh
#
# Checks:
#   1. Python SDK local version vs published PyPI version
#   2. TypeScript SDK local version vs published npm version
#   3. figuard-notebooks for known anti-patterns
#   4. CHANGELOG has an entry for the local SDK version

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NOTEBOOKS_DIR="${NOTEBOOKS_DIR:-}"   # override if notebooks repo is elsewhere
PASS=0
FAIL=0

green()  { echo -e "\033[0;32m✓ $*\033[0m"; }
red()    { echo -e "\033[0;31m✗ $*\033[0m"; }
yellow() { echo -e "\033[0;33m! $*\033[0m"; }
header() { echo -e "\n\033[1m── $* ──\033[0m"; }

pass() { green "$1"; PASS=$((PASS+1)); }
fail() { red   "$1"; FAIL=$((FAIL+1)); }
warn() { yellow "$1"; }

# ─────────────────────────────────────────────
# 1. Python SDK — is local version on PyPI?
# ─────────────────────────────────────────────
header "Python SDK"

PY_LOCAL=$(grep '__version__' "$REPO_ROOT/sdk/python/figuard/__init__.py" \
           | head -1 | sed 's/.*"\(.*\)".*/\1/')
echo "  local version: $PY_LOCAL"

if command -v curl &>/dev/null; then
  PY_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
              "https://pypi.org/pypi/figuard/${PY_LOCAL}/json")
  if [ "$PY_STATUS" = "200" ]; then
    pass "Python SDK $PY_LOCAL is published on PyPI"
  elif [ "$PY_STATUS" = "404" ]; then
    fail "Python SDK $PY_LOCAL is NOT on PyPI — publish before releasing"
  else
    warn "PyPI returned HTTP $PY_STATUS — check manually"
  fi
else
  warn "curl not found — cannot check PyPI"
fi

# ─────────────────────────────────────────────
# 2. TypeScript SDK — is local version on npm?
# ─────────────────────────────────────────────
header "TypeScript SDK"

TS_LOCAL=$(grep '"version"' "$REPO_ROOT/sdk/typescript/package.json" \
           | head -1 | sed 's/.*"version": *"\(.*\)".*/\1/')
echo "  local version: $TS_LOCAL"

if command -v curl &>/dev/null; then
  TS_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
              "https://registry.npmjs.org/figuard/${TS_LOCAL}")
  if [ "$TS_STATUS" = "200" ]; then
    pass "TypeScript SDK $TS_LOCAL is published on npm"
  elif [ "$TS_STATUS" = "404" ]; then
    fail "TypeScript SDK $TS_LOCAL is NOT on npm — publish before releasing"
  else
    warn "npm registry returned HTTP $TS_STATUS — check manually"
  fi
else
  warn "curl not found — cannot check npm registry"
fi

# ─────────────────────────────────────────────
# 3. figuard-notebooks anti-patterns
# ─────────────────────────────────────────────
header "Notebooks"

# Locate notebooks repo — try common sibling paths
if [ -z "$NOTEBOOKS_DIR" ]; then
  for candidate in \
    "$REPO_ROOT/../figuard-notebooks" \
    "$HOME/Documents/figuard-notebooks"; do
    if [ -d "$candidate" ]; then
      NOTEBOOKS_DIR="$candidate"
      break
    fi
  done
fi

if [ -z "$NOTEBOOKS_DIR" ] || [ ! -d "$NOTEBOOKS_DIR" ]; then
  warn "figuard-notebooks not found — set NOTEBOOKS_DIR=/path/to/figuard-notebooks to enable notebook checks"
else
  echo "  scanning: $NOTEBOOKS_DIR"
  NB_ISSUES=0

  check_pattern() {
    local pattern="$1"
    local description="$2"
    local matches
    matches=$(grep -rl "$pattern" "$NOTEBOOKS_DIR" --include="*.ipynb" 2>/dev/null || true)
    if [ -n "$matches" ]; then
      fail "Anti-pattern [$description] found in:"
      echo "$matches" | sed 's/^/      /'
      NB_ISSUES=$((NB_ISSUES+1))
    fi
  }

  check_pattern 'tokens\["default"\]'                    'stale token extraction (use budget.session_token)'
  check_pattern 't\.session_token for t in budget\.tokens' 'stale token extraction (use budget.session_token)'
  check_pattern '"enforcement_mode"'                      'snake_case key — API expects enforcementMode'
  check_pattern '"allowed_categories"'                    'snake_case key — API expects allowedCategories'
  check_pattern 'create_delegation_token.*session_token=' 'create_delegation_token() has no session_token param'
  check_pattern 'create_delegation_token.*expires_in='   'create_delegation_token() has no expires_in param'

  if [ "$NB_ISSUES" -eq 0 ]; then
    pass "No known anti-patterns found in notebooks"
  fi
fi

# ─────────────────────────────────────────────
# 4. CHANGELOG has entry for current version
# ─────────────────────────────────────────────
header "CHANGELOG"

CHANGELOG="$REPO_ROOT/CHANGELOG.md"
if [ ! -f "$CHANGELOG" ]; then
  fail "CHANGELOG.md not found"
else
  if grep -q "$PY_LOCAL\|$TS_LOCAL" "$CHANGELOG" 2>/dev/null; then
    pass "CHANGELOG.md contains an entry for this version"
  else
    fail "CHANGELOG.md has no entry for $PY_LOCAL — add release notes before publishing"
  fi
fi

# ─────────────────────────────────────────────
# Summary
# ─────────────────────────────────────────────
echo ""
echo "────────────────────────────"
if [ "$FAIL" -eq 0 ]; then
  green "All checks passed ($PASS passed, 0 failed)"
  echo ""
  echo "Ready to release. Next steps:"
  echo "  1. Publish Python SDK:     cd sdk/python && python -m build && twine upload dist/*"
  echo "  2. Publish TypeScript SDK: cd sdk/typescript && npm publish"
  echo "  3. Create GitHub Release at github.com/figuard/figuard-core/releases/new"
  exit 0
else
  red "$FAIL check(s) failed, $PASS passed"
  echo ""
  echo "Fix the issues above before cutting the release."
  exit 1
fi
