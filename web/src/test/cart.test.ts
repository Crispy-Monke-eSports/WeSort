import { beforeEach, describe, expect, it } from "vitest";
import { recoverCheckoutGroups, useCart } from "../stores/shop";

function linesOf() {
  return useCart.getState().lines();
}

const dia = (stash: string, count: number) => ({ type: "item" as const, key: "minecraft:diamond", count, stash });
const box = (stash: string, key: string, count: number) => ({ type: "shulker" as const, key, count, stash });

describe("recovering an interrupted checkout", () => {
  it("preserves the key and exact request when a submitting group survives a reload", () => {
    const group = { body: { stash: "a", lines: [], names: null, packageLabel: null, purpose: "SELF" as const,
      recipient: null, comment: null }, hash: "hash", key: "same-key", status: "submitting" as const, updatedAt: 1 };
    const [recovered] = recoverCheckoutGroups([group]);
    expect(recovered.status).toBe("uncertain");
    expect(recovered.key).toBe(group.key);
    expect(recovered.body).toEqual(group.body);
  });
});

describe("cart isolation (per user; lines carry their stash)", () => {
  beforeEach(() => {
    useCart.setState({ carts: {}, badLines: {}, activeScope: "" });
  });

  it("starts empty without a scope and ignores writes", () => {
    expect(linesOf()).toEqual([]);
    useCart.getState().setLines([dia("a", 1)]);
    expect(linesOf()).toEqual([]);
  });

  it("isolates users; one cart holds lines from many stashes", () => {
    const s = useCart.getState();
    s.setScope("owner");
    s.setLines([dia("a", 5), dia("b", 3), box("a", "box-1", 2)]);
    expect(linesOf()).toHaveLength(3);

    s.setScope("member");
    expect(linesOf()).toEqual([]);

    // Back to the first user: the cart is restored, nothing leaked.
    s.setScope("owner");
    expect(linesOf()).toEqual([dia("a", 5), dia("b", 3), box("a", "box-1", 2)]);
    expect(s.countOf("item", "minecraft:diamond")).toBe(8);
    expect(s.countOf("item", "minecraft:diamond", "a")).toBe(5);
    expect(s.countOf("item", "minecraft:diamond", "b")).toBe(3);
  });

  it("clear only affects the active user", () => {
    const s = useCart.getState();
    s.setScope("owner");
    s.setLines([dia("a", 5)]);
    s.setScope("member");
    s.setLines([dia("a", 2)]);
    s.clear();
    expect(linesOf()).toEqual([]);
    s.setScope("owner");
    expect(linesOf()).toEqual([dia("a", 5)]);
  });

  it("resetScope forgets the active user (logout)", () => {
    const s = useCart.getState();
    s.setScope("owner");
    s.setLines([dia("a", 5)]);
    s.resetScope();
    expect(linesOf()).toEqual([]);
    // The persisted cart is still keyed to the user and comes back on login.
    s.setScope("owner");
    expect(linesOf()).toEqual([dia("a", 5)]);
  });

  it("rejects invalid persisted entries", () => {
    useCart.setState({
      carts: { "user:owner": [dia("a", 5)] },
      badLines: {},
      activeScope: "",
    });
    // Simulate corrupt storage: merge must drop it instead of crashing.
    const merged = (useCart as any).persist.getOptions().merge({ carts: { bad: [{ type: "nope", key: "", count: -1 }] } }, useCart.getState());
    expect(merged.carts).toEqual({});
  });
});
