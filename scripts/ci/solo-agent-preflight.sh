#!/usr/bin/env bash
# Check the dedicated profile configuration and optionally Manager readiness.
# The real client must prove signed presence and physical readback separately.
set -euo pipefail
LIVE=0
if [[ "${1:-}" == "--live" ]]; then LIVE=1; fi
fail=0
pass() { echo "PASS: $1"; }
blocked() { echo "BLOCKED: $1"; fail=1; }
skip() { echo "SKIP: $1"; }

# 1. One-time configuration present (the only human step that may ever exist).
if [[ -n "${WESORT_MANAGER_URL:-}" ]]; then pass "manager URL configured"; else blocked "WESORT_MANAGER_URL not set (IP/port of the Manager)"; fi
if [[ -n "${WESORT_API_KEY:-}" ]]; then pass "API credential configured"; else blocked "WESORT_API_KEY not set"; fi
server="${WESORT_MC_SERVER:-}"
if [[ "${server,,}" =~ ^([a-z0-9-]+\.)*6b6t\.org\.?(:[0-9]+)?$ ]]; then
  pass "6b6t server configured"
else
  blocked "WESORT_MC_SERVER must identify 6b6t.org"
fi

# 2. Static contract checks: 6b6t scope, lobby portal geometry, signed link scope.
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
if grep -q "6b6t" "$ROOT/src/main/java/com/porter/wesort/stash/Stash.java"; then
  pass "6b6t server scope pinned in Stash.is6b6tServer"
else
  blocked "Stash.is6b6tServer has no 6b6t scope"
fi
if [[ -f "$ROOT/src/test/java/com/porter/wesort/utils/PortalEntryTest.java" \
   && -f "$ROOT/src/test/java/com/porter/wesort/commands/ManagerLinkScopeTest.java" ]]; then
  pass "portal geometry + signed link-scope unit tests present"
else
  blocked "portal/link-scope unit tests missing"
fi

# Public readiness reports no server identity. Only the signed client can prove scope.
if [[ "$LIVE" -eq 0 ]]; then
  skip "Manager readiness (use --live on the agent host)"
elif [[ -n "${WESORT_MANAGER_URL:-}" ]]; then
  code="$(curl --proto '=http,https' -s -o /dev/null -w '%{http_code}' --max-time 10 -- "${WESORT_MANAGER_URL%/}/health/ready" || true)"
  if [[ "$code" == 200 ]]; then
    pass "Manager ready"
  else
    blocked "Manager readiness returned HTTP ${code:-000}"
  fi
fi
skip "signed hello, lobby transit and inventory readback require the running Minecraft client"

if [[ "$fail" -eq 0 ]]; then echo "preflight OK"; else echo "preflight BLOCKED (see lines above)"; fi
exit "$fail"
