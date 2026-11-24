import { describe, expect, it } from "vitest";
import {
  AuthUserSchema,
  KitTemplateSchema,
  ManagedUserSchema,
  OrderLineSchema,
  OrdersResponseSchema,
  StashRefSchema,
} from "../api/types";

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

describe("kit contracts (on-demand: one kit is one box)", () => {
  it("parses a kit order line and per-stash kit availability", () => {
    expect(
      OrderLineSchema.parse({ type: "kit", key: "starter", count: 2 }),
    ).toEqual({
      type: "kit",
      key: "starter",
      count: 2,
    });
    const res = OrdersResponseSchema.parse({
      orders: [],
      available: {
        items: { "minecraft:diamond": 10 },
        shulkers: {},
        kits: { starter: 3 },
      },
    });
    expect(res.available.kits?.["starter"]).toBe(3);
  });

  it("parses the kit template list", () => {
    const t = KitTemplateSchema.parse({
      name: "starter",
      revision: 2,
      updatedAt: 123,
      slots: [
        { slot: 0, key: "minecraft:diamond", count: 16 },
        { slot: 13, key: "minecraft:dirt", count: 32 },
      ],
    });
    expect(t.slots).toHaveLength(2);
    expect(t.slots[0]).toEqual({
      slot: 0,
      key: "minecraft:diamond",
      count: 16,
    });
  });
});

describe("permission defaults in Zod 4", () => {
  const identity = { id: "member", name: "Member" };
  const noPermissions = {
    view: false,
    order: false,
    ordersAll: false,
    guildOrders: false,
    positions: false,
    activity: false,
    settings: false,
    rescan: false,
    link: false,
    stashes: null,
    bots: null,
  };

  it("parses omitted and partial permissions through the schema defaults", () => {
    for (const schema of [AuthUserSchema, ManagedUserSchema]) {
      expect(schema.parse(identity).perms).toEqual(noPermissions);
      expect(schema.parse({ ...identity, perms: {} }).perms).toEqual(
        noPermissions,
      );
      expect(
        schema.parse({ ...identity, perms: { view: true } }).perms,
      ).toEqual({ ...noPermissions, view: true });
      expect(schema.safeParse({ ...identity, perms: null }).success).toBe(
        false,
      );
    }
  });
});
