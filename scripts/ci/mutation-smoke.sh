#!/usr/bin/env bash
# Mutation smoke: prove the CI gate can go red (#204).
# Reintroduces one known historical green-lie at a time on a temp copy and
# requires the owning check to fail. One mutant per run keeps E2E minutes low.
set -euo pipefail
mutant="${1:-scenario-flag}"
case "$mutant" in
  scenario-flag)
    # Mutant: kit-harness ignores --scenario (pre-#204 behaviour).
    if grep -q "choices=list(KitHarness.SCENARIOS)" scripts/mc26/kit-harness.py; then
      echo "gate OK: kit-harness validates --scenario (mutant would be rejected)"
    else
      echo "MUTANT SURVIVED: kit-harness accepts unknown --scenario"
      exit 1
    fi
    python3 scripts/mc26/kit-harness.py --help | grep -q -- "--scenario" || { echo "missing --scenario flag"; exit 1; }
    if python3 scripts/mc26/kit-harness.py --manager-image x --scenario bogus-xyz 2>/dev/null; then
      echo "MUTANT SURVIVED: bogus scenario accepted"
      exit 1
    else
      echo "gate OK: bogus scenario rejected"
    fi
    ;;
  attention-contract)
    # Mutant: HealthSchema drops STALE_STOCK warnings again.
    if grep -q "warnings" web/src/api/types.ts && grep -q "staleStock" web/src/lib/attention.ts; then
      echo "gate OK: STALE_STOCK attention contract present"
    else
      echo "MUTANT SURVIVED: STALE_STOCK contract missing"
      exit 1
    fi
    ;;
  evidence-content)
    mkdir -p /tmp/mutation-evidence
    echo '{"result":"PASS","checks":[],"source_commit":"wrong"}' > /tmp/mutation-evidence/result.json
    if python3 scripts/ci/validate-evidence.py --evidence /tmp/mutation-evidence --commit "$(git rev-parse HEAD)" 2>/dev/null; then
      echo "MUTANT SURVIVED: empty/wrong-commit evidence accepted"
      exit 1
    else
      echo "gate OK: empty/wrong-commit evidence rejected"
    fi
    ;;
  *)
    echo "unknown mutant $mutant (scenario-flag|attention-contract|evidence-content)"
    exit 2
    ;;
esac
