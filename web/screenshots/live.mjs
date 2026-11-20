// Live visual evidence for PR #94: screenshots against a real Java Manager with
// real Minecraft 26.3 icons (no /icon mocks). Run via scripts/screenshots-live.py:
//   node web/screenshots/live.mjs <base> <password> <outDir>
//
// This is a verification gate, not a best-effort capture: page errors, failed
// API responses and missed captures fail the run (exit 1). Every shot asserts
// its view rendered (headings, key content), so a blank or broken page is red.
import { chromium } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";

const [base, password, outDir] = process.argv.slice(2);
if (!base || !password || !outDir) {
  console.error("usage: node live.mjs <base> <password> <outDir>");
  process.exit(1);
}
fs.mkdirSync(outDir, { recursive: true });

const failures = [];
function fail(where, what) {
  failures.push(`${where}: ${what}`);
  console.log("FAILSHOT", where, "-", what);
}

const views = [
  ["overview", "/overview", "Overview"],
  ["inventory-grid", "/inventory", "Inventory"],
  ["inventory-table", "/inventory", "Inventory"],
  ["inventory-detail", "/inventory", "Inventory"],
  ["orders", "/orders", "Orders"],
  ["bots", "/bots", "Bots"],
  ["operations", "/operations", "Operations"],
  ["settings", "/settings", "Settings"],
  ["checkout", "/inventory", "Inventory"],
];
const widths = [390, 768, 1440, 1920];

const browser = await chromium.launch();
for (const width of widths) {
  const height = width === 390 ? 844 : width === 768 ? 1024 : width === 1440 ? 900 : 1080;
  // Fresh profile per width: no cart/checkout state leaks between viewports.
  const context = await browser.newContext({ viewport: { width, height } });
  const page = await context.newPage();
  page.on("pageerror", (e) => fail(`${width}`, `pageerror: ${String(e).slice(0, 200)}`));
  page.on("response", async (r) => {
    const url = r.url();
    if (!url.includes("/api/v1/")) return;
    if (url.includes("/icon/")) return;
    if (r.status() >= 400) {
      // Expected-when-unreported: a bot that never pushed settings answers 404;
      // the UI must show the no-settings state (asserted visually).
      if (r.status() === 404 && /\/api\/v1\/bots\/[^/]+\/settings/.test(url)) return;
      fail(`${width}`, `API ${r.status()} ${url.split("/api")[1]?.slice(0, 80)}`);
      return;
    }
    if (url.includes("/api/v1/orders?")) {
      try {
        const body = JSON.stringify(await r.json());
        console.log("API", r.status(), url.split("/api")[1].slice(0, 60), body.slice(200, 400));
      } catch { /* ignore */ }
    }
  });
  await page.goto(base + "/login");
  await page.getByLabel("Account name").fill("");
  await page.getByLabel("Password").fill(password);
  await page.locator("#auth-submit").click();
  try {
    await page.waitForURL("**/overview", { timeout: 15000 });
  } catch {
    fail(`${width}`, "login did not reach overview");
    await context.close();
    continue;
  }
  for (const [name, route, heading] of views) {
    if (name === "inventory-table" && width === 390) continue; // table is a desktop pattern
    const tag = `${width}-${name}`;
    try {
      await page.goto(base + route, { waitUntil: "networkidle" });
      await page.waitForTimeout(2500); // icon retry + query settle
      // Every shot must show its view heading: a blank/broken page is a failure.
      await expectHeading(page, heading, tag);
      if (name === "inventory-table") {
        const table = page.getByRole("button", { name: "Table view" });
        if ((await table.count()) === 0) throw new Error("table toggle missing");
        await table.first().click();
        await page.waitForTimeout(800);
      }
      if (name === "inventory-detail") {
        await page.locator('button:has-text("Add"):enabled').first().click({ timeout: 8000 });
        await page.waitForTimeout(800);
        if ((await page.getByRole("dialog").count()) === 0) throw new Error("detail dialog did not open");
      }
      if (name === "checkout") {
        await page.locator('button:has-text("Add"):enabled').first().click({ timeout: 8000 });
        await page.getByRole("dialog").getByRole("button", { name: "Add to cart" }).click({ timeout: 5000 });
        await page.getByRole("button", { name: "Cart", exact: true }).click({ timeout: 5000 });
        await page.getByRole("button", { name: "Checkout", exact: true }).click({ timeout: 5000 });
        await page.waitForTimeout(800);
        if ((await page.getByRole("dialog").count()) === 0) throw new Error("checkout dialog did not open");
      }
      const shot = `${tag}.png`;
      await page.screenshot({ path: path.join(outDir, shot) });
      console.log("shot", shot);
    } catch (e) {
      fail(tag, String(e).slice(0, 200));
    }
  }
  await context.close();
}
await browser.close();

async function expectHeading(page, heading, tag) {
  const h = page.getByRole("heading", { name: heading, exact: true });
  if ((await h.count()) === 0) throw new Error(`heading ${heading} missing`);
  await h.first().waitFor({ state: "visible", timeout: 10000 });
}

if (failures.length) {
  console.error(`\nSCREENSHOT GATE FAILED (${failures.length}):`);
  for (const f of failures) console.error(" -", f);
  process.exit(1);
}
console.log("\nSCREENSHOT GATE PASSED");
