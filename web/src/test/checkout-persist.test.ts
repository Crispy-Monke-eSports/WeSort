import { describe, expect, it } from "vitest";
import { mergeCheckoutPersisted, recoverCheckoutGroups } from "../stores/shop";
import type { CheckoutGroup } from "../stores/shop";

function group(over: Partial<CheckoutGroup> = {}): CheckoutGroup {
  return {
    body: {
      stash: "main",
      lines: [{ type: "item", key: "minecraft:diamond", count: 1 }],
      names: null,
      packageLabel: null,
      purpose: "SELF",
      recipient: null,
      comment: null,
    },
    hash: "hash-1",
    key: "key-1",
    status: "not_submitted",
    updatedAt: 1,
    ...over,
  };
}

describe("checkout rehydration never silently drops idempotency keys", () => {
  it("keeps craft lines that the schema used to reject", () => {
    const persisted = {
      pending: {
        "user:owner": [
          group({
            body: {
              stash: "main",
              lines: [{ type: "craft", key: "minecraft:torch", count: 4 }],
              names: null,
              packageLabel: null,
              purpose: "SELF",
              recipient: null,
              comment: null,
            },
            key: "key-craft",
            hash: "hash-craft",
          }),
        ],
      },
    };
    const out = mergeCheckoutPersisted(persisted, { pending: {} } as never);
    expect(out.pending["user:owner"]).toHaveLength(1);
    expect(out.pending["user:owner"][0].key).toBe("key-craft");
    expect(out.pending["user:owner"][0].body.lines[0].type).toBe("craft");
  });

  it("keeps parseable groups when one group has an unknown shape", () => {
    const persisted = {
      pending: {
        "user:owner": [
          group({ key: "key-good", hash: "hash-good" }),
          { key: "key-future", hash: "hash-future", body: { stash: "main", lines: [] } },
        ],
      },
    };
    const out = mergeCheckoutPersisted(persisted, { pending: {} } as never);
    const kept = out.pending["user:owner"];
    expect(kept).toHaveLength(2);
    expect(kept[0].key).toBe("key-good");
    // Unknown shape is quarantined as uncertain, never dropped with its key.
    expect(kept[1].key).toBe("key-future");
    expect(kept[1].status).toBe("uncertain");
  });

  it("marks interrupted submissions uncertain so retries reuse the key", () => {
    const out = recoverCheckoutGroups([group({ status: "submitting", key: "key-s" })]);
    expect(out[0].status).toBe("uncertain");
    expect(out[0].key).toBe("key-s");
  });

  it("drops only groups with nothing exact to retry with", () => {
    const persisted = { pending: { "user:owner": [{ nope: true }, group({ key: "key-ok" })] } };
    const out = mergeCheckoutPersisted(persisted, { pending: {} } as never);
    expect(out.pending["user:owner"].map((g) => g.key)).toEqual(["key-ok"]);
  });

  it("ignores non-object payloads without throwing", () => {
    for (const bad of [null, undefined, 42, "x", []] as unknown[]) {
      const out = mergeCheckoutPersisted(bad, { pending: {} } as never);
      expect(out.pending).toEqual({});
    }
  });
});
