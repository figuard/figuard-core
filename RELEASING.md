# Releasing FiGuard

This document covers the release process for maintainers. Run through every section in order before publishing a GitHub Release.

---

## 1. Run the automated checks

```bash
bash scripts/check-release.sh
```

This script checks:
- Python SDK local version vs published PyPI version
- TypeScript SDK local version vs published npm version
- figuard-notebooks for known anti-patterns (stale method signatures, wrong param names)
- CHANGELOG has an entry for the version being released

All checks must pass before proceeding.

---

## 2. SDK readiness

### Python SDK (`sdk/python/`)
- [ ] New API endpoints added in this release have corresponding SDK methods
- [ ] Method signatures match the API (param names, types)
- [ ] `pyproject.toml` version bumped appropriately (patch / minor / major)
- [ ] Published to PyPI: `cd sdk/python && python -m build && twine upload dist/*`
- [ ] Verify: `pip install figuard==<new-version>` works

### TypeScript SDK (`sdk/typescript/`)
- [ ] Same method coverage check as Python
- [ ] `package.json` version bumped
- [ ] Published to npm: `cd sdk/typescript && npm publish`
- [ ] Verify: `npm install figuard@<new-version>` works

### Java SDK (`sdk/java/`)
- [ ] If new API endpoints exist — add SDK methods or note them as pending in `sdk/java/README.md`

---

## 3. Notebooks (`figuard/figuard-notebooks`)

- [ ] Any new SDK methods used in notebooks reference the **newly published** PyPI version
- [ ] No notebooks reference local/dev SDK versions
- [ ] Spot-check the agent-incident notebooks against the sandbox — they are the primary user-facing examples
- [ ] Run `scripts/check-release.sh` notebook checks (included in step 1)

---

## 4. MCP server

- [ ] All MCP tool definitions map to valid API endpoints in this release
- [ ] Tool input schemas match current API request format
- [ ] Tested manually: at least one tool call succeeds end-to-end

---

## 5. CHANGELOG

- [ ] `CHANGELOG.md` has an entry for the new version with a summary of changes
- [ ] Breaking changes are clearly marked

---

## 6. CI

- [ ] All CI checks are green on `main`
- [ ] No failing tests

---

## 7. Cut the release

1. Go to **github.com/figuard/figuard-core → Releases → Draft a new release**
2. Tag: `vX.Y.Z` targeting `main`
3. Title: `vX.Y.Z`
4. Paste CHANGELOG entry as release notes
5. Click **Publish release**

Publishing the release automatically triggers the sandbox rebuild via the `rebuild-sandbox.yml` workflow.

---

## 8. Verify

- [ ] GitHub Actions `rebuild-sandbox` workflow completes successfully
- [ ] Sandbox is healthy: `curl https://figuard-sandbox-g1ha.onrender.com/api/v1/health`
- [ ] Spot-check one or two API calls against the sandbox with `sb_live_demo` key
