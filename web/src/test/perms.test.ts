import { describe, expect, it } from "vitest";
import { AuthStateSchema, PermsSchema } from "../api/types";
import { permissionsOf } from "../api/queries";

// Shapes verified against Java AuthRoutes.userJson / Users.Perms.toJson:
// perms is an OBJECT of booleans plus stashes/bots scopes (never an array).
const javaOwner = {
  setupNeeded: false,
  setupAllowed: false,
  codeRequired: false,
  loggedIn: true,
  elevated: false,
  elevatedUntil: 0,
  minLength: 12,
  user: {
    id: "owner",
    name: "Owner",
    owner: true,
    mustChange: false,
    perms: { view: true, order: true, ordersAll: true },
  },
};

const javaMember = {
  loggedIn: true,
  minLength: 12,
  user: {
    id: "u-abcdef123456",
    name: "member",
    owner: false,
    mustChange: false,
    perms: {
      view: true,
      order: true,
      ordersAll: false,
      guildOrders: false,
      positions: false,
      activity: false,
      settings: false,
      rescan: false,
      link: false,
      stashes: [{ stash: "a" }],
      bots: ["bot-1"],
    },
  },
};

describe("auth contracts (Java shape)", () => {
  it("parses the owner state", () => {
    const s = AuthStateSchema.parse(javaOwner);
    expect(s.loggedIn).toBe(true);
    expect(s.user?.owner).toBe(true);
    expect(s.user?.perms.ordersAll).toBe(true);
  });

  it("parses scoped member perms as an object (not an array)", () => {
    const s = AuthStateSchema.parse(javaMember);
    expect(Array.isArray(s.user?.perms)).toBe(false);
    expect(s.user?.perms.view).toBe(true);
    expect(s.user?.perms.ordersAll).toBe(false);
    expect(s.user?.perms.stashes).toEqual([{ stash: "a" }]);
    expect(s.user?.perms.bots).toEqual(["bot-1"]);
  });

  it("defaults missing perm fields to false", () => {
    const s = AuthStateSchema.parse({
      loggedIn: true,
      user: { id: "x", name: "x", owner: false, mustChange: false, perms: {} },
    });
    expect(s.user?.perms.order).toBe(false);
    expect(s.user?.perms.stashes).toBeNull();
  });

  it("grants owners every permission via permissionsOf", () => {
    const perms = permissionsOf(AuthStateSchema.parse(javaOwner));
    for (const p of [
      "view",
      "order",
      "ordersAll",
      "guildOrders",
      "positions",
      "activity",
      "settings",
      "rescan",
      "link",
    ]) {
      expect(perms.has(p)).toBe(true);
    }
  });

  it("does not invent permissions for members", () => {
    const perms = permissionsOf(AuthStateSchema.parse(javaMember));
    expect(perms.has("view")).toBe(true);
    expect(perms.has("order")).toBe(true);
    expect(perms.has("ordersAll")).toBe(false);
    expect(perms.has("activity")).toBe(false);
    expect(perms.size).toBe(2);
  });

  it("parses raw Perms objects", () => {
    expect(PermsSchema.parse({ view: true }).view).toBe(true);
    expect(PermsSchema.parse({}).ordersAll).toBe(false);
  });
});
