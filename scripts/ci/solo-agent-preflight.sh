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
# The client login address is informational. Presence is verified by the signed Manager reply.
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
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
