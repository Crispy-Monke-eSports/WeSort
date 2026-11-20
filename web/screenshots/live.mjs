// Live visual evidence for PR #94: screenshots against a real Java Manager with
// real Minecraft 26.3 icons (no /icon mocks). Run via scripts/screenshots-live.py:
//   node web/screenshots/live.mjs <base> <password> <outDir>
import { chromium } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";

const [base, password, outDir] = process.argv.slice(2);
if (!base || !password || !outDir) {
  console.error("usage: node live.mjs <base> <password> <outDir>");
  process.exit(1);
}
fs.mkdirSync(outDir, { recursive: true });

const views = [
  ["overview", "/overview"],
  ["inventory-grid", "/inventory"],
  ["inventory-table", "/inventory"],
  ["inventory-detail", "/inventory"],
  ["orders", "/orders"],
  ["bots", "/bots"],
  ["operations", "/operations"],
  ["settings", "/settings"],
  ["checkout", "/inventory"],
];
const widths = [390, 768, 1440, 1920];

const browser = await chromium.launch();
for (const width of widths) {
  const height = width === 390 ? 844 : width === 768 ? 1024 : width === 1440 ? 900 : 1080;
  // Fresh profile per width: no cart/checkout state leaks between viewports.
  const context = await browser.newContext({ viewport: { width, height } });
  const page = await context.newPage();
  page.on("pageerror", (e) => console.log("PAGEERROR", String(e).slice(0, 200)));
  page.on("response", async (r) => {
    const url = r.url();
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
  await page.waitForURL("**/overview", { timeout: 15000 });
  for (const [name, route] of views) {
    if (name === "inventory-table" && width === 390) continue; // table is a desktop pattern
    await page.goto(base + route, { waitUntil: "networkidle" });
    await page.waitForTimeout(2500); // icon retry + query settle
    if (name === "inventory-table") {
      const table = page.getByRole("button", { name: "Table view" });
      if ((await table.count()) > 0) {
        await table.first().click();
        await page.waitForTimeout(800);
      }
    }
    if (name === "inventory-detail") {
      await page.locator('button:has-text("Add"):enabled').first().click();
      await page.waitForTimeout(800);
    }
    if (name === "checkout") {
      try {
        await page.locator('button:has-text("Add"):enabled').first().click({ timeout: 5000 });
        await page.getByRole("dialog").getByRole("button", { name: "Add to cart" }).click({ timeout: 3000 });
        await page.getByRole("button", { name: /cart/i }).first().click();
        await page.getByRole("button", { name: "Checkout" }).click();
        await page.waitForTimeout(800);
      } catch (e) {
        console.log("checkout shot skipped:", String(e).slice(0, 120));
      }
    }
    const shot = `${width}-${name}.png`;
    await page.screenshot({ path: path.join(outDir, shot) });
    console.log("shot", shot);
  }
  await context.close();
}
await browser.close();
