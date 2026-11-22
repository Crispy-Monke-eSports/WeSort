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
];

/** TanStack Query key prefixes invalidated for a set of live channels. */
export function prefixesForChannels(channels: string[]): unknown[][] {
  const has = (...names: string[]) => names.some((n) => channels.includes(n));
  const out: unknown[][] = [];
  const add = (prefix: unknown[]) => {
    if (!out.some((p) => p.length === prefix.length && p.every((v, i) => v === prefix[i]))) out.push(prefix);
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
  for (const prefix of channels ? prefixesForChannels(channels) : LIVE_PREFIXES) {
    void client.invalidateQueries({ queryKey: prefix });
  }
}

export type LiveStatus = "live" | "connecting" | "offline";

interface LiveState {
  status: LiveStatus;
  rev: number;
}

export const useLiveStore = create<LiveState>(() => ({ status: "offline", rev: 0 }));

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
    setLive({ status: "connecting" });
    const source = new EventSource(streamUrl);
    let timer: ReturnType<typeof setTimeout> | null = null;
    let pending = new Set<string>();

    const flush = () => {
      timer = null;
      if (pending.size === 0) return;
      invalidateLive(client, [...pending]);
      pending = new Set<string>();
    };
    const queue = (channels: string[]) => {
      for (const c of channels) pending.add(c);
      if (timer !== null) return;
      timer = setTimeout(flush, 250);
    };

    source.onopen = () => {
      setLive({ status: "live" });
      invalidateLive(client); // resync: cover everything missed while away
    };
    const onUpdate = (event: MessageEvent) => {
      const channels = parseUpdate(String((event as MessageEvent).data ?? ""));
      try {
        const rev = (JSON.parse(String((event as MessageEvent).data ?? "")) as { rev?: unknown }).rev;
        if (typeof rev === "number") setLive({ rev });
      } catch {
        /* rev is advisory */
      }
      queue(channels.length ? channels : ["__unknown__"]);
    };
    source.addEventListener("update", onUpdate as EventListener);
    source.onerror = () => {
      // EventSource retries by itself; only mark offline when it gives up.
      if (source.readyState === EventSource.CLOSED) setLive({ status: "offline" });
      else setLive({ status: "connecting" });
      // The stream dying can mean the session is gone (revoked, disabled,
      // logged out elsewhere): revalidate auth so a 401 bounces to login
      // promptly instead of stranding a stale-looking session. Throttled —
      // routine ~50 s stream rotations must not refetch auth every time.
      const now = Date.now();
      if (authCheckDue(lastAuthCheck.current, now)) {
        lastAuthCheck.current = now;
        void client.invalidateQueries({ queryKey: keys.auth });
      }
    };
    return () => {
      if (timer !== null) clearTimeout(timer);
      source.removeEventListener("update", onUpdate as EventListener);
      source.close();
      setLive({ status: "offline" });
    };
  }, [enabled, streamUrl, client]);
}
