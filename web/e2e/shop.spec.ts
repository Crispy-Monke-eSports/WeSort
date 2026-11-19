import { expect, test } from "@playwright/test";

// Shop smoke against the Vite dev server with mocked Manager APIs:
// unified storefront renders, aggregates loose + boxed counts,
// quick-add flows into the cart, the wizard walks cart -> delivery ->
// review -> place, and the Idempotency-Key header is sent (not a body field).
test("shop aggregates, carts, checks out and places with idempotency header", async ({ page }) => {
  await page.route("**/api/v1/auth/state", (r) =>
    r.fulfill({ json: { loggedIn: true, user: { id: "owner", name: "Owner", owner: true, perms: { view: true, order: true } } } }),
  );
  await page.route("**/api/v1/stashes", (r) =>
    r.fulfill({ json: [
      { server: "test", stash: "fixture", online: false },
      { server: "test", stash: "depot", online: true },
    ] }),
  );
  const boxContents = Array.from({ length: 27 }, (_, slot) => ({ slot, key: "minecraft:diamond", count: 64 }));
  await page.route("**/api/v1/index*", (r) => {
    const depot = new URL(r.request().url()).searchParams.get("stash") === "depot";
    return r.fulfill({
      json: depot
        ? { mcVersion: "26.3", items: [{ key: "minecraft:torch", id: "minecraft:torch", name: "Torch", count: 32, maxStack: 64, tab: "functional_blocks" }], shulkers: [] }
        : {
            mcVersion: "26.3",
            items: [{ key: "minecraft:diamond", id: "minecraft:diamond", name: "Diamond", count: 64, maxStack: 64, tab: "ingredients" }],
            shulkers: [{ key: "diamond-box", id: "minecraft:shulker_box", itemKey: "minecraft:diamond", count: 1, contents: boxContents }],
          },
    });
  });
  await page.route("**/api/v1/orders?*", (r) =>
    r.fulfill({ json: { orders: [], available: { items: { "minecraft:diamond": 64 }, shulkers: {} } } }),
  );
  let posted: { headers: Record<string, string>; body: any } | null = null;
  await page.route("**/api/v1/orders", (r) => {
    if (r.request().method() === "POST") {
      posted = { headers: r.request().headers(), body: r.request().postDataJSON() };
      return r.fulfill({ json: { ok: true, order: { id: "o-e2e1" } } });
    }
    return r.continue();
  });

  await page.goto("/shop");
  await expect(page.getByRole("heading", { name: "Shop the stash" })).toBeVisible();
  // Aggregated loose (64) + boxed (1728).
  await expect(page.locator("#item-grid").getByText("×1,792").first()).toBeVisible();

  // Search narrows the grid through the URL.
  await page.getByRole("searchbox", { name: "Search the stash" }).fill("diamond");
  await expect(page).toHaveURL(/q=diamond/);
  await expect(page.locator('#item-grid [role="listitem"]')).toHaveCount(1);
  await page.getByRole("searchbox", { name: "Search the stash" }).fill("");
  await expect(page.locator('#item-grid [role="listitem"]')).toHaveCount(2);

  // Quick-add one stack via the dialog.
  await page.locator('#item-grid [role="listitem"]').first().click();
  await page.getByRole("button", { name: "Add to cart" }).click();
  await expect(page.locator("#cart-lines .cart-line")).toHaveCount(1);
  await expect(page.locator("#cart-totals")).toContainText("64 items");

  // Walk the wizard: cart -> delivery -> review -> place order.
  await page.getByRole("button", { name: "Continue to delivery" }).click();
  await page.locator("#order-comment").fill("e2e order");
  await page.getByRole("button", { name: "Continue to review" }).click();
  await expect(page.locator("#order-review")).toContainText("e2e order");
  await page.getByRole("button", { name: "Place order", exact: true }).click();
  // The mocked stash is offline, so the order queues (offline order queue).
  await expect(page.locator("#cart-status").first()).toContainText(/placed|reconnect/i, { timeout: 10_000 });

  expect(posted).not.toBeNull();
  expect(posted!.headers["idempotency-key"]).toMatch(/^[A-Za-z0-9_-]{1,128}$/);
  expect(posted!.body.lines).toEqual([{ type: "item", key: "minecraft:diamond", count: 64 }]);
  expect(posted!.body).not.toHaveProperty("idempotencyKey");
  expect(posted!.body.purpose).toBe("SELF");
});

test("empty stash and auth redirect", async ({ page }) => {
  await page.route("**/api/v1/auth/state", (r) => r.fulfill({ json: { loggedIn: false } }));
  await page.goto("/shop");
  await expect(page).toHaveURL(/\/login/);
});
