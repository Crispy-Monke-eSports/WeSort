import { describe, expect, it } from "vitest";
import { MAX_SHULKERS, perStashBoxUsage, shulkersNeeded } from "../lib/shop";
import type { CartLine } from "../lib/shop";

// P1-08: the 54-box limit applies per stash order (all cart lines of that
// stash together), never per single addition. The server stays authoritative;
// this is the client pre-check with real max stacks.

const line = (
  stash: string,
  type: "item" | "shulker",
  key: string,
  count: number,
): CartLine => ({ type, key, count, stash });

const maxOf = (stacks: Record<string, number>) => (key: string) =>
  stacks[key] ?? 64;

describe("per-stash box capacity (P1-08)", () => {
  it("counts packed boxes 1:1 and loose items through 27-slot bins", () => {
    const lines = [
      line("a", "shulker", "box-1", 53),
      line("a", "item", "minecraft:cobblestone", 64),
    ];
    // 53 packed + 1 loose stack (64/64) into bins = 54 total.
    expect(perStashBoxUsage(lines, maxOf({})).get("a")).toBe(54);
    expect(
      shulkersNeeded(
        lines.map(({ type, key, count }) => ({ type, key, count })),
        maxOf({}),
        () => false,
      ),
    ).toBe(54);
  });

  it("rejects 55 packed boxes from one stash", () => {
    const usage = perStashBoxUsage(
      [line("a", "shulker", "box-1", 55)],
      maxOf({}),
    );
    expect(usage.get("a")).toBe(55);
    expect(usage.get("a")! > MAX_SHULKERS).toBe(true);
  });

  it("splits usage per stash: 54 + 54 across two stashes is fine, 55 in one is not", () => {
    const usage = perStashBoxUsage(
      [line("a", "shulker", "box-1", 54), line("b", "shulker", "box-2", 54)],
      maxOf({}),
    );
    expect(usage.get("a")).toBe(54);
    expect(usage.get("b")).toBe(54);
    const bad = perStashBoxUsage(
      [line("a", "shulker", "box-1", 54), line("a", "shulker", "box-2", 1)],
      maxOf({}),
    );
    // Each addition alone fits; together they overflow the same stash order.
    expect(bad.get("a")).toBe(55);
  });

  it("uses real max stacks: 16-stack items fill bins faster", () => {
    // 27 slots * 16 = 432 ender pearls per box; 433 needs 2 boxes.
    const usage = perStashBoxUsage(
      [line("a", "item", "minecraft:ender_pearl", 433)],
      maxOf({ "minecraft:ender_pearl": 16 }),
    );
    expect(usage.get("a")).toBe(2);
    // Same count at stack 64 fits one box (27*64 = 1728).
    const usage64 = perStashBoxUsage(
      [line("a", "item", "minecraft:cobblestone", 433)],
      maxOf({ "minecraft:cobblestone": 64 }),
    );
    expect(usage64.get("a")).toBe(1);
  });

  it("mixes packed and loose lines from several items in one stash", () => {
    const usage = perStashBoxUsage(
      [
        line("a", "shulker", "box-1", 50),
        line("a", "item", "minecraft:cobblestone", 27 * 64 * 3), // 3 boxes
        line("a", "item", "minecraft:ender_pearl", 16), // +1 slot -> +1 box (54 slots? no: 28 slots -> 2 bins)
      ],
      maxOf({ "minecraft:ender_pearl": 16 }),
    );
    // 50 packed + ceil((81 + 1) / 27) = 50 + 4 = 54: exactly at the limit.
    expect(usage.get("a")).toBe(54);
  });
});
