import { describe, expect, it } from "vitest";
import {
  availabilityOf,
  boxedContents,
  isSingleContent,
  mixedBoxedContents,
  shulkersNeeded,
  singleBoxedContents,
  splitItemLine,
  takeBoxes,
} from "../lib/shop";

const diamonds = (key: string, count: number, perBox: number) => ({
  key,
  id: "minecraft:shulker_box",
  itemKey: "minecraft:diamond",
  count,
  contents: [{ slot: 0, key: "minecraft:diamond", count: perBox }],
});

describe("automatic shulker splitting (PR #89)", () => {
  it("takes whole boxes first and keeps the loose remainder", () => {
    const shulkers = [diamonds("a", 1, 1728)];
    const { took, remainder } = takeBoxes("minecraft:diamond", 1792, shulkers, () => 0);
    expect(took).toEqual([{ key: "a", count: 1 }]);
    expect(remainder).toBe(64);
  });

  it("never over-delivers: a box larger than the need is not taken", () => {
    const shulkers = [diamonds("a", 1, 1728)];
    expect(takeBoxes("minecraft:diamond", 100, shulkers, () => 0)).toEqual({ took: [], remainder: 100 });
  });

  it("respects already-reserved packed boxes", () => {
    const shulkers = [diamonds("a", 1, 1728)];
    const { took, remainder } = takeBoxes("minecraft:diamond", 1792, shulkers, (box) => (box === "a" ? 1 : 0));
    expect(took).toEqual([]);
    expect(remainder).toBe(1792);
  });

  it("handles several groups, biggest per-box first", () => {
    const shulkers = [diamonds("small", 2, 100), diamonds("big", 1, 1000)];
    const { took, remainder } = takeBoxes("minecraft:diamond", 1100, shulkers, () => 0);
    expect(took).toEqual([{ key: "big", count: 1 }, { key: "small", count: 1 }]);
    expect(remainder).toBe(0);
  });

  it("never aliases the input array", () => {
    const input = [{ type: "item", key: "minecraft:diamond", count: 10 }] as { type: "item" | "shulker"; key: string; count: number }[];
    const out = splitItemLine(input, "minecraft:diamond", 64, []);
    expect(out).not.toBe(input);
    expect(out).toEqual(input);
    expect(input).toEqual([{ type: "item", key: "minecraft:diamond", count: 10 }]);
  });

  it("splitItemLine refuses when the loose remainder exceeds availability", () => {
    const shulkers = [diamonds("a", 1, 1728)];
    expect(splitItemLine([{ type: "item", key: "minecraft:diamond", count: 100 }], "minecraft:diamond", 64, shulkers)).toBeNull();
    const ok = splitItemLine([{ type: "item", key: "minecraft:diamond", count: 1792 }], "minecraft:diamond", 64, shulkers);
    expect(ok).toContainEqual({ type: "shulker", key: "a", count: 1 });
    expect(ok).toContainEqual({ type: "item", key: "minecraft:diamond", count: 64 });
  });

  it("leaves packed lines untouched while splitting the item part", () => {
    const shulkers = [diamonds("a", 1, 1728)];
    const ok = splitItemLine(
      [
        { type: "item", key: "minecraft:diamond", count: 1792 },
        { type: "shulker", key: "other-box", count: 2 },
      ],
      "minecraft:diamond",
      64,
      shulkers,
    );
    expect(ok).toContainEqual({ type: "shulker", key: "a", count: 1 });
    expect(ok).toContainEqual({ type: "item", key: "minecraft:diamond", count: 64 });
    expect(ok).toContainEqual({ type: "shulker", key: "other-box", count: 2 });
  });
});

describe("capacity", () => {
  it("counts every packed unit (not lines)", () => {
    expect(shulkersNeeded([{ type: "shulker", key: "b", count: 10 }], () => 64, () => false)).toBe(10);
    expect(shulkersNeeded([{ type: "item", key: "d", count: 64 }], () => 64, () => false)).toBe(1);
    expect(shulkersNeeded([{ type: "item", key: "d", count: 64 * 27 + 1 }], () => 64, () => false)).toBe(2);
  });
});

describe("aggregation", () => {
  it("aggregates loose plus boxed counts", () => {
    const shulkers = [diamonds("a", 1, 1728), diamonds("b", 2, 1728)];
    expect(boxedContents("minecraft:diamond", shulkers)).toBe(1728 + 2 * 1728);
    expect(boxedContents("minecraft:iron_ingot", shulkers)).toBe(0);
  });
});

describe("mixed shulkers never over-deliver (P0 2.4)", () => {
  const mixed = (key: string, count: number) => ({
    key,
    id: "minecraft:shulker_box",
    itemKey: "minecraft:diamond",
    count,
    contents: [
      { slot: 0, key: "minecraft:diamond", count: 64 },
      { slot: 1, key: "minecraft:netherite_ingot", count: 64 },
    ],
  });

  it("single-content detection ignores empty slots but rejects any foreign key", () => {
    expect(isSingleContent({ key: "a", count: 1, contents: [{ slot: 0, key: "minecraft:diamond", count: 64 }] } as any, "minecraft:diamond")).toBe(true);
    expect(isSingleContent(mixed("m", 1) as any, "minecraft:diamond")).toBe(false);
    expect(isSingleContent(mixed("m", 1) as any, "minecraft:netherite_ingot")).toBe(false);
    expect(isSingleContent({ key: "e", count: 1, contents: [] } as any, "minecraft:diamond")).toBe(false);
  });

  it("takeBoxes never takes a mixed box whole, even when it holds enough", () => {
    const shulkers = [mixed("m", 1)];
    const { took, remainder } = takeBoxes("minecraft:diamond", 64, shulkers, () => 0);
    expect(took).toEqual([]);
    expect(remainder).toBe(64);
  });

  it("splitItemLine does not emit a packed line for a mixed box", () => {
    const shulkers = [mixed("m", 1)];
    const ok = splitItemLine(
      [{ type: "item", key: "minecraft:diamond", count: 64 }],
      "minecraft:diamond",
      64,
      shulkers,
    );
    // Satisfiable loose: stays a loose line, no shulker line with hitchhikers.
    expect(ok).toEqual([{ type: "item", key: "minecraft:diamond", count: 64 }]);
    expect(ok?.some((l) => l.type === "shulker")).toBe(false);
  });

  it("availability splits single-boxed, mixed and loose units", () => {
    const shulkers = [diamonds("a", 1, 1728), mixed("m", 2)];
    expect(singleBoxedContents("minecraft:diamond", shulkers)).toBe(1728);
    expect(mixedBoxedContents("minecraft:diamond", shulkers)).toBe(2 * 64);
    expect(mixedBoxedContents("minecraft:netherite_ingot", shulkers)).toBe(2 * 64);
    const av = availabilityOf("minecraft:diamond", 10, shulkers);
    expect(av).toEqual({ loose: 10, boxed: 1728, mixed: 128, orderable: 1738 });
  });
});
