import { describe, expect, it } from "vitest";
import {
  botListOf,
  buildUserPerms,
  presetMatches,
  scopeDiff,
  scopeKey,
  stashListOf,
} from "../lib/userScopes";

// P0-01: null (unlimited) vs [] (no access) vs a list must stay distinct from
// the editor through the payload to the backend merge.
const FLAGS = ["view", "order", "ordersAll", "guildOrders", "positions", "activity", "settings", "rescan", "link"] as const;

describe("user scope payload (P0-01)", () => {
  it("always sends explicit scope keys", () => {
    const p = buildUserPerms({ view: true }, FLAGS, "all", [], "all", []);
    expect(p.stashes).toBeNull();
    expect(p.bots).toBeNull();
    expect("stashes" in p && "bots" in p).toBe(true);
  });

  it("sends selected scopes as sorted lists, [] stays []", () => {
    const p = buildUserPerms({}, FLAGS, "selected", ["b", "a"], "selected", []);
    expect(p.stashes).toEqual([{ stash: "a" }, { stash: "b" }]);
    expect(p.bots).toEqual([]);
  });

  it("scopeKey distinguishes null, [] and lists order-insensitively", () => {
    expect(scopeKey(null)).toBe("all");
    expect(scopeKey([])).toBe("sel:");
    expect(scopeKey([{ stash: "b" }, { stash: "a" }])).toBe(scopeKey([{ stash: "a" }, { stash: "b" }]));
    expect(scopeKey(null)).not.toBe(scopeKey([]));
    expect(scopeKey(["x"])).not.toBe(scopeKey(null));
  });

  it("a scoped user is never identified as an unlimited preset", () => {
    const def = { view: true, order: true, stashes: null, bots: null };
    const scoped = { view: true, order: true, stashes: [{ stash: "a" }], bots: null };
    expect(presetMatches(def, scoped, FLAGS)).toBe(false);
    expect(presetMatches(def, { view: true, order: true, stashes: null, bots: null }, FLAGS)).toBe(true);
    const noneScopes = { view: true, order: true, stashes: [], bots: [] };
    expect(presetMatches(def, noneScopes, FLAGS)).toBe(false);
  });

  it("scopeDiff reports only real scope changes", () => {
    expect(scopeDiff(["a"], ["a"], null, null)).toEqual([]);
    expect(scopeDiff(["a"], null, null, null)).toEqual(["stashes: a → All"]);
    expect(scopeDiff(null, [], null, [])).toEqual(["stashes: All → None", "bots: All → None"]);
  });

  it("parses backend scope shapes", () => {
    expect(stashListOf({ stashes: [{ stash: "a" }, { stash: "b" }] })).toEqual(["a", "b"]);
    expect(stashListOf({ stashes: null })).toEqual([]);
    expect(botListOf({ bots: ["bot-1"] })).toEqual(["bot-1"]);
  });
});
