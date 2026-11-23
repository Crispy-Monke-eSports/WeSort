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
  ["settings-users", "/settings", "Settings"],
  ["checkout", "/inventory", "Inventory"],
];
// Light theme: same views at desktop width (full second-scheme proof) plus the
// theme toggle affordance check. Mobile widths stay dark (layout proof).
const lightViews = views.filter(([name]) => name !== "inventory-table");
const widths = [390, 768, 1440, 1920];

const browser = await chromium.launch();
for (const width of widths) {
  for (const theme of width === 1440 ? ["dark", "light"] : ["dark"]) {
    await capture(width, theme);
  }
}
await browser.close();

async function capture(width, theme) {
  const height =
    width === 390 ? 844 : width === 768 ? 1024 : width === 1440 ? 900 : 1080;
  const tagBase =
    width === 1440 && theme === "light" ? `${width}-light` : `${width}`;
  // Fresh profile per width: no cart/checkout state leaks between viewports.
  const context = await browser.newContext({ viewport: { width, height } });
  if (theme === "light") {
    // Same key the app's useColorScheme persists (mui-mode): pre-paint light.
    await context.addInitScript(() => {
      try {
        localStorage.setItem("mui-mode", "light");
      } catch {
        /* ignore */
      }
    });
  }
  const page = await context.newPage();
  page.on("pageerror", (e) =>
    fail(`${tagBase}`, `pageerror: ${String(e).slice(0, 200)}`),
  );
  page.on("response", async (r) => {
    const url = r.url();
    if (!url.includes("/api/v1/")) return;
    if (url.includes("/icon/")) return;
    if (r.status() >= 400) {
      // Expected-when-unreported: a bot that never pushed settings answers 404;
      // the UI must show the no-settings state (asserted visually).
      if (r.status() === 404 && /\/api\/v1\/bots\/[^/]+\/settings/.test(url))
        return;
      fail(
        `${tagBase}`,
        `API ${r.status()} ${url.split("/api")[1]?.slice(0, 80)}`,
      );
      return;
    }
    if (url.includes("/api/v1/orders?")) {
      try {
        const body = JSON.stringify(await r.json());
        console.log(
          "API",
          r.status(),
          url.split("/api")[1].slice(0, 60),
          body.slice(200, 400),
        );
      } catch {
        /* ignore */
      }
    }
  });
  await page.goto(base + "/login");
  await page.getByLabel("Account name").fill("");
  await page.getByLabel("Password").fill(password);
  await page.locator("#auth-submit").click();
  try {
    await page.waitForURL("**/overview", { timeout: 15000 });
  } catch {
    fail(`${tagBase}`, "login did not reach overview");
    await context.close();
    return;
  }
  // Theme proof: the toggle affordance matches the active scheme.
  const toggleName =
    theme === "light" ? "Switch to dark theme" : "Switch to light theme";
  try {
    await page
      .getByRole("button", { name: toggleName })
      .first()
      .waitFor({ timeout: 8000 });
  } catch {
    fail(`${tagBase}`, `theme toggle missing (${toggleName})`);
  }
  const runViews = width === 1440 && theme === "light" ? lightViews : views;
  for (const [name, route, heading] of runViews) {
    if (name === "inventory-table" && width === 390) continue; // table is a desktop pattern
    const tag = `${tagBase}-${name}`;
    try {
      await page.goto(base + route, { waitUntil: "networkidle" });
      await page.waitForTimeout(2500); // icon retry + query settle
      // Every shot must show its view heading: a blank/broken page is a failure.
      await expectHeading(page, heading, tag);
      if (name === "settings-users") {
        await page.getByRole("tab", { name: "Users" }).click();
        await page.waitForTimeout(800);
        await page
          .getByRole("button", { name: "Edit" })
          .first()
          .waitFor({ timeout: 8000 });
        if (tag === "1440-settings-users") {
          // Validation-error dialog proof (masked password field, P0-03):
          // open reset, touch the field empty, keep the dialog unsubmitted.
          await page.getByRole("button", { name: "Reset pw" }).first().click();
          await page.getByLabel("New temporary password").click();
          await page.getByRole("heading", { name: /Reset password/ }).click();
          await page.waitForTimeout(400);
          await page.screenshot({
            path: path.join(outDir, "1440-resetpw.png"),
          });
          console.log("shot 1440-resetpw.png");
          await page.getByRole("button", { name: "Cancel" }).click();
          await page.waitForTimeout(400);
        }
      }
      if (name === "inventory-table") {
        const table = page.getByRole("button", { name: "Table view" });
        if ((await table.count()) === 0)
          throw new Error("table toggle missing");
        await table.first().click();
        await page.waitForTimeout(800);
      }
      if (name === "inventory-detail") {
        await page
          .locator('button:has-text("Add"):enabled')
          .first()
          .click({ timeout: 8000 });
        await page.waitForTimeout(800);
        if ((await page.getByRole("dialog").count()) === 0)
          throw new Error("detail dialog did not open");
      }
      if (name === "checkout") {
        await page
          .locator('button:has-text("Add"):enabled')
          .first()
          .click({ timeout: 8000 });
        await page
          .getByRole("dialog")
          .getByRole("button", { name: "Add to cart" })
          .click({ timeout: 5000 });
        await page
          .getByRole("button", { name: "Cart", exact: true })
          .click({ timeout: 5000 });
        await page
          .getByRole("button", { name: "Checkout", exact: true })
          .click({ timeout: 5000 });
        await page.waitForTimeout(800);
        if ((await page.getByRole("dialog").count()) === 0)
          throw new Error("checkout dialog did not open");
      }
      // P1-06: no page may force the viewport wider (tables scroll internally).
      const overflow = await page.evaluate(
        () => document.documentElement.scrollWidth - window.innerWidth,
      );
      if (overflow > 1)
        throw new Error(`horizontal overflow: ${overflow}px past the viewport`);
      const shot = `${tag}.png`;
      await page.screenshot({ path: path.join(outDir, shot) });
      console.log("shot", shot);
    } catch (e) {
      fail(tag, String(e).slice(0, 200));
    }
  }
  await context.close();
}

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
