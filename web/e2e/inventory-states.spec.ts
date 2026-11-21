import { expect, test } from "@playwright/test";

// P1-09: reservations are never presented as verified after a failed refetch
// (and unknown stock cannot be drafted). P1-10: the grid pages through the
// whole catalog instead of silently capping. Bot presence follows the stash
// list while Inventory is open.
test("failed order refetch marks stock unverified, grid pages the catalog", async ({ page }) => {
  test.slow();
  await page.route("**/api/v1/auth/state", (r) =>
    r.fulfill({
      json: { loggedIn: true, user: { id: "owner", name: "Owner", owner: true, perms: { view: true, order: true } } },
    }),
  );
  let stashesOnline = true;
  await page.route("**/api/v1/stashes", (r) =>
    r.fulfill({ json: [{ stash: "fixture", online: stashesOnline }] }),
  );
  const items = Array.from({ length: 130 }, (_, i) => ({
    key: `minecraft:item_${i}`,
    id: `minecraft:item_${i}`,
    name: `Item ${i}`,
    count: 10,
    maxStack: 64,
    tab: "ingredients",
  }));
  await page.route("**/api/v1/index*", (r) =>
    r.fulfill({ json: { mcVersion: "26.3", items, shulkers: [] } }),
  );
  let ordersOk = true;
  await page.route("**/api/v1/orders?*", (r) => {
    if (!ordersOk) return r.fulfill({ status: 500, json: { ok: false } });
    return r.fulfill({
      json: { orders: [], available: { items: Object.fromEntries(items.map((it) => [it.key, 10])), shulkers: {} } },
    });
  });

  await page.goto("/inventory");
  // P1-10: full catalog count + paging, not a silent cap.
  await expect(page.getByTestId("grid-count")).toContainText("Showing 120 of 130 items");
  await expect(page.getByTestId("item-grid").locator(".MuiCard-root")).toHaveCount(120);
  await page.getByRole("button", { name: "Show 10 more" }).click();
  await expect(page.getByTestId("item-grid").locator(".MuiCard-root")).toHaveCount(130);
  await expect(page.getByTestId("grid-count")).toContainText("Showing 130 of 130 items");
  // Search still covers the whole catalog beyond the first page.
  await page.getByLabel("Search items").first().fill("item_129");
  await expect(page.getByTestId("item-grid").locator(".MuiCard-root")).toHaveCount(1);
  await page.getByLabel("Search items").first().fill("");

  // Presence is live: the bot goes offline while the page is open.
  await page.getByTestId("item-grid").locator(".MuiCard-root").first().getByRole("button", { name: "Add" }).click();
  await expect(page.getByText("bot online").first()).toBeVisible();
  await page.getByRole("button", { name: "Cancel" }).click();
  stashesOnline = false;
  await page.getByTestId("item-grid").locator(".MuiCard-root").first().getByRole("button", { name: "Add" }).click();
  await expect(page.getByText("bot offline").first()).toBeVisible({ timeout: 30_000 });
  await page.getByRole("button", { name: "Cancel" }).click();

  // P1-09: Manager goes offline after a good first load — nothing becomes "0"
  // or stays "verified", and unknown stock cannot be drafted.
  ordersOk = false;
  await expect(page.getByText("Some stash data is unavailable").first()).toBeVisible({ timeout: 20_000 });
  await expect(page.getByText("Unverified").first()).toBeVisible();
  await expect(page.getByTestId("item-grid").locator(".MuiCard-root").first().getByRole("button", { name: "Add" })).toBeDisabled();
});
