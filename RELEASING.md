# Releasing FiGuard

This document covers the release process for maintainers. Run through every section in order before publishing a GitHub Release.

---

## 1. Run the automated checks

```bash
bash scripts/check-release.sh
```

This script checks:
- figuard-notebooks for known anti-patterns (stale method signatures, wrong param names)
- CHANGELOG has an entry for the version being released

> **Note:** The script also checks local SDK versions against PyPI/npm. These checks are no longer meaningful — SDK versions are now stamped from the git tag at publish time, not pre-bumped in source. Ignore any version-mismatch failures from those two checks; treat only the CHANGELOG and notebook checks as blocking.

All blocking checks must pass before proceeding.

---

## 2. SDK readiness

### Python SDK (`sdk/python/`)
- [ ] New API endpoints added in this release have corresponding SDK methods
- [ ] Method signatures match the API (param names, types)
- [ ] **No manual version bump needed** — the publish workflow stamps the version from the git tag at build time

### TypeScript SDK (`sdk/typescript/`)
- [ ] Same method coverage check as Python
- [ ] **No manual version bump needed** — stamped from the tag at publish time

### MCP server (`packages/mcp/`)
- [ ] All tool definitions map to valid API endpoints in this release
- [ ] Tool input schemas match current API request format
- [ ] **No manual version bump needed** — stamped from the tag at publish time

### Java SDK (`sdk/java/`)
- [ ] If new API endpoints exist — add SDK methods or note them as pending in `sdk/java/README.md`

---

## 3. Notebooks (`figuard/figuard-notebooks`)

- [ ] Any new SDK methods used in notebooks reference the **newly published** PyPI version
- [ ] No notebooks reference local/dev SDK versions
- [ ] Spot-check the agent-incident notebooks against the sandbox — they are the primary user-facing examples
- [ ] Run `scripts/check-release.sh` notebook checks (included in step 1)

---

## 4. CHANGELOG

- [ ] `CHANGELOG.md` has an entry for the new version with a summary of changes
- [ ] Breaking changes are clearly marked

---

## 5. CI

- [ ] All CI checks are green on `main`
- [ ] No failing tests

---

## 6. Cut the release

1. Go to **github.com/figuard/figuard-core → Releases → Draft a new release**
2. Tag: `vX.Y.Z` targeting `main`
3. Title: `vX.Y.Z`
4. Paste CHANGELOG entry as release notes
5. Click **Publish release**

**What happens automatically on publish:**
- `publish-python.yml` — stamps `__version__` from the tag, builds a wheel, publishes to PyPI via OIDC trusted publishing (no token needed)
- `publish-npm.yml` — stamps version in `package.json` for both `figuard` and `figuard-mcp`, publishes both to npm (requires `NPM_TOKEN` secret)
- `rebuild-sandbox.yml` — triggers a Render redeploy of the sandbox instance

No manual `twine upload` or `npm publish` needed — the tag is the release signal.

---

## 7. Verify

- [ ] GitHub Actions `rebuild-sandbox` workflow completes successfully
- [ ] Sandbox is healthy: `curl https://figuard-sandbox-g1ha.onrender.com/api/v1/health`
- [ ] Spot-check one or two API calls against the sandbox with `sb_live_demo` key
