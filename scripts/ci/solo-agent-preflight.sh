#!/usr/bin/env bash
# Solo-agent preflight for #141: prove a real 6b6t-ready installation is configured
# before any live run. No human can ever interact with the agent instance afterwards:
# the only human step is this one-time configuration (IP, port, API credentials).
#
# Usage:
#   solo-agent-preflight.sh            # static checks only (CI-safe, no network)
#   solo-agent-preflight.sh --live     # also attempts Manager hello + presence (needs env)
#
# --live requires: WESORT_MANAGER_URL, WESORT_API_KEY, WESORT_MC_SERVER (e.g. 6b6t.org).
# Every check prints PASS, BLOCKED (with the exact missing precondition, never silent),
# or SKIP (live-only check without --live). Exit 0 only when nothing is BLOCKED.
# A live 6b6t run that is stopped by a server-side restriction must be reported as
# BLOCKED, never PASS (see docs/P0-ACCEPTANCE.md).
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
if [[ -n "${WESORT_MC_SERVER:-}" ]]; then pass "MC server configured: $WESORT_MC_SERVER"; else blocked "WESORT_MC_SERVER not set (e.g. 6b6t.org)"; fi

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

# 3. Live checks: signed hello, heartbeat presence, safe scan readback.
if [[ "$LIVE" -eq 0 ]]; then
  skip "live Manager hello/presence/scan (rerun with --live on the 6b6t host)"
else
  if [[ -z "${WESORT_MANAGER_URL:-}" || -z "${WESORT_API_KEY:-}" ]]; then
    blocked "live checks need WESORT_MANAGER_URL and WESORT_API_KEY"
  else
    code="$(curl -s -o /tmp/solo-health.json -w "%{http_code}" --max-time 10 "$WESORT_MANAGER_URL/health" || true)"
    if [[ "$code" == "200" || "$code" == "503" ]]; then
      pass "manager reachable at $WESORT_MANAGER_URL (HTTP $code)"
    else
      blocked "manager unreachable at $WESORT_MANAGER_URL (HTTP $code)"
    fi
    if grep -q "6b6t" /tmp/solo-health.json 2>/dev/null; then
      pass "manager reports 6b6t scope"
    else
      blocked "manager health does not show the configured 6b6t server identity"
    fi
    echo "NOTE: full login/lobby/portal/presence/scan proof still requires the attended"
    echo "live run in docs/P0-ACCEPTANCE.md steps 1-6 on real 6b6t hardware. A server-side"
    echo "restriction stops the run as BLOCKED with the exact missing precondition."
  fi
fi

if [[ "$fail" -eq 0 ]]; then echo "preflight OK"; else echo "preflight BLOCKED (see lines above)"; fi
exit "$fail"
