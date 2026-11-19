# CI architecture

The four GitHub Actions workflows are:

- `ci.yml`: the entry point for **every PR**, pushes to `main`, nightly
  runs and manual full-suite runs. Also called by the release workflow.
- `_verify.yml`: client/Manager/web tests, a single source-bound Manager
  Docker image, and browser/TLS/Docker smoke tests.
- `_mc26-e2e.yml`: **all** original Minecraft 26.3 E2E harnesses, consuming
  the exact image built by verification.
- `manager-image.yml`: guarded manual or tag release; full CI first, then
  versioned candidate, SBOM, publish and anonymous pull verification.

The 34 original matrix scenarios run in ten isolated sequential shards:
journal (9), kits (10) and repack (15). Each keeps its own 25-minute timeout.
A failed scenario is reported, later scenarios continue, and the shard fails.
Nine additional standalone E2E suites are unchanged: assets/UI,
client-runtime, hierarchy, inventory, navigation, sorting, stock, toolchain
and world.

The shared Manager image is built once per run with BuildKit/GHA cache,
exported to a zstd-compressed artifact (7-day retention), loaded in each
Docker-dependent job, and verified by image ID and source revision. No image
is published to any registry during CI.

The stable required branch-protection check is `ci-required`. Update
GitHub branch protection/rulesets to use this check after the new draft PR
has run. Old per-workflow status names no longer exist. If compilation or
unit tests fail, E2E is skipped to conserve runner resources, but the
required check is failed. If any E2E job fails, the required check fails.

A release uses read-only permission for verification and only elevates
to `packages: write` inside the guarded publish job; release workflows
never auto-cancel. Manual publication remains explicitly gated.

Before merging, compare runtime, queue time, runner-minutes and artifact
transfer against the old workflows. Batching and shared images are
engineering hypotheses, not yet measured performance improvements.
