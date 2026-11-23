import { useEffect, useRef } from "react";
import { useQueryClient, type QueryClient } from "@tanstack/react-query";
import { create } from "zustand";
import { keys } from "./queries";

/**
 * Live updates: one Server-Sent Events stream (GET /api/v1/events) per tab,
 * turned into TanStack Query invalidations. This replaces interval polling —
 * the server pushes a channel signal the moment something changes, and the
 * affected queries refetch once (de-duplicated by the QueryClient).
 *
 * Channel → query-key mapping (prefix invalidation, `exact: false`):
 * - orders      → per-stash order lists, operations counts, stash summaries, audit
 * - index/stock/stashes → guild index per stash, stash list, operations
 * - bots        → bot list, per-bot activity/settings, operations
 * - presence    → stash list (online flags), bot list (heartbeats/offline sweeps)
 * - operations  → operations view plus every owner queue
 * - unknown     → the whole live set (safe default for future channels)
 */

const LIVE_PREFIXES: readonly unknown[][] = [
  ["orders"],
  ["index"],
  ["stashes"],
  ["bots"],
  ["operations"],
  ["server"],
  ["audit", "orders"],
  ["physical"],
  ["jobs"],
  ["handoffs"],
  ["custody"],
  ["deposits"],
  ["boxes", "jobs"],
  ["guild"],
  ["kits"],
  ["stash-defs"],
  ["users"],
  ["quotas"],
  ["auth"],
  ["agents"],
];

/** TanStack Query key prefixes invalidated for a set of live channels. */
export function prefixesForChannels(channels: string[]): unknown[][] {
  const has = (...names: string[]) => names.some((n) => channels.includes(n));
  const out: unknown[][] = [];
  const add = (prefix: unknown[]) => {
    if (
      !out.some(
        (p) => p.length === prefix.length && p.every((v, i) => v === prefix[i]),
      )
    )
      out.push(prefix);
  };
  if (has("orders")) {
    add(["orders"]);
    add(["operations"]);
    add(["stashes"]);
    add(["audit", "orders"]);
  }
  if (has("index", "stock", "stashes")) {
    add(["index"]);
    add(["stashes"]);
    add(["operations"]);
  }
  if (has("bots")) {
    add(["bots"]);
    add(["operations"]);
  }
  if (has("presence")) {
    add(["stashes"]);
    add(["bots"]);
    add(["operations"]);
  }
  if (has("settings")) {
    for (const key of [
      ["guild"],
      ["kits"],
      ["stash-defs"],
      ["users"],
      ["quotas"],
      ["server"],
      ["stashes"],
      ["auth"],
      ["agents"],
    ])
      add(key);
  }
  if (has("auth")) add(["auth"]);
  if (has("operations")) {
    add(["operations"]);
    add(["physical"]);
    add(["jobs"]);
    add(["handoffs"]);
    add(["custody"]);
    add(["deposits"]);
    add(["boxes", "jobs"]);
    add(["audit", "orders"]);
  }
  if (out.length === 0) return LIVE_PREFIXES.map((p) => [...p]);
  return out;
}

/** Invalidates every live query (resync after reconnect). */
export function invalidateLive(client: QueryClient, channels?: string[]) {
  for (const prefix of channels
    ? prefixesForChannels(channels)
    : LIVE_PREFIXES) {
    void client.invalidateQueries({ queryKey: prefix });
  }
}

export type LiveStatus = "live" | "connecting" | "offline";

interface LiveState {
  status: LiveStatus;
  rev: number;
}

export const useLiveStore = create<LiveState>(() => ({
  status: "offline",
  rev: 0,
}));

function setLive(patch: Partial<LiveState>) {
  useLiveStore.setState(patch);
}

/** Parses an SSE `update` payload into channel names (never throws). */
export function parseUpdate(data: string): string[] {
  try {
    const json = JSON.parse(data) as { channels?: unknown };
    if (!Array.isArray(json.channels)) return [];
    return json.channels.filter((c): c is string => typeof c === "string");
  } catch {
    return [];
  }
}

/**
 * Minimum time between session revalidations triggered by stream errors.
 * The server rotates every stream (~50 s, a routine reconnect), so an
 * unthrottled check would refetch auth on every rotation for no reason.
 */
export const AUTH_CHECK_THROTTLE_MS = 30_000;

/** Whether a stream error should revalidate the session (throttled). Pure for tests. */
export function authCheckDue(lastCheck: number, now: number): boolean {
  return now - lastCheck >= AUTH_CHECK_THROTTLE_MS;
}

/**
 * Opens the live stream while `enabled` and keeps TanStack Query current.
 * On (re)connect the live set resyncs, so a dropped connection can only
 * delay updates — never strand stale data on screen.
 */
export function useLiveUpdates(enabled: boolean, streamUrl = "/api/v1/events") {
  const client = useQueryClient();
  const lastAuthCheck = useRef(0);
  useEffect(() => {
    if (!enabled || typeof EventSource === "undefined") return;
    let source: EventSource | null = null;
    let flushTimer: ReturnType<typeof setTimeout> | null = null;
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
    let pending = new Set<string>();
    let stopped = false;

    const flush = () => {
      flushTimer = null;
      if (pending.size === 0) return;
      invalidateLive(client, [...pending]);
      pending = new Set<string>();
    };
    const queue = (channels: string[]) => {
      for (const c of channels) pending.add(c);
      if (flushTimer === null) flushTimer = setTimeout(flush, 250);
    };
    const onUpdate = (event: MessageEvent) => {
      const data = String(event.data ?? "");
      const channels = parseUpdate(data);
      try {
        const rev = (JSON.parse(data) as { rev?: unknown }).rev;
        if (typeof rev === "number") setLive({ rev });
      } catch {
        /* rev is advisory */
      }
      queue(channels.length ? channels : ["__unknown__"]);
    };
    const connect = () => {
      if (stopped) return;
      setLive({ status: "connecting" });
      const current = new EventSource(streamUrl);
      source = current;
      current.onopen = () => {
        if (stopped) return;
        setLive({ status: "live" });
        invalidateLive(client); // replay everything missed during a disconnection
      };
      current.addEventListener("update", onUpdate as EventListener);
      current.onerror = () => {
        if (stopped) return;
        if (current.readyState === EventSource.CLOSED) {
          // HTTP 503 when all stream permits are occupied can close EventSource
          // permanently. Unlike ordinary network errors, it needs an explicit retry.
          setLive({ status: "offline" });
          current.removeEventListener("update", onUpdate as EventListener);
          current.close();
          if (source === current) source = null;
          if (reconnectTimer === null) {
            reconnectTimer = setTimeout(() => {
              reconnectTimer = null;
              connect();
            }, 5000);
          }
        } else {
          // EventSource performs its own retry while in CONNECTING state.
          setLive({ status: "connecting" });
        }
        const now = Date.now();
        if (authCheckDue(lastAuthCheck.current, now)) {
          lastAuthCheck.current = now;
          void client.invalidateQueries({ queryKey: keys.auth });
        }
      };
    };
    connect();
    return () => {
      stopped = true;
      if (flushTimer !== null) clearTimeout(flushTimer);
      if (reconnectTimer !== null) clearTimeout(reconnectTimer);
      source?.removeEventListener("update", onUpdate as EventListener);
      source?.close();
      setLive({ status: "offline" });
    };
  }, [enabled, streamUrl, client]);
}
