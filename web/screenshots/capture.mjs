// Visual evidence for PR #94: deterministic fixture screenshots at all required
// viewports. Run against a throwaway Manager (or any reachable one):
//   node web/screenshots/capture.mjs [baseUrl] [outDir]
// Default base http://127.0.0.1:8766 (the demo Manager), out docs/screenshots.
import { chromium } from "playwright";
import fs from "node:fs";
import path from "node:path";

const base = process.argv[2] ?? "http://127.0.0.1:8766";
const outDir = process.argv[3] ?? path.join(process.cwd(), "docs", "screenshots");
fs.mkdirSync(outDir, { recursive: true });

const boxContents = Array.from({ length: 27 }, (_, slot) => ({ slot, key: "minecraft:diamond", count: 64 }));
const fixtureItems = [
  { key: "minecraft:cobblestone", id: "minecraft:cobblestone", name: "Cobblestone", count: 110592, maxStack: 64, tab: "building_blocks" },
  { key: "minecraft:oak_planks", id: "minecraft:oak_planks", name: "Oak Planks", count: 5904, maxStack: 64, tab: "building_blocks" },
  { key: "minecraft:torch", id: "minecraft:torch", name: "Torch", count: 4096, maxStack: 64, tab: "functional_blocks" },
  { key: "minecraft:diamond", id: "minecraft:diamond", name: "Diamond", count: 64, maxStack: 64, tab: "ingredients" },
  { key: "minecraft:iron_ingot", id: "minecraft:iron_ingot", name: "Iron Ingot", count: 2304, maxStack: 64, tab: "ingredients" },
  { key: "minecraft:ender_pearl", id: "minecraft:ender_pearl", name: "Ender Pearl", count: 160, maxStack: 16, tab: "ingredients" },
  { key: "minecraft:golden_apple", id: "minecraft:golden_apple", name: "Golden Apple", count: 37, maxStack: 64, tab: "food_and_drinks" },
  { key: "minecraft:netherite_sword", id: "minecraft:netherite_sword", name: "Netherite Sword", count: 3, maxStack: 1, tab: "combat" },
];

const depotItems = [
  { key: "minecraft:netherite_ingot", id: "minecraft:netherite_ingot", name: "Netherite Ingot", count: 41, maxStack: 64, tab: "ingredients" },
];
const depotAvail = { "minecraft:netherite_ingot": 41 };

async function mockApi(page, { orders = [], available = null, items = fixtureItems, online = true, multiStash = true } = {}) {
  const stashes = [{ server: "Survival", stash: "main-hall", online, itemTypes: items.length, itemTotal: 124010 }];
  if (multiStash) stashes.push({ server: "Survival", stash: "nether-depot", online, itemTypes: depotItems.length, itemTotal: 41 });
  await page.route("**/api/v1/auth/state", (r) =>
    r.fulfill({ json: { loggedIn: true, user: { id: "owner", name: "Owner", owner: true, mustChange: false, perms: { view: true, order: true, ordersAll: true, guildOrders: true, positions: true, activity: true, settings: true, rescan: true, link: true } } } }),
  );
  await page.route("**/api/v1/stashes", (r) => r.fulfill({ json: stashes }));
  await page.route("**/api/v1/index*", (r) => {
    const depot = new URL(r.request().url()).searchParams.get("stash") === "nether-depot";
    return r.fulfill({
      json: depot
        ? { mcVersion: "26.3", items: depotItems, shulkers: [] }
        : { mcVersion: "26.3", items, shulkers: [{ key: "diamond-box", id: "minecraft:shulker_box", itemKey: "minecraft:diamond", count: 2, contents: boxContents }] },
    });
  });
  await page.route("**/api/v1/orders?*", (r) => {
    const depot = new URL(r.request().url()).searchParams.get("stash") === "nether-depot";
    return r.fulfill({ json: { orders, available: available ?? { items: depot ? depotAvail : { "minecraft:diamond": 64, "minecraft:iron_ingot": 2304, "minecraft:torch": 4096, "minecraft:oak_planks": 5904, "minecraft:cobblestone": 110592, "minecraft:ender_pearl": 160, "minecraft:golden_apple": 37, "minecraft:netherite_sword": 3 } }, shulkers: {} } });
  });
  let placed = null;
  await page.route("**/api/v1/orders", (r) => {
    if (r.request().method() === "POST") {
      placed = r.request().postDataJSON();
      return r.fulfill({ json: { ok: true, order: { id: "o-shot1" } } });
    }
    return r.continue();
  });
  await page.route("**/icon/**", (r) => r.fulfill({ status: 404, body: "no" }));
  return () => placed;
}

async function shot(browser, name, viewport, setup) {
  const page = await browser.newPage({ viewport });
  const placed = await mockApi(page, setup?.mock);
  if (setup?.theme) {
    await page.addInitScript((theme) => {
      try {
        localStorage.setItem("wesort.prefs", JSON.stringify({ state: { theme, streamer: false, sort: "count", query: "", category: "all", availability: "all" }, version: 0 }));
      } catch {}
    }, setup.theme);
  }
  await page.goto(`${base}${setup?.path ?? "/shop"}`);
  if (setup?.wait) await setup.wait(page);
  await page.waitForTimeout(700);
  await page.screenshot({ path: path.join(outDir, name) });
  console.log("shot", name, JSON.stringify(placed?.() ?? null)?.slice(0, 80));
  await page.close();
}

const browser = await chromium.launch();
try {
  const shopWait = async (page) => page.locator("#item-grid [role=listitem]").first().waitFor({ timeout: 20000 });
  await shot(browser, "shop-1920.png", { width: 1920, height: 1080 }, { wait: shopWait });
  await shot(browser, "shop-1440.png", { width: 1440, height: 900 }, { wait: shopWait });
  await shot(browser, "shop-768.png", { width: 768, height: 1024 }, { wait: shopWait });
  await shot(browser, "shop-390.png", { width: 390, height: 844 }, { wait: shopWait });
  await shot(browser, "shop-empty-1440.png", { width: 1440, height: 900 }, {
    mock: { items: [], orders: [], available: { items: {}, shulkers: {} }, multiStash: false },
    wait: async (page) => page.getByText("Empty inventory").waitFor({ timeout: 20000 }),
  });
  await shot(browser, "cart-1440.png", { width: 1440, height: 900 }, {
    wait: async (page) => {
      await page.locator("#item-grid [role=listitem]").first().waitFor({ timeout: 20000 });
      const card = page.locator("#item-grid [role=listitem]", { hasText: "Iron Ingot" });
      await card.hover();
      await card.getByRole("button", { name: "Quick add Iron Ingot" }).click();
      await page.locator("#cart-lines .cart-line").first().waitFor({ timeout: 10000 });
      const card2 = page.locator("#item-grid [role=listitem]", { hasText: "Torch" });
      await card2.scrollIntoViewIfNeeded();
      await card2.hover();
      await page.waitForTimeout(300);
      await card2.getByRole("button", { name: "Quick add Torch" }).click();
      await page.waitForTimeout(500);
    },
  });
  await shot(browser, "checkout-1440.png", { width: 1440, height: 900 }, {
    wait: async (page) => {
      await page.locator("#item-grid [role=listitem]").first().waitFor({ timeout: 20000 });
      const card = page.locator("#item-grid [role=listitem]", { hasText: "Iron Ingot" });
      await card.hover();
      await card.getByRole("button", { name: "Quick add Iron Ingot" }).click();
      await page.locator("#cart-lines .cart-line").first().waitFor({ timeout: 10000 });
      await page.getByRole("button", { name: "Continue to delivery" }).click();
      await page.locator("#order-comment").fill("Screenshot order");
      await page.getByRole("button", { name: "Continue to review" }).click();
      await page.waitForTimeout(400);
    },
  });
  await shot(browser, "orders-1440.png", { width: 1440, height: 900 }, {
    path: "/orders",
    mock: { items: fixtureItems, orders: [
      { id: "o-aaa111", status: "pending", purpose: "SELF", lines: [{ type: "item", key: "minecraft:torch", count: 64 }], createdAt: Date.now(), requestedBy: "owner", comment: "hurry", packageTotal: 1 },
      { id: "o-bbb222", status: "delivered", lines: [{ type: "shulker", key: "diamond-box", count: 2 }], createdAt: Date.now() - 99999, requestedBy: "owner" },
    ] },
    wait: async (page) => page.locator("#order-list li").first().waitFor({ timeout: 20000 }),
  });
  await shot(browser, "classic-1440.png", { width: 1440, height: 900 }, { theme: "classic", wait: shopWait });
  await shot(browser, "shop-filtered-1440.png", { width: 1440, height: 900 }, {
    path: "/shop?q=diamond&cat=ingredients&sort=count&avail=available",
    wait: async (page) => { await page.locator("#item-grid [role=listitem]").first().waitFor({ timeout: 20000 }); },
  });
} finally {
  await browser.close();
}
console.log("done ->", outDir);
