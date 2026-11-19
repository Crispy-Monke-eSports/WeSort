import { expect, test } from "@playwright/test";

// Shop smoke: the unified storefront renders, aggregates loose + boxed counts,
// auto-splits a box-sized order, and walks cart -> delivery -> review -> place.
test("shop aggregates, auto-splits and places", async ({ page }) => {
  await page.route("**/api/v1/auth/state", (r) =>
    r.fulfill({ json: { loggedIn: true, user: { id: "owner", name: "Owner", owner: true } } }),
  );
  await page.route("**/api/v1/stashes", (r) =>
    r.fulfill({ json: [{ server: "test", stash: "fixture", online: false }] }),
  );
  const boxContents = Array.from({ length: 27 }, (_, slot) => ({ slot, key: "minecraft:diamond", count: 64 }));
  await page.route("**/api/v1/index*", (r) =>
    r.fulfill({
      json: {
        mcVersion: "26.3",
        items: [{ key: "minecraft:diamond", id: "minecraft:diamond", name: "Diamond", count: 64, maxStack: 64 }],
        shulkers: [{ key: "diamond-box", id: "minecraft:shulker_box", itemKey: "minecraft:diamond", count: 1, contents: boxContents }],
      },
    }),
  );
  await page.route("**/api/v1/orders?*", (r) =>
    r.fulfill({ json: { orders: [], available: { items: { "minecraft:diamond": 64 }, shulkers: {} } } }),
  );
  let placed: any = null;
  await page.route("**/api/v1/orders", (r) => {
    if (r.request().method() === "POST") {
      placed = r.request().postDataJSON();
      return r.fulfill({ json: {} });
    }
    return r.continue();
  });

  await page.goto("/shop");
  await expect(page.getByRole("heading", { name: "Cart" })).toBeVisible();
  // Aggregated loose (64) + boxed (1728).
  await expect(page.locator("#item-grid").getByText("×1,792")).toBeVisible();
  // Quick-add one stack via the dialog.
  await page.locator("#item-grid .slot").first().click();
  await page.getByRole("button", { name: "Add to cart" }).click();
  await expect(page.locator("#cart-lines .cart-line")).toHaveCount(1);
  // Walk the wizard to review and place.
  await page.getByRole("button", { name: "Continue to delivery" }).click();
  await page.getByRole("button", { name: "Continue to review" }).click();
  await page.getByRole("button", { name: "Place order", exact: true }).click();
  await expect.poll(() => placed, { timeout: 10_000 }).not.toBeNull();
  expect(placed.lines).toBeDefined();
});
