# 6b6t lobby: safe portal entry and Manager presence

On a 6b6t endpoint (`6b6t.org` and subdomains, with/without port) the
client may join a **lobby** rather than survival. The internal sorter must
cross the lobby portal before any warehouse inventory interaction.

## How it works

- **Per-login state:** `PortalWorldSession` is re-armed by `GameLeftEvent`.
  Every new login starts with a fresh world snapshot; a stale `portalArrived`
  state from the previous connection cannot skip the lobby. Unrelated world
  reloads *before* an entry attempt do not count as successful crossing.
- **Bootstrap without a stash:** the always-subscribed sorter tracker drives
  portal navigation for a configured, active Manager Agent even before
  `bot/hello` has assigned a stash or a sorting permit exists. Normal inventory
  and physical work remain behind the existing Manager/journal safety gates.
- **Walkable approach:** scan loaded chunks for `minecraft:nether_portal`,
  choose the lower portal block and compute the adjacent standing cell
  perpendicular to its `AXIS`. Only select a cell with a clear two-block-high
  hitbox and solid floor. Baritone walks **to the approach**, not the
  non-solid portal block, so it no longer stops in front of the surface.
- **Final crossing:** at the approach, release only the agent-owned Baritone
  goal, face the center of the portal and temporarily hold forward to enter
  it. Release the synthetic forward key on portal contact, loss of focus,
  retry, logout and success. Manual portal contact is recognized too.
- **Recovery:** the approach stalls after 200 ticks (10 seconds) without
  position progress; explicit crossing times out after 100 ticks (5 seconds)
  outside the portal. In either case, release our goal/key, choose the other
  side and scan again. Being inside the portal suppresses repeated movement
  and warns after 20 seconds if the server has not transferred the player.
- **Confirmation:** only a new `ClientLevel` instance or dimension **after
  a crossing attempt** releases the inventory/jobs gate. This is also what
  happens when the 6b6t proxy transfers the player to survival.

## Production reproduction (22 November 2026; main at 3dd9b8c)

The client log for Minecraft 26.3 on `6b6t.org:25565` shows:

- 13:34:51: lobby joined, Manager stash `main` adopted, portal transit started
- 13:34:53: `PAUSED_NAVIGATION_UNSAFE_BLOCK` blocked the autonomous route
- 13:35:13: manual proxy transfer to a survival worker; pause remained
- 13:38:42: explicit logout/rejoin; pause persisted on the new login
- 13:39:15: another world transfer still left navigation paused

The actual pre-patch navigation guard rejects a route cell containing
`minecraft:nether_portal` (classified as a safety-sensitive block), and the
generic navigator latches the pause until `.wesort-navigation resume`.
The previous portal goal aimed at a portal block itself. The production log
does not include coordinates identifying the rejected cell, so other unsafe
route blocks cannot be excluded solely from this evidence.

The fix keeps the generic safety classification unchanged. A dedicated
`watchPortalApproach` route can be started without completed inventory
readback **only** if the active Manager Agent is awaiting a real loaded portal,
the approach is perpendicular to the portal plane, the approach is checked
by the normal navigation guard, and the physical journal is clear. All route
cells continue to be checked by `NavigationGuard`; rejected routes log the
precise blocked block/position for diagnosis. Only a portal-scoped pause is
released at disconnect, allowing a new guarded attempt on the next login.
An ordinary unsafe warehouse route remains paused until manual approval.

Manager `status.txt`: healthy, zero restarts, configured Minecraft server
`6b6t.org`. Neither that status nor the startup-only Docker log proves a
successful signed heartbeat, so authenticated presence must be verified
in a live test separately.

## Manager online status

The signed `/api/v1/bot/*` control channel now starts even if the lobby
has not provided inventory readback or the Manager has not announced a
stash. Its authenticated hello/state/long poll establishes agent presence
independently of warehouse readiness. A recent signed 200/204 response for
the **current link scope** is reported as Online in the connection UI; real
authentication errors, an unreachable Manager, bad signatures and wrong
server responses still override that state. Stale replies (over 90 seconds)
never keep a disconnected agent Online.

This does **not** loosen the server binding: Manager still requires the
exact server address configured in its server settings for physical
heartbeats and index uploads. If the Agent reports `wrong server`, verify
that the configured Manager Minecraft address matches
`Stash.currentServer()` (including port).

## Regression checks

- `PortalEntryTest`: approach orientation on both portal axes, free
  crossing geometry, arrival distance and camera heading.
- `PortalWorldSessionTest`: manual/proxy transfer detection, unrelated
  world changes, and especially logout/rejoin requiring another portal entry.
- `PortalNavigationPolicyTest`: refuses all missing prerequisites for
  inventory-independent portal navigation and prevents general safety pauses
  from resetting on logout.
- `ConnectionStateTest`: signed presence while inventory/stash unavailable,
  stale authentication and concrete network/authentication error precedence.
- `./gradlew test` and the Minecraft 26.3 Baritone client-runtime/E2E test
  matrix are required before merge. A passing unit suite does **not** by
  itself prove live entry on 6b6t. Verify on an actual lobby and check that
  the Agent appears online before and after portal transfer.

## Limits

Baritone is required for autonomous approach walking. Mojang login and
6b6t-specific identity/captcha checks are not bypassed. A player can still
enter the portal manually, and the agent must not fabricate survival
arrival or a verified Manager connection.
