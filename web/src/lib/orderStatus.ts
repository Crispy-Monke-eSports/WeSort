/**
 * Backend order-status contract → UI (P2-17). The vocabulary comes from the
 * Java backend, not from hand-written frontend guesses:
 *
 * - OrderStore.OPEN = pending, accepted, in_progress (mutable; cancellable).
 * - OrderStore.FINISHED = delivered, packed, failed, cancelled,
 *   needs_reconciliation (terminal; cancel refused with 409).
 * - OrderStore REPORTED_TERMINAL + the retry gate allow retry only for
 *   failed/cancelled (delivered/packed/needs_reconciliation are NOT retryable).
 * - OrderStage adds PACKING, PICKUP_READY (reported as "packed"),
 *   PICKED_UP, IN_TRANSIT, AWAITING_CUSTOMER, DELIVERED, FAILED,
 *   NEEDS_RECONCILIATION, CANCELLED — mapped defensively in case an API
 *   ever surfaces stage names.
 *
 * Unknown strings stay visible (never hidden) with a neutral chip.
 */

export type OrderSeverity = "success" | "error" | "info" | "warning" | "default";

export interface OrderUiState {
  severity: OrderSeverity;
  /** Still open server-side (not in FINISHED). */
  open: boolean;
  /** Cancel / force-cancel may be attempted. */
  cancellable: boolean;
  /** Retry-as-new-order may be attempted. */
  retryable: boolean;
  /** Needs operator attention (failure / reconciliation). */
  needsAttention: boolean;
}

/** Every status string the backend can actually emit, plus legacy aliases. */
export const ORDER_STATUSES = [
  "pending",
  "accepted",
  "in_progress",
  "packed",
  "needs_reconciliation",
  "delivered",
  "failed",
  "cancelled",
] as const;

const STATES: Record<string, OrderUiState> = {
  pending: { severity: "default", open: true, cancellable: true, retryable: false, needsAttention: false },
  accepted: { severity: "info", open: true, cancellable: true, retryable: false, needsAttention: false },
  in_progress: { severity: "info", open: true, cancellable: true, retryable: false, needsAttention: false },
  // PACKING/PICKUP fleet states surface as info; "packed" is the reported terminal.
  packing: { severity: "info", open: true, cancellable: true, retryable: false, needsAttention: false },
  packed: { severity: "success", open: false, cancellable: false, retryable: false, needsAttention: false },
  picked_up: { severity: "info", open: false, cancellable: false, retryable: false, needsAttention: false },
  in_transit: { severity: "info", open: false, cancellable: false, retryable: false, needsAttention: false },
  awaiting_customer: { severity: "info", open: false, cancellable: false, retryable: false, needsAttention: false },
  delivered: { severity: "success", open: false, cancellable: false, retryable: false, needsAttention: false },
  failed: { severity: "error", open: false, cancellable: false, retryable: true, needsAttention: true },
  cancelled: { severity: "error", open: false, cancellable: false, retryable: true, needsAttention: true },
  "force-cancelled": { severity: "error", open: false, cancellable: false, retryable: false, needsAttention: true },
  needs_reconciliation: { severity: "warning", open: false, cancellable: false, retryable: false, needsAttention: true },
  // Legacy/alias strings some older payloads used: visible, never hidden.
  completed: { severity: "success", open: false, cancellable: false, retryable: false, needsAttention: false },
  done: { severity: "success", open: false, cancellable: false, retryable: false, needsAttention: false },
  queued: { severity: "default", open: true, cancellable: true, retryable: false, needsAttention: false },
  staging: { severity: "info", open: true, cancellable: true, retryable: false, needsAttention: false },
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
