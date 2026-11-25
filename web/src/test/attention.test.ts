import { describe, expect, it } from "vitest";
import {
  agentServerMismatch,
  alertCount,
  classifyStaleAlert,
  failedStashItems,
  healthAttention,
  isHealthFresh,
  isStaleStockState,
  journalDisplayState,
  orderHeldBy,
  rescanPendingAge,
  rescanPendingLabel,
  silentBots,
  stuckOrderEntries,
  visibleWithOverflow,
  withAlertCount,
  SILENT_BOT_MS,
  type BotLike,
  type OrderLike,
} from "../lib/attention";

const NOW = 1_000_000_000;

function bot(over: Partial<BotLike> = {}): BotLike {
  return {
    botId: "bot-1",
    player: "Steve",
    online: true,
    lastSeen: NOW - 10_000,
    activityAt: NOW - 10_000,
    summary: "sorting",
    ...over,
  };
}

function order(over: Partial<OrderLike> = {}): OrderLike {
  return {
    id: "o-1",
    stash: "main",
    status: "in_progress",
    agent: "bot-1",
    updatedAt: NOW - 10_000,
    ...over,
  };
}

// Aggregation keeps corroborating counts on the row they describe.
describe("incident aggregation (no silent folding)", () => {
  it("keeps alert counts on the same row", () => {
    expect(withAlertCount("Bot X offline", "DISCONNECTED", 2)).toBe(
      "Bot X offline (+ DISCONNECTED ×2)",
    );
    expect(
      withAlertCount("Order o-1 needs attention", "NEEDS_RECONCILIATION", 1),
    ).toBe("Order o-1 needs attention (+ NEEDS_RECONCILIATION ×1)");
  });

  it("leaves text alone without a count", () => {
    expect(withAlertCount("Bot X offline", "DISCONNECTED", 0)).toBe(
      "Bot X offline",
    );
    expect(withAlertCount("Bot X offline", "DISCONNECTED", null)).toBe(
      "Bot X offline",
    );
  });

  it("reads alert counts defensively", () => {
    expect(
      alertCount([{ code: "DISCONNECTED", count: 3 }], "DISCONNECTED"),
    ).toBe(3);
    expect(alertCount([], "DISCONNECTED")).toBe(0);
    expect(alertCount(undefined, "DISCONNECTED")).toBe(0);
  });
});

// STALE_RESCAN is expected rescan fallout; STALE_AGE/MISSING/UNREACHABLE need work.
describe("stale classification", () => {
  it("mutes rescan-expected codes", () => {
    expect(classifyStaleAlert("STALE_RESCAN", false)).toEqual({
      severity: "info",
      muted: true,
    });
    expect(classifyStaleAlert("STALE_STARTUP", false)).toEqual({
      severity: "info",
      muted: true,
    });
  });

  it("maps bare STALE through rescanPending (older servers)", () => {
    expect(classifyStaleAlert("STALE", true).muted).toBe(true);
    expect(classifyStaleAlert("STALE", true).severity).toBe("info");
    expect(classifyStaleAlert("STALE", false).muted).toBe(false);
    expect(classifyStaleAlert("STALE", false).severity).toBe("warning");
  });

  it("keeps age/missing/unreachable actionable even during a rescan", () => {
    for (const code of ["STALE_AGE", "MISSING", "UNREACHABLE", "UNKNOWN"]) {
      expect(classifyStaleAlert(code, true).muted).toBe(false);
      expect(classifyStaleAlert(code, false).severity).toBe("warning");
    }
  });

  it("recognises stale stock states", () => {
    expect(isStaleStockState("STALE")).toBe(true);
    expect(isStaleStockState("STALE_AGE")).toBe(true);
    expect(isStaleStockState("FRESH")).toBe(false);
    expect(isStaleStockState("MISSING")).toBe(false);
  });
});

// /health is the 60s signal: stuck orders and a stale index are errors.
describe("health signals", () => {
  it("is fresh only inside the 60s window", () => {
    expect(isHealthFresh({ checkedAt: NOW - 10_000 }, NOW)).toBe(true);
    expect(isHealthFresh({ checkedAt: NOW - 61_000 }, NOW)).toBe(false);
    expect(isHealthFresh({}, NOW)).toBe(false);
    expect(isHealthFresh(null, NOW)).toBe(false);
  });

  it("retains stock age and additional warning codes from a ready Manager", () => {
    const items = healthAttention({
      status: "ready",
      staleStock: true,
      oldestStockAgeMs: 120000,
      warnings: ["STALE_STOCK", "OTHER_WARNING"],
    });
    expect(items).toHaveLength(2);
    expect(items[0]).toMatchObject({ severity: "warning", to: "/inventory" });
    expect(items[0].text).toContain("2m");
    expect(items[1].text).toBe("OTHER_WARNING");
  });

  it("flags stuck orders and a stale index as errors", () => {
    const items = healthAttention({ stuckOrders: 2, staleIndex: true });
    expect(items).toHaveLength(2);
    expect(items.every((i) => i.severity === "error")).toBe(true);
    expect(items[0].to).toBe("/orders");
  });

  it("lists aged stuck orders individually when the server sends them", () => {
    const items = healthAttention({
      stuckOrders: 2,
      stuckOrderIds: [
        { id: "o-9", stash: "main", ageMs: 95_000 },
        "o-10",
        42,
        null,
      ],
    });
    expect(items).toHaveLength(2);
    expect(items[0].text).toContain("o-9");
    expect(items[0].text).toContain("main");
    expect(items[0].text).toContain("stuck");
    expect(items[0].to).toBe("/orders");
    expect(items[0].search).toEqual({ q: "o-9" });
    expect(items[1].text).toContain("o-10");
  });

  it("flags recovering counts and a dead user registry", () => {
    const items = healthAttention({
      recovering: 1,
      userRegistryUnavailable: true,
    });
    expect(items).toHaveLength(2);
    expect(items[0].severity).toBe("warning");
    expect(items[1].severity).toBe("error");
  });

  it("flags STALE_STOCK warnings as warning, never silent (#203)", () => {
    const viaWarnings = healthAttention({
      warnings: ["STALE_STOCK"],
      oldestStockAgeMs: 514_000,
    });
    expect(viaWarnings).toHaveLength(1);
    expect(viaWarnings[0].severity).toBe("warning");
    expect(viaWarnings[0].text.toLowerCase()).toContain("stale");
    expect(viaWarnings[0].to).toBe("/inventory");
    const viaFlag = healthAttention({ staleStock: true });
    expect(viaFlag).toHaveLength(1);
    expect(viaFlag[0].severity).toBe("warning");
    // Unknown warning codes surface generically (never silent); absence of freshness is not freshness.
    expect(healthAttention({ warnings: ["SOME_OTHER"] })).toHaveLength(1);
  });

  it("yields nothing without data", () => {
    expect(healthAttention(null)).toEqual([]);
    expect(healthAttention({})).toEqual([]);
  });

  it("parses stuck-order detail defensively", () => {
    expect(stuckOrderEntries({})).toEqual([]);
    expect(stuckOrderEntries({ stuckOrderIds: "nope" })).toEqual([]);
    expect(stuckOrderEntries({ stuckOrderIds: [{ nope: 1 }] })).toEqual([]);
  });
});

// Online transport is not progress: silent bots hold open work with no report.
describe("silent-bot watchdog", () => {
  it("flags an online bot with aged open work and no recent report", () => {
    const b = bot({
      lastSeen: NOW - SILENT_BOT_MS - 1,
      activityAt: NOW - SILENT_BOT_MS - 1,
    });
    const o = order({ updatedAt: NOW - SILENT_BOT_MS - 1 });
    const found = silentBots([b], [o], NOW);
    expect(found).toHaveLength(1);
    expect(found[0].bot.botId).toBe("bot-1");
    expect(found[0].orders).toHaveLength(1);
  });

  it("ignores the agent-reported summary text", () => {
    const b = bot({
      summary: "idle, nothing to do",
      lastSeen: NOW - SILENT_BOT_MS - 1,
      activityAt: NOW - SILENT_BOT_MS - 1,
    });
    const o = order({ updatedAt: NOW - SILENT_BOT_MS - 1 });
    expect(silentBots([b], [o], NOW)).toHaveLength(1);
  });

  it("stays quiet for fresh work, offline bots and missing timestamps", () => {
    const fresh = bot();
    expect(silentBots([fresh], [order()], NOW)).toEqual([]);
    const offline = bot({ online: false, lastSeen: NOW - 3600_000 });
    expect(
      silentBots([offline], [order({ updatedAt: NOW - 3600_000 })], NOW),
    ).toEqual([]);
    // No orders held: a quiet bot is fine.
    expect(silentBots([bot({ lastSeen: 1 })], [], NOW)).toEqual([]);
    // Orders without timestamps prove nothing.
    expect(
      silentBots(
        [bot({ lastSeen: 1 })],
        [order({ updatedAt: undefined })],
        NOW,
      ),
    ).toEqual([]);
  });

  it("matches holdings by bot id or player name only", () => {
    const b = bot();
    expect(orderHeldBy(order({ agent: "bot-1" }), b)).toBe(true);
    expect(orderHeldBy(order({ agent: "Steve" }), b)).toBe(true);
    expect(orderHeldBy(order({ agent: "other" }), b)).toBe(false);
    expect(orderHeldBy(order({ agent: null }), b)).toBe(false);
  });
});

describe("agent-vs-server mismatch", () => {
  it("flags an idle claim against held open orders", () => {
    expect(agentServerMismatch(bot({ summary: "idle" }), [order()])).toBe(true);
    expect(
      agentServerMismatch(bot({ summary: "Ready, waiting" }), [order()]),
    ).toBe(true);
  });

  it("does not flag working summaries, missing summaries or offline bots", () => {
    expect(
      agentServerMismatch(bot({ summary: "sorting 3/10" }), [order()]),
    ).toBe(false);
    expect(agentServerMismatch(bot({ summary: null }), [order()])).toBe(false);
    expect(agentServerMismatch(bot({ summary: "no status" }), [order()])).toBe(
      false,
    );
    expect(
      agentServerMismatch(bot({ summary: "idle", online: false }), [order()]),
    ).toBe(false);
    expect(agentServerMismatch(bot({ summary: "idle" }), [])).toBe(false);
  });
});

describe("failed stashes", () => {
  it("emits one signal per stash, each linking at it", () => {
    const items = failedStashItems(["b-stash", "a-stash"]);
    expect(items).toHaveLength(2);
    expect(items[0].severity).toBe("error");
    expect(items[0].to).toBe("/orders");
    expect(items[0].search).toEqual({ q: "a-stash" });
    expect(items[1].search).toEqual({ q: "b-stash" });
  });
});

describe("overflow expanders", () => {
  it("shows the head plus an explicit remainder", () => {
    expect(visibleWithOverflow([1, 2, 3], 5, false)).toEqual({
      visible: [1, 2, 3],
      hidden: 0,
    });
    expect(visibleWithOverflow([1, 2, 3, 4], 2, false)).toEqual({
      visible: [1, 2],
      hidden: 2,
    });
    expect(visibleWithOverflow([1, 2, 3, 4], 2, true)).toEqual({
      visible: [1, 2, 3, 4],
      hidden: 0,
    });
  });
});

describe("journal display state", () => {
  it("maps recovery, receipts and ready journals", () => {
    expect(journalDisplayState({ managerState: "RECOVERING" })).toBe(
      "AUTO_RECOVERING",
    );
    expect(journalDisplayState({ managerState: "NEEDS_RECONCILIATION" })).toBe(
      "AUTO_RECOVERING",
    );
    expect(
      journalDisplayState({
        managerState: "VERIFYING",
        inspectionState: "REQUESTED",
      }),
    ).toBe("WAITING_MANAGER_RECEIPT");
    expect(journalDisplayState({ managerState: "COMPLETED" })).toBe("READY");
  });

  it("yields the raw state when nothing specific applies", () => {
    expect(journalDisplayState({ managerState: "IN_PROGRESS" })).toBe(
      "IN_PROGRESS",
    );
    expect(journalDisplayState({})).toBe("unknown");
    expect(journalDisplayState({ inspectionState: null })).toBe("unknown");
  });
});

describe("rescan pending age (#217)", () => {
  const NOW_MS = 1_700_000_000_000;
  const nowSec = Math.floor(NOW_MS / 1000);
  it("returns null without a request id", () => {
    expect(rescanPendingAge(null, NOW_MS)).toBeNull();
    expect(rescanPendingAge(undefined, NOW_MS)).toBeNull();
    expect(rescanPendingAge(0, NOW_MS)).toBeNull();
    expect(rescanPendingAge("123", NOW_MS)).toBeNull();
    expect(rescanPendingLabel(false, nowSec - 10, null, NOW_MS)).toBeNull();
  });

  it("labels pending age and the blocker verbatim", () => {
    expect(rescanPendingAge(nowSec - 90, NOW_MS)).toBe(90);
    const label = rescanPendingLabel(
      true,
      nowSec - 90,
      "no readable containers",
      NOW_MS,
    );
    expect(label).toContain("rescan pending 1m");
    expect(label).toContain("no readable containers");
  });
});
