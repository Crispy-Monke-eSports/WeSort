/**
 * Backend order-status contract → UI. The vocabulary comes from the
 * Java backend, not from hand-written frontend guesses:
 *
 * - OrderStore.OPEN = pending, accepted, in_progress (mutable; cancellable).
 * - OrderStore.FINISHED = delivered, packed, failed, cancelled.
 * - Failed or needs_reconciliation orders are operator-visible problems:
 *   they never resolve on their own and must surface in "Needs attention".
 * - RECOVERING is an autonomous rollback stage: quiet while fresh, but a
 *   rollback that never finishes is stuck automation, so needsAttentionNow()
 *   flags it once it ages past RECOVERING_ATTENTION_MS (mirroring the
 *   server's AUTO_CANCEL_AFTER_MS horizon).
 * - OrderStage adds PACKING, RECOVERING, PICKUP_READY, PICKED_UP,
 *   IN_TRANSIT, AWAITING_CUSTOMER, DELIVERED, FAILED, CANCELLED.
 *   PICKED_UP/IN_TRANSIT/AWAITING_CUSTOMER/DELIVERED are reserved for a
 *   future custody flow: the current bot API never emits them (a bot
 *   `delivered` report means warehouse staging and lands on PICKUP_READY),
 *   so the implemented terminal set is PICKUP_READY plus the verified
 *   sweep release. The entries below stay so older payloads never hide.
 *
 * Unknown strings stay visible (never hidden) with a neutral chip.
 */

export type OrderSeverity =
  "success" | "error" | "info" | "warning" | "default";

export interface OrderUiState {
  severity: OrderSeverity;
  /** Still open server-side (not in FINISHED). */
  open: boolean;
  /** Cancel / force-cancel may be attempted. */
  cancellable: boolean;
  /** Retry-as-new-order may be attempted. */
  retryable: boolean;
  /** Requires human action; automated recovery must not generate these alerts. */
  needsAttention: boolean;
}

/** Every status string the backend can actually emit, plus legacy aliases. */
export const ORDER_STATUSES = [
  "pending",
  "accepted",
  "in_progress",
  "packed",
  "delivered",
  "failed",
  "cancelled",
] as const;

const STATES: Record<string, OrderUiState> = {
  pending: {
    severity: "default",
    open: true,
    cancellable: true,
    retryable: false,
    needsAttention: false,
  },
  accepted: {
    severity: "info",
    open: true,
    cancellable: true,
    retryable: false,
    needsAttention: false,
  },
  in_progress: {
    severity: "info",
    open: true,
    cancellable: true,
    retryable: false,
    needsAttention: false,
  },
  // PACKING/PICKUP fleet states surface as info; "packed" is the reported terminal.
  packing: {
    severity: "info",
    open: true,
    cancellable: true,
    retryable: false,
    needsAttention: false,
  },
  packed: {
    severity: "success",
    open: false,
    cancellable: false,
    retryable: false,
    needsAttention: false,
  },
  picked_up: {
    severity: "info",
    open: false,
    cancellable: false,
    retryable: false,
    needsAttention: false,
  },
  in_transit: {
    severity: "info",
    open: false,
    cancellable: false,
    retryable: false,
    needsAttention: false,
  },
  awaiting_customer: {
    severity: "info",
    open: false,
    cancellable: false,
    retryable: false,
    needsAttention: false,
  },
  delivered: {
    severity: "success",
    open: false,
    cancellable: false,
    retryable: false,
    needsAttention: false,
  },
  failed: {
    severity: "error",
    open: false,
    cancellable: false,
    retryable: true,
    needsAttention: true,
  },
  cancelled: {
    severity: "default",
    open: false,
    cancellable: false,
    retryable: true,
    needsAttention: false,
  },
  "force-cancelled": {
    severity: "default",
    open: false,
    cancellable: false,
    retryable: false,
    needsAttention: false,
  },
  // A failed reconciliation gate never clears on its own: the operator must
  // inspect the physical box before recovery. Always attention-worthy.
  needs_reconciliation: {
    severity: "error",
    open: false,
    cancellable: false,
    retryable: true,
    needsAttention: true,
  },
  recovering: {
    severity: "info",
    open: true,
    cancellable: false,
    retryable: false,
    needsAttention: false,
  },
  // Legacy/alias strings some older payloads used: visible, never hidden.
  completed: {
    severity: "success",
    open: false,
    cancellable: false,
    retryable: false,
    needsAttention: false,
  },
  done: {
    severity: "success",
    open: false,
    cancellable: false,
    retryable: false,
    needsAttention: false,
  },
  queued: {
    severity: "default",
    open: true,
    cancellable: true,
    retryable: false,
    needsAttention: false,
  },
  staging: {
    severity: "info",
    open: true,
    cancellable: true,
    retryable: false,
    needsAttention: false,
  },
};

const FALLBACK: OrderUiState = {
  severity: "default",
  open: true,
  cancellable: false,
  retryable: false,
  needsAttention: false,
};

export function orderUiState(status: string | null | undefined): OrderUiState {
  const key = String(status ?? "unknown").toLowerCase();
  return STATES[key] ?? FALLBACK;
}

/**
 * A terminal order placed again as a new order (backend `retriedAs`) stands for
 * its successor: on its own it no longer requires anything. The attention lists
 * hide these by default so they show current errors only; the successor still
 * shows if it needs attention.
 */
type AttentionOrder =
  | { status?: unknown; retriedAs?: unknown; updatedAt?: unknown }
  | null
  | undefined;

export function isSuperseded(order: AttentionOrder): boolean {
  return !!order?.retriedAs;
}

/**
 * Autonomous rollback horizon: a RECOVERING order older than this is stuck
 * automation, not healthy progress. Mirrors the server's AUTO_CANCEL_AFTER_MS
 * (OrderStore, 10 minutes) so the dashboard and the Manager agree on "stuck".
 */
export const RECOVERING_ATTENTION_MS = 10 * 60 * 1000;

/**
 * Attention-list membership: a mapped attention state (or the frontend
 * `uncertain` checkout state) that is not superseded by a retry — plus a
 * time-based rule for `recovering`, whose static map stays quiet while the
 * rollback is fresh. `now` defaults to the client clock; pass the server
 * clock (e.g. operations `at`) when one is available.
 */
export function needsAttentionNow(
  status: string | null | undefined,
  order?: AttentionOrder,
  now?: number,
): boolean {
  const s = String(status ?? "unknown").toLowerCase();
  if (isSuperseded(order)) return false;
  if (s === "recovering") {
    const updated =
      typeof order?.updatedAt === "number" ? order.updatedAt : null;
    if (updated == null || updated <= 0) return false;
    return (now ?? Date.now()) - updated >= RECOVERING_ATTENTION_MS;
  }
  if (!orderUiState(s).needsAttention && s !== "uncertain") return false;
  return true;
}
