# 6b6t transport scope: single agent, verified arrival

> **Current product decision (2026-11-23):** WeSort supports one active
> Minecraft 26.3 Fabric/Meteor/Baritone agent. The optional, per-server
> `/tpa` and `/home` transport-profile subsystem (former W-062) has
> been discontinued. This file documents the new boundary, not a supported
> transport API.

## Existing verified building blocks

- `PortalEntry` and `PortalWorldSession` implement a guarded 6b6t
  login-lobby portal bootstrap. These are **not** the deleted upstream
  generic Meteor portal or a general-purpose teleport facility.
- `NavigationRuntime`, `NavigationGuard` and `InventorySync` protect
  local Baritone movement and block/inventory actions from stale or
  unverified physical operations.
- `StashManager` reports signed Manager presence independently of whether
  the agent has safely reached its warehouse.

## Not implemented or guaranteed

- There is no `/api/v1/transport`, `TransportProfiles`,
  `TransportRoutes` or general typed TPA/home-command dispatcher.
- Chat responses, sending commands, a claimed destination or an in-progress
  Baritone path do not establish delivery or safe arrival.
- Neither the private vanilla 26.3 harness nor the existing lobby tests
  prove autonomous full-distance delivery on the live 6b6t server.

## Required delivery work

W-035/W-036 must implement a **single-agent** pickup/courier path with
explicit waypoint/dimension verification, durable custody and safe pause on
disconnect, death, PvP, lag, world transfer or unknown inventory. W-038 and
W-039 require independent, server-observed chest or recipient receipts.
Completion is gated by real 26.3 E2E, plus an explicit 6b6t live check
tracked in [#141](https://github.com/Crispy-Monke-eSports/WeSort/issues/141).

Previous experimental TPA/home design survives only in Git history.
