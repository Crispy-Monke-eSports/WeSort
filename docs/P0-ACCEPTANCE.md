# P0 acceptance gates

This document tracks the seven open P0 issues reviewed for PR #205. It is a test plan, not a report of a successful live run.

## Recovery and stock (#197–#201)

- #197: start the dedicated agent with a temporarily unavailable chunk, then make the route available. Verify a fresh guarded route is planned with bounded backoff. Explicit cancellation, unsafe blocks and foreign navigation remain fenced.
- #198: interrupt a shulker pickup after the block breaks; test pickup in a different inventory slot, duplicate identical shulkers, cursor contents and a changed fingerprint. Accept only an unambiguous server-confirmed receipt. Keep unresolved work durable across restart.
- #199: request a rescan with healthy stock. Existing valid observations must remain usable until their real freshness deadline or an actual invalidation. Verify read-only surveys under a fenced lease, without inventory mutation. Acknowledgment must follow completion of the read attempts.
- #200: maintain keepalives on a nonterminal journal while fencing its matching lease. Verify the operation becomes inspectable. Never release its reservations on keepalive alone. Repeat with a different agent and server.
- #201: default stock TTL is 1800 seconds; routine reads keep a 60-second minimum spacing (well below TTL). For 69 containers, run the dedicated agent for at least 5400 seconds after the initial verified scan, with no manual rescan. Sample oldest confirmed observation age and availability at least every five seconds. Require age below 1440 seconds for every reachable source throughout steady state. Record retry and unreachable sources separately. Repeat with a temporary network interruption.

## SQLite (#140)

The credential write fix is not the unified transaction migration. Verify order creation/claim/receipt, inventory reservations, custody, deposit/handoff and audit/outbox on one transaction boundary. Inject errors before commit and after commit before acknowledgment, restart the process, and replay identical and conflicting request IDs. Assert that no resource is double reserved and no physical event is accepted twice. Include missing/corrupt persistence, disk-full, concurrent reservations and SIGKILL tests. Do not erase existing physical evidence to migrate a volume.

## Live 6b6t (#141)

Use an authorized dedicated Minecraft 26.3 client with the production mod and Manager version under test. Never use account credentials in diagnostics.

1. Start Manager with a dedicated agent credential and a disposable, authorized test stash.
2. Log in to 6b6t, record version and UTC timestamps, verify lobby recognition and guarded portal transition.
3. Confirm signed agent presence and server identity in Manager. Record non-sensitive operation IDs and state transitions.
4. Perform a nondestructive inventory read against the authorized source. Compare the confirmed source fingerprint, revision and actual item counts in Manager.
5. Repeat after reconnect, transient navigation failure and a fenced recovery. Confirm no unintended inventory mutation or physical replay.
6. Preserve redacted client/Manager logs and relevant API responses; remove credentials, private coordinates and identifiers unrelated to the test.

If any prerequisite is unavailable, record BLOCKED with the exact missing prerequisite. Do not mark #141 PASS from a local vanilla harness, mocked packets, or CI alone. Run `scripts/ci/solo-agent-preflight.sh` (add `--live` on the 6b6t host) as the gate before any live attempt: it checks the one-time IP/port/API configuration, the 6b6t scope pin and the portal/link-scope unit tests, and reports BLOCKED — never PASS — for everything it cannot prove statically. No human can interact with the agent instance after that configuration, so every step above must complete with no manual resume, rescan or restart.

## Status

All acceptance tests above require evidence on the final PR revision. This plan does not close issues. The long-duration soak, live 6b6t test and full transaction migration have not been completed in this PR revision.
