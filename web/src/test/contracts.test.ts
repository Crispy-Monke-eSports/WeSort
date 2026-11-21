import { describe, expect, it } from "vitest";
import { StashRefSchema } from "../api/types";

/** Regression: the real Java payload uses explicit nulls for stall flags and
 *  summaries. The schema must accept them — a parse throw here empties the
 *  whole shop silently (P0: never present uncertain data as empty). */
describe("StashRef server contract", () => {
  it("parses the real /api/v1/stashes entry with explicit nulls", () => {
    const real = {
      server: "Survival",
      stash: "main-hall",
      player: "ShotBot",
      online: true,
      lastSeen: 1795171724315,
      indexedAt: 1795171724307,
      itemTypes: 1,
      itemTotal: 64,
      delivery: null,
      naming: null,
      crafting: null,
      deliveryStorage: null,
      orderBlocker: null,
      rescanPending: 1795172153491, // Java stall flags are epoch timestamps, not booleans
      rescanBlocked: null,
      containers: null,
      orders: { open: 0, active: null },
      deliveryContents: null,
    };
    const parsed = StashRefSchema.parse(real);
    expect(parsed.stash).toBe("main-hall");
    expect(parsed.online).toBe(true);
    expect(parsed.rescanPending).toBe(true);
    expect(parsed.rescanBlocked).toBe(false);
  });
});
