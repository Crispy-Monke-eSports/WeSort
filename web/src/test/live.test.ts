import { describe, expect, it } from "vitest";
import { AUTH_CHECK_THROTTLE_MS, authCheckDue, parseUpdate, prefixesForChannels } from "../api/live";

describe("live channel mapping (SSE update → TanStack Query keys)", () => {
  it("parses update payloads, tolerating junk", () => {
    expect(parseUpdate(JSON.stringify({ rev: 7, channels: ["orders"] }))).toEqual(["orders"]);
    expect(parseUpdate("not json")).toEqual([]);
    expect(parseUpdate(JSON.stringify({ rev: 1 }))).toEqual([]);
    expect(parseUpdate(JSON.stringify({ channels: ["orders", 42, null] }))).toEqual(["orders"]);
  });

  it("orders invalidate order lists, stash summaries, operations and audit", () => {
    const prefixes = prefixesForChannels(["orders"]);
    expect(prefixes).toContainEqual(["orders"]);
    expect(prefixes).toContainEqual(["operations"]);
    expect(prefixes).toContainEqual(["stashes"]);
    expect(prefixes).toContainEqual(["audit", "orders"]);
  });

  it("index/stock/stashes refresh the guild stock and the stash list", () => {
    const prefixes = prefixesForChannels(["index"]);
    expect(prefixes).toContainEqual(["index"]);
    expect(prefixes).toContainEqual(["stashes"]);
    expect(prefixes).toContainEqual(["operations"]);
  });

  it("bot changes refresh bots without touching the stock", () => {
    const prefixes = prefixesForChannels(["bots"]);
    expect(prefixes).toContainEqual(["bots"]);
    expect(prefixes).toContainEqual(["operations"]);
    expect(prefixes).not.toContainEqual(["index"]);
    expect(prefixes).not.toContainEqual(["orders"]);
  });

  it("presence refreshes online flags without touching stock or orders", () => {
    const prefixes = prefixesForChannels(["presence"]);
    expect(prefixes).toContainEqual(["stashes"]);
    expect(prefixes).toContainEqual(["bots"]);
    expect(prefixes).not.toContainEqual(["index"]);
  });

  it("operations refresh every owner queue", () => {
    const prefixes = prefixesForChannels(["operations"]);
    for (const p of [["operations"], ["physical"], ["jobs"], ["handoffs"], ["custody"], ["deposits"], ["boxes", "jobs"]]) {
      expect(prefixes).toContainEqual(p);
    }
  });

  it("unknown channels fall back to the full live set", () => {
    const prefixes = prefixesForChannels(["something-new"]);
    expect(prefixes).toContainEqual(["orders"]);
    expect(prefixes).toContainEqual(["index"]);
    expect(prefixes).toContainEqual(["bots"]);
  });
});

describe("session revalidation on stream errors (throttled)", () => {
  it("checks auth after a quiet stretch", () => {
    expect(authCheckDue(0, AUTH_CHECK_THROTTLE_MS)).toBe(true);
    expect(authCheckDue(1000, 1000 + AUTH_CHECK_THROTTLE_MS + 1)).toBe(true);
  });

  it("skips routine reconnects inside the throttle window", () => {
    // Stream rotations (~50 s) and retry bursts must not refetch auth every time.
    expect(authCheckDue(1000, 1000 + AUTH_CHECK_THROTTLE_MS - 1)).toBe(false);
    expect(authCheckDue(1000, 1000)).toBe(false);
  });
});
