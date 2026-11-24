import { describe, expect, it } from "vitest";
import {
  ORDER_STATUSES,
  isSuperseded,
  needsAttentionNow,
  orderUiState,
} from "../lib/orderStatus";

// P2-17: the frontend status mapping must follow the backend contract
// (OrderStore OPEN/FINISHED, OrderStage, retry gate) — not hand-written drift.
describe("order status contract (P2-17)", () => {
  it("covers exactly the backend vocabulary", () => {
    expect([...ORDER_STATUSES].sort()).toEqual(
      [
        "accepted",
        "cancelled",
        "delivered",
        "failed",
        "in_progress",
        "packed",
        "pending",
      ].sort(),
    );
  });

  it("open states are cancellable and not retryable", () => {
    for (const s of ["pending", "accepted", "in_progress"]) {
      const st = orderUiState(s);
      expect(st.open).toBe(true);
      expect(st.cancellable).toBe(true);
      expect(st.retryable).toBe(false);
    }
  });

  it("only failed/cancelled are retryable (backend retry gate)", () => {
    expect(orderUiState("failed").retryable).toBe(true);
    expect(orderUiState("cancelled").retryable).toBe(true);
    for (const s of [
      "pending",
      "accepted",
      "in_progress",
      "packed",
      "delivered",
    ]) {
      expect(orderUiState(s).retryable).toBe(false);
    }
  });

  it("finished states are terminal", () => {
    for (const s of ["packed", "delivered", "failed", "cancelled"]) {
      const st = orderUiState(s);
      expect(st.open).toBe(false);
      expect(st.cancellable).toBe(false);
    }
  });

  it("failed and needs_reconciliation need operator action; recovering is quiet while fresh", () => {
    expect(orderUiState("failed").needsAttention).toBe(true);
    expect(orderUiState("needs_reconciliation").needsAttention).toBe(true);
    expect(orderUiState("recovering").open).toBe(true);
    expect(orderUiState("recovering").needsAttention).toBe(false);
    expect(orderUiState("packed").needsAttention).toBe(false);
    expect(orderUiState("delivered").needsAttention).toBe(false);
    expect(orderUiState("pending").needsAttention).toBe(false);
  });

  it("never hides unknown statuses", () => {
    expect(orderUiState("something-new").severity).toBe("default");
    expect(orderUiState(null).severity).toBe("default");
  });
});

describe("attention lists (superseded retries)", () => {
  it("a retried order stands for its successor and needs nothing on its own", () => {
    expect(isSuperseded({ status: "failed", retriedAs: "o-new" })).toBe(true);
    expect(isSuperseded({ status: "failed" })).toBe(false);
    expect(isSuperseded({ status: "failed", retriedAs: null })).toBe(false);
    expect(isSuperseded(null)).toBe(false);
  });

  it("superseded failures are hidden from the attention list by default", () => {
    expect(needsAttentionNow("failed", { retriedAs: "o-new" })).toBe(false);
    expect(needsAttentionNow("cancelled", { retriedAs: "o-new" })).toBe(false);
    expect(needsAttentionNow("failed", { retriedAs: null })).toBe(true);
    expect(needsAttentionNow("failed", null)).toBe(true);
    expect(needsAttentionNow("needs_reconciliation", null)).toBe(true);
    expect(
      needsAttentionNow("needs_reconciliation", { retriedAs: "o-new" }),
    ).toBe(false);
    expect(needsAttentionNow("packed", { retriedAs: "o-new" })).toBe(false);
    expect(needsAttentionNow("uncertain", null)).toBe(true);
  });

  it("recovering flags only once the rollback itself looks stuck", () => {
    const now = 1_000_000_000;
    // Fresh rollback: autonomous work, quiet.
    expect(
      needsAttentionNow("recovering", { updatedAt: now - 60_000 }, now),
    ).toBe(false);
    // Aged past the server's AUTO_CANCEL horizon: stuck automation.
    expect(
      needsAttentionNow("recovering", { updatedAt: now - 11 * 60_000 }, now),
    ).toBe(true);
    // Unknown age proves nothing: do not flag.
    expect(needsAttentionNow("recovering", {}, now)).toBe(false);
    expect(needsAttentionNow("recovering", null, now)).toBe(false);
    // A retried-away recovering order stands for its successor.
    expect(
      needsAttentionNow(
        "recovering",
        { updatedAt: now - 3600_000, retriedAs: "o-new" },
        now,
      ),
    ).toBe(false);
  });
});
