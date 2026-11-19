import { describe, expect, it } from "vitest";
import { boxedContents, shelfCount, shulkersNeeded, splitItemLine, takeBoxes } from "../lib/shop";

const box = (key: string, count: number, perBox: number) => ({
  key,
  id: "minecraft:shulker_box",
  itemKey: "minecraft:diamond",
  count,
  contents: [{ slot: 0, key: "minecraft:diamond", count: perBox }],
});

describe("boxed aggregation (PR #89)", () => {
  it("aggregates loose plus boxed counts", () => {
    const shulkers = [box("a", 1, 1728), box("b", 2, 1728)];
    expect(boxedContents("minecraft:diamond", shulkers)).toBe(1728 + 2 * 1728);
    expect(shelfCount({ key: "minecraft:diamond", id: "minecraft:diamond", count: 64, maxStack: 64 }, shulkers)).toBe(64 + 5184);
  });
});

describe("automatic shulker splitting (PR #89)", () => {
  it("takes whole boxes first and keeps the loose remainder", () => {
    const shulkers = [box("diamond-box", 1, 1728)];
    const { took, remainder } = takeBoxes("minecraft:diamond", 1792, shulkers, () => 0);
    expect(took).toEqual([{ key: "diamond-box", count: 1 }]);
    expect(remainder).toBe(64);
  });

  it("never over-delivers: a box larger than the need is not taken", () => {
    const shulkers = [box("diamond-box", 1, 1728)];
    const { took, remainder } = takeBoxes("minecraft:diamond", 100, shulkers, () => 0);
    expect(took).toEqual([]);
    expect(remainder).toBe(100);
  });

  it("splitItemLine refuses when the loose remainder exceeds availability", () => {
    const shulkers = [box("diamond-box", 1, 1728)];
    const next = [{ type: "item" as const, key: "minecraft:diamond", count: 100 }];
    // 64 loose: remainder would be 100 (no full box fits), so refuse.
    expect(splitItemLine(next, "minecraft:diamond", 64, shulkers)).toBeNull();
    // 1792 wanted with 64 loose: one box + 64 loose works.
    const ok = splitItemLine([{ type: "item" as const, key: "minecraft:diamond", count: 1792 }], "minecraft:diamond", 64, shulkers);
    expect(ok).toContainEqual({ type: "shulker", key: "diamond-box", count: 1 });
    expect(ok).toContainEqual({ type: "item", key: "minecraft:diamond", count: 64 });
  });
});

describe("order capacity", () => {
  it("counts packed lines 1:1 and item lines via stacks", () => {
    expect(shulkersNeeded([{ type: "shulker", key: "b", count: 2 }], () => 64, () => false)).toBe(2);
    expect(shulkersNeeded([{ type: "item", key: "d", count: 64 }], () => 64, () => false)).toBe(1);
  });
});
