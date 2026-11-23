import { describe, expect, it } from "vitest";
import { OrderLineSchema, OrdersResponseSchema } from "../api/types";
import { perStashBoxUsage } from "../lib/shop";

describe("crafting table order contracts", () => {
  it("parses a craftable item without a physical output in the index", () => {
    const parsed = OrdersResponseSchema.parse({
      orders: [],
      available: {
        items: { "minecraft:iron_nugget": 200 },
        shulkers: {},
        craftables: { "minecraft:iron_block": 2 },
      },
    });
    expect(parsed.available.craftables["minecraft:iron_block"]).toBe(2);
    expect(
      OrderLineSchema.parse({
        type: "craft",
        key: "minecraft:iron_block",
        count: 2,
      }),
    ).toEqual({ type: "craft", key: "minecraft:iron_block", count: 2 });
  });

  it("conservatively estimates packaging when a crafted item has no observed stack size", () => {
    const lines = [
      {
        type: "craft" as const,
        key: "minecraft:iron_block",
        count: 40,
        stash: "a",
      },
      {
        type: "item" as const,
        key: "minecraft:diamond",
        count: 64,
        stash: "a",
      },
    ];
    const counts = perStashBoxUsage(lines, (key) =>
      lines.some((l) => l.type === "craft" && l.key === key) ? 1 : 64,
    );
    expect(counts.get("a")).toBe(2);
  });
});
