/**
 * Pure attention-signal helpers for the Overview "Needs attention" card.
 *
 * Every helper here is a pure function over backend-shaped data so the
 * aggregation, classification and watchdog rules are unit-testable without a
 * browser. Components fetch (queries.ts) and render; they must not re-derive
 * these rules inline.
 */

export type AttentionSeverity = "error" | "warning" | "info";

export interface AttentionItem {
  severity: AttentionSeverity;
  text: string;
  /** Router path understood by the target page. */
  to: string;
  /** Router search params (e.g. `{ q }` for the Orders filter). */
  search?: Record<string, string>;
}

export interface OpsAlertLike {
  code: string;
  count: number;
}

/** A bot row as read from `/api/v1/bots` (extra fields tolerated). */
export interface BotLike {
  botId: string;
  player?: string | null;
  online?: boolean | null;
  lastSeen?: number | null;
  lastHeartbeat?: number | null;
  activityAt?: number | null;
  summary?: string | null;
  cause?: string | null;
  [key: string]: unknown;
}

/** An order row as projected by `useAllOrders` (extra fields tolerated). */
export interface OrderLike {
  id: string;
  stash: string;
  status: string;
  agent?: string | null;
  updatedAt?: number;
  message?: string | null;
  retriedAs?: string | null;
  [key: string]: unknown;
}

/** Defensively parsed `/health` body (see queries.ts HealthSchema). */
export interface HealthLike {
  status?: string | null;
  staleIndex?: boolean | null;
  stuckOrders?: number | null;
  /** Joint-health addition: per-order detail; absent on older servers. */
  stuckOrderIds?: unknown;
  recovering?: number | null;
  reservationHoldingOrders?: number | null;
  warnings?: string[] | null;
  staleStock?: boolean | null;
  oldestStockAgeMs?: number | null;
  oldestIndexAgeMs?: number | null;
  checkedAt?: number | null;
  userRegistryUnavailable?: boolean | null;
  [key: string]: unknown;
}

/** A stuck-order entry from the joint-health `/health` detail list. */
export interface StuckOrderLike {
  id: string;
  stash?: string | null;
  ageMs?: number | null;
}

/** Read the joint-health stuck-order detail list defensively. */
export function stuckOrderEntries(health: HealthLike): StuckOrderLike[] {
  const raw = health.stuckOrderIds;
  if (!Array.isArray(raw)) return [];
  const out: StuckOrderLike[] = [];
  for (const e of raw) {
    if (typeof e === "string") {
      out.push({ id: e });
      continue;
    }
    if (e && typeof e === "object") {
      const o = e as Record<string, unknown>;
      if (typeof o.id === "string" && o.id) {
        out.push({
          id: o.id,
          stash: typeof o.stash === "string" ? o.stash : null,
          ageMs: typeof o.ageMs === "number" ? o.ageMs : null,
        });
      }
    }
  }
  return out;
}

/** A bot that reports presence but makes no progress is frozen, not healthy. */
export const SILENT_BOT_MS = 90 * 1000;

/**
 * Only trust a `/health` sample younger than this: the Manager itself only
 * reports ready/degraded inside a 60s window (RuntimeHealth.detail), so an
 * older sample is "unknown", never "ready".
 */
export const HEALTH_FRESH_MS = 60 * 1000;

/** True when the health sample is fresh enough to count as verified quiet. */
export function isHealthFresh(
  health: HealthLike | null | undefined,
  now: number,
): boolean {
  if (!health || typeof health.checkedAt !== "number" || health.checkedAt <= 0)
    return false;
  return now - health.checkedAt < HEALTH_FRESH_MS;
}

/**
 * `/health`-derived signals. stuckOrders>0 and a stale index are errors
 * (the "60s signal"); a missing or out-of-window sample yields nothing here
 * and must instead feed `healthUnknown` at the call site.
 */
export function healthAttention(
  health: HealthLike | null | undefined,
): AttentionItem[] {
  const items: AttentionItem[] = [];
  if (!health) return items;
  const stuck =
    typeof health.stuckOrders === "number" && health.stuckOrders > 0
      ? health.stuckOrders
      : 0;
  if (stuck > 0) {
    const entries = stuckOrderEntries(health);
    if (entries.length > 0) {
      for (const e of entries) {
        const age =
          typeof e.ageMs === "number" && e.ageMs > 0
            ? ` (stuck ${formatAge(e.ageMs)})`
            : "";
        items.push({
          severity: "error",
          text: `Order ${e.id}${e.stash ? ` (${e.stash})` : ""} stuck${age} — inspect before recovery.`,
          to: "/orders",
          search: { q: e.id },
        });
      }
    } else {
      items.push({
        severity: "error",
        text: `Manager reports ${stuck} stuck order${stuck === 1 ? "" : "s"} — inspect before recovery.`,
        to: "/orders",
      });
    }
  }
  if (health.recovering != null && health.recovering > 0) {
    items.push({
      severity: "warning",
      text: `${health.recovering} order${health.recovering === 1 ? " is" : "s are"} in autonomous recovery — watch for stalls.`,
      to: "/operations",
    });
  }
  if (health.staleIndex) {
    items.push({
      severity: "error",
      text: "Stock index is stale — availability data cannot be trusted.",
      to: "/operations",
    });
  }
  const warnings = new Set(health.warnings);
  if (health.staleStock) warnings.add("STALE_STOCK");
  for (const warning of warnings) {
    if (warning === "STALE_STOCK") {
      const age = health.oldestStockAgeMs;
      items.push({
        severity: "warning",
        text: `STALE_STOCK — oldest stock observation: ${age == null ? "unknown age" : formatAge(age)}.`,
        to: "/inventory",
      });
    } else if (
      !(warning === "STALE_INDEX" && health.staleIndex) &&
      !(warning === "RECOVERING_ORDERS" && health.recovering)
    ) {
      items.push({ severity: "warning", text: warning, to: "/operations" });
    }
  }
  if (health.userRegistryUnavailable) {
    items.push({
      severity: "error",
      text: "User registry unavailable — permissions may be wrong.",
      to: "/settings",
    });
  }
  return items;
}

/**
 * Aggregate instead of fold: keep the operations alert count visible on the
 * same row as the problem it corroborates, so one incident is counted once
 * but no signal is silently dropped.
 */
export function withAlertCount(
  text: string,
  code: string,
  count: number | null | undefined,
): string {
  if (typeof count !== "number" || count <= 0) return text;
  return `${text} (+ ${code} ×${count})`;
}

export function alertCount(
  alerts: OpsAlertLike[] | undefined,
  code: string,
): number {
  const hit = (alerts ?? []).find((a) => a.code === code);
  return typeof hit?.count === "number" ? hit.count : 0;
}

// ---------- stale classification ----------

/**
 * STALE_RESCAN is expected fallout of an in-flight rescan: muted/info.
 * STALE_AGE, MISSING, UNREACHABLE (and UNKNOWN) need an operator: actionable.
 *
 * Older servers only emit the bare `STALE` code for every STALE_* stock
 * state, so map that defensively: when any visible stash has a rescan
 * pending, treat bare STALE as rescan-expected; otherwise actionable.
 */
export function classifyStaleAlert(
  code: string,
  rescanPending: boolean,
): { severity: "info" | "warning"; muted: boolean } {
  if (code === "STALE_RESCAN" || code === "STALE_STARTUP") {
    return { severity: "info", muted: true };
  }
  if (code === "STALE") {
    return rescanPending
      ? { severity: "info", muted: true }
      : { severity: "warning", muted: false };
  }
  return { severity: "warning", muted: false };
}

/** Stock-state keys that count as "stale" in the operations `stock` map. */
export function isStaleStockState(state: string): boolean {
  return state === "STALE" || state.startsWith("STALE_");
}

// ---------- silent-bot watchdog ----------

/**
 * Bots that are "online" (transport heartbeat) but silent on progress while
 * holding open work. Judged from the server clock (`serverNow`, e.g. the
 * operations `at` field) and the lease/order tables — never from the
 * agent-reported summary text.
 *
 * A bot counts as silent when it holds at least one open order whose last
 * server-side update is older than SILENT_BOT_MS and the bot itself has
 * reported no activity within the same window.
 */
export function silentBots(
  bots: BotLike[],
  openOrders: OrderLike[],
  serverNow: number,
): { bot: BotLike; orders: OrderLike[]; silentMs: number }[] {
  const out: { bot: BotLike; orders: OrderLike[]; silentMs: number }[] = [];
  for (const bot of bots) {
    if (!bot.online) continue;
    const mine = openOrders.filter((o) => orderHeldBy(o, bot));
    if (mine.length === 0) continue;
    const oldestUpdate = Math.min(
      ...mine.map((o) =>
        typeof o.updatedAt === "number" && o.updatedAt > 0
          ? o.updatedAt
          : Number.POSITIVE_INFINITY,
      ),
    );
    if (!Number.isFinite(oldestUpdate)) continue;
    const lastProgress = latestReport(bot);
    const workSilentMs = serverNow - oldestUpdate;
    const botSilentMs =
      lastProgress != null ? serverNow - lastProgress : workSilentMs;
    if (workSilentMs >= SILENT_BOT_MS && botSilentMs >= SILENT_BOT_MS) {
      out.push({
        bot,
        orders: mine,
        silentMs: Math.min(workSilentMs, botSilentMs),
      });
    }
  }
  return out;
}

/** Does the server still hold this open order against this bot? */
export function orderHeldBy(order: OrderLike, bot: BotLike): boolean {
  const agent = typeof order.agent === "string" ? order.agent : null;
  if (!agent) return false;
  if (agent === bot.botId) return true;
  return !!bot.player && agent === bot.player;
}

/** Newest server-observed report timestamp for a bot (null when never). */
export function latestReport(bot: BotLike): number | null {
  const stamps = [bot.lastSeen, bot.lastHeartbeat, bot.activityAt].filter(
    (t): t is number => typeof t === "number" && t > 0,
  );
  return stamps.length ? Math.max(...stamps) : null;
}

const IDLE_SUMMARY =
  /idle|ready|waiting|no (running )?tasks?|nothing (to do|running)/i;

/**
 * Agent-vs-server mismatch: the agent's own summary claims it is idle while
 * the server still holds open work against it. Its own warning so a lying
 * (or stale) summary cannot mask a stuck bot.
 */
export function agentServerMismatch(
  bot: BotLike,
  openOrders: OrderLike[],
): boolean {
  if (!bot.online) return false;
  if (typeof bot.summary !== "string" || !IDLE_SUMMARY.test(bot.summary)) {
    return false;
  }
  return openOrders.some((o) => orderHeldBy(o, bot));
}

// ---------- failed stashes ----------

/** One signal per stash whose orders failed to load, each linking at it. */
export function failedStashItems(stashes: string[]): AttentionItem[] {
  return [...stashes].sort().map((stash) => ({
    severity: "error" as const,
    text: `Order data unavailable for ${stash} — the lists are partial.`,
    to: "/orders",
    search: { q: stash },
  }));
}

// ---------- truncation ----------

/**
 * Replace silent slice() truncation: show the head of a long list plus an
 * explicit "+N more" remainder the operator can expand.
 */
export function visibleWithOverflow<T>(
  items: T[],
  limit: number,
  expanded: boolean,
): { visible: T[]; hidden: number } {
  if (expanded || items.length <= limit) {
    return { visible: items, hidden: 0 };
  }
  return { visible: items.slice(0, limit), hidden: items.length - limit };
}

function formatAge(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000));
  if (s < 60) return `${s}s`;
  if (s < 3600) return `${Math.floor(s / 60)}m`;
  if (s < 86400) return `${Math.floor(s / 3600)}h`;
  return `${Math.floor(s / 86400)}d`;
}

// ---------- journal / lease display ----------

/**
 * Journal/lease display state from Manager-side fields: autonomous recovery,
 * a receipt the Manager has yet to confirm, a clean ready journal, or the
 * yielded raw state when nothing more specific applies. Unknown shapes fall
 * back to the raw manager (then inspection) state, never hidden.
 */
export function journalDisplayState(op: {
  managerState?: string | null;
  inspectionState?: string | null;
}): string {
  const manager = (op.managerState ?? "").toUpperCase();
  const inspection = (op.inspectionState ?? "").toUpperCase();
  if (manager === "RECOVERING" || manager === "NEEDS_RECONCILIATION") {
    return "AUTO_RECOVERING";
  }
  if (
    inspection === "REQUESTED" ||
    inspection === "PENDING" ||
    inspection === "AWAITING_RECEIPT"
  ) {
    return "WAITING_MANAGER_RECEIPT";
  }
  if (manager === "COMPLETED" || manager === "READY") return "READY";
  return String(op.managerState ?? op.inspectionState ?? "unknown");
}
