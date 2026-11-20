import { expect, test } from "@playwright/test";

// Inventory + checkout against the Vite dev server with mocked Manager APIs:
// dashboard renders, aggregates loose + boxed counts, add-to-cart flows into the
// draft, the stepper walks cart -> details -> review -> confirmation with a
// per-group state machine, and the Idempotency-Key header is sent (not a body field).
test("inventory aggregates, drafts, checks out and places with idempotency header", async ({ page }) => {
  await page.route("**/api/v1/auth/state", (r) =>
    r.fulfill({ json: { loggedIn: true, user: { id: "owner", name: "Owner", owner: true, perms: { view: true, order: true } } } }),
  );
  await page.route("**/api/v1/stashes", (r) =>
    r.fulfill({ json: [
      { stash: "fixture", online: false },
      { stash: "depot", online: true },
    ] }),
  );
  const boxContents = Array.from({ length: 27 }, (_, slot) => ({ slot, key: "minecraft:diamond", count: 64 }));
  await page.route("**/api/v1/index*", (r) => {
    const depot = new URL(r.request().url()).searchParams.get("stash") === "depot";
    return r.fulfill({
      json: depot
        ? { mcVersion: "26.3", items: [
          { key: "minecraft:torch", id: "minecraft:torch", name: "Torch", count: 32, maxStack: 64, tab: "functional_blocks" },
          { key: "minecraft:diamond", id: "minecraft:diamond", name: "Diamond", count: 200, maxStack: 64, tab: "ingredients" },
        ], shulkers: [] }
        : {
            mcVersion: "26.3",
            items: [{ key: "minecraft:diamond", id: "minecraft:diamond", name: "Diamond", count: 64, maxStack: 64, tab: "ingredients" }],
            shulkers: [{ key: "diamond-box", id: "minecraft:shulker_box", itemKey: "minecraft:diamond", count: 1, contents: boxContents }],
          },
    });
  });
  await page.route("**/api/v1/orders?*", (r) => {
    const depot = new URL(r.request().url()).searchParams.get("stash") === "depot";
    return r.fulfill({ json: { orders: [], available: { items: depot ? { "minecraft:diamond": 200 } : { "minecraft:diamond": 64 }, shulkers: {} } } });
  });
  let posted: { headers: Record<string, string>; body: any }[] = [];
  await page.route("**/api/v1/orders", (r) => {
    if (r.request().method() === "POST") {
      posted.push({ headers: r.request().headers(), body: r.request().postDataJSON() });
      return r.fulfill({ json: { ok: true, order: { id: `o-e2e-${posted.length}` } } });
    }
    return r.continue();
  });

  await page.goto("/inventory");
  await expect(page.getByRole("heading", { name: "Inventory" })).toBeVisible();
  // Guild-wide orderable: fixture loose (64) + depot loose (200) + whole-box (1728).
  await expect(page.getByTestId("item-grid").getByText("1,992").first()).toBeVisible();

  // Search narrows the grid.
  await page.getByLabel("Search items").first().fill("diamond");
  await expect(page.getByTestId("item-grid").locator(".MuiCard-root")).toHaveCount(1);
  await page.getByLabel("Search items").first().fill("");
  await expect(page.getByTestId("item-grid").locator(".MuiCard-root")).toHaveCount(2);

  // Add the full orderable quantity via the item dialog (defaults to max).
  await page.getByTestId("item-grid").locator(".MuiCard-root").first().getByRole("button", { name: "Add" }).click();
  await page.getByRole("button", { name: "Add to cart" }).click();

  // Open the draft drawer and start checkout.
  await page.getByRole("button", { name: /cart/i }).first().click();
  // Guild-wide split: loose + whole box from fixture, loose remainder from depot.
  await expect(page.locator("#cart-lines .cart-line")).toHaveCount(3);
  await page.getByRole("button", { name: "Checkout" }).click();

  // Walk the stepper: cart -> details -> review -> place.
  await page.getByRole("button", { name: "Continue" }).click();
  await page.getByLabel("Comment (optional)").fill("e2e order");
  await page.getByRole("button", { name: "Review" }).click();
  await expect(page.locator("#order-review")).toContainText("e2e order");
  await page.getByRole("button", { name: "Place orders" }).click();
  // Both stash groups confirm with their own order ids (one backend order per stash).
  await expect(page.getByText("Confirmed o-e2e-1")).toBeVisible({ timeout: 10_000 });
  await expect(page.getByText("Confirmed o-e2e-2")).toBeVisible({ timeout: 10_000 });

  // The guild-wide draft (1992 diamonds) split across stashes: fixture loose + whole
  // box, depot loose remainder. Each group has its own idempotency key.
  expect(posted).toHaveLength(2);
  const byStash = Object.fromEntries(posted.map((p) => [p.body.stash, p]));
  expect(byStash["fixture"].body.lines).toEqual([
    { type: "item", key: "minecraft:diamond", count: 64 },
    { type: "shulker", key: "diamond-box", count: 1 },
  ]);
  expect(byStash["depot"].body.lines).toEqual([{ type: "item", key: "minecraft:diamond", count: 200 }]);
  for (const p of posted) {
    expect(p.headers["idempotency-key"]).toMatch(/^[A-Za-z0-9_-]{1,128}$/);
    expect(p.body).not.toHaveProperty("idempotencyKey");
    expect(p.body).not.toHaveProperty("server");
    expect(p.body.purpose).toBe("SELF");
  }
  expect(new Set(posted.map((p) => p.headers["idempotency-key"])).size).toBe(2);
});

test("empty stash and auth redirect", async ({ page }) => {
  await page.route("**/api/v1/auth/state", (r) => r.fulfill({ json: { loggedIn: false } }));
  await page.goto("/inventory");
  await expect(page).toHaveURL(/\/login/);
});

test("failed orders expose retry, partial stash failure is explicit", async ({ page }) => {  await page.route("**/api/v1/auth/state", (r) =>
    r.fulfill({ json: { loggedIn: true, user: { id: "owner", name: "Owner", owner: true, perms: { view: true, order: true, ordersAll: true } } } }),
  );
  await page.route("**/api/v1/stashes", (r) =>
    r.fulfill({ json: [{ stash: "a", online: true }, { stash: "b", online: true }] }),
  );
  await page.route("**/api/v1/orders?stash=a", (r) =>
    r.fulfill({ json: { orders: [{ id: "o-failed1", status: "failed", stash: "a", message: "shortage", lines: [{ type: "item", key: "minecraft:dirt", count: 5 }], createdAt: 1, updatedAt: 2 }], available: { items: {}, shulkers: {} } } }),
  );
  await page.route("**/api/v1/orders?stash=b", (r) => r.fulfill({ status: 500, json: { error: "down" } }));
  await page.goto("/orders");
  await expect(page.getByText("Could not load orders for b")).toBeVisible();
  await page.getByTestId("order-card").first().click();
  // Retry is visible for failed orders (not hidden inside an open-state gate).
  await expect(page.getByRole("button", { name: "Retry as new order" })).toBeVisible();
});

test("SPA navigation shares one canonical orders cache without reload", async ({ page }) => {
  // P0-02 regression: Inventory and Orders read the same keys.orders(stash)
  // cache entries (OrdersResponse). Navigating between pages must never show
  // the other page's projected shape (missing `available`, phantom rows).
  await page.route("**/api/v1/auth/state", (r) =>
    r.fulfill({ json: { loggedIn: true, user: { id: "owner", name: "Owner", owner: true, perms: { view: true, order: true, ordersAll: true, activity: true } } } }),
  );
  await page.route("**/api/v1/stashes", (r) =>
    r.fulfill({ json: [{ stash: "a", online: true }] }),
  );
  await page.route("**/api/v1/index*", (r) =>
    r.fulfill({ json: { mcVersion: "26.3",
      items: [{ key: "minecraft:dirt", id: "minecraft:dirt", name: "Dirt", count: 10, maxStack: 64, tab: "natural_blocks" }],
      shulkers: [] } }),
  );
  await page.route("**/api/v1/orders?*", (r) =>
    r.fulfill({ json: { orders: [{ id: "o-shared1", status: "pending", stash: "a", lines: [{ type: "item", key: "minecraft:dirt", count: 5 }], createdAt: 1, updatedAt: 2 }], available: { items: { "minecraft:dirt": 10 }, shulkers: {} } } }),
  );
  await page.route("**/api/v1/bots", (r) => r.fulfill({ json: [] }));
  await page.route("**/api/v1/server", (r) => r.fulfill({ json: { server: "test", configured: true } }));

  await page.goto("/inventory");
  // Inventory renders availability from the shared cache entry.
  await expect(page.getByTestId("item-grid").getByText("10").first()).toBeVisible();

  await page.getByRole("link", { name: "Orders", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Orders" })).toBeVisible();
  await expect(page.getByTestId("order-card")).toHaveCount(1);

  await page.getByRole("link", { name: "Inventory", exact: true }).click();
  await expect(page.getByTestId("item-grid").getByText("10").first()).toBeVisible();

  await page.getByRole("link", { name: "Overview", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Overview" })).toBeVisible();

  await page.getByRole("link", { name: "Orders", exact: true }).click();
  await expect(page.getByTestId("order-card")).toHaveCount(1);
  // No Reload anywhere above: cache inside and outside stale time stays coherent.
});

test("network failure, reload, exact retry: no duplicates, no silent loss", async ({ page }) => {
  test.setTimeout(90_000);
  // P0-04: an aborted submit becomes uncertain, survives reload via the
  // persisted store, surfaces a resume banner, and retries with the exact
  // same body + idempotency key. A confirmed retry reconciles the cart.
  await page.route("**/api/v1/auth/state", (r) =>
    r.fulfill({ json: { loggedIn: true, user: { id: "owner", name: "Owner", owner: true, perms: { view: true, order: true } } } }),
  );
  await page.route("**/api/v1/stashes", (r) =>
    r.fulfill({ json: [{ stash: "a", online: true }] }),
  );
  await page.route("**/api/v1/index*", (r) =>
    r.fulfill({ json: { mcVersion: "26.3",
      items: [{ key: "minecraft:dirt", id: "minecraft:dirt", name: "Dirt", count: 10, maxStack: 64, tab: "natural_blocks" }],
      shulkers: [] } }),
  );
  await page.route("**/api/v1/orders?*", (r) =>
    r.fulfill({ json: { orders: [], available: { items: { "minecraft:dirt": 10 }, shulkers: {} } } }),
  );
  const posted: { headers: Record<string, string>; body: any }[] = [];
  let failNext = true;
  await page.route("**/api/v1/orders", (r) => {
    if (r.request().method() === "POST") {
      posted.push({ headers: r.request().headers(), body: r.request().postDataJSON() });
      if (failNext) {
        failNext = false;
        return r.abort("failed");
      }
      return r.fulfill({ json: { ok: true, order: { id: "o-retry1" } } });
    }
    return r.continue();
  });

  await page.goto("/inventory");
  await page.getByTestId("item-grid").locator(".MuiCard-root").first().getByRole("button", { name: "Add" }).click();
  await page.getByRole("button", { name: "Add to cart" }).click();
  await page.getByRole("button", { name: /cart/i }).first().click();
  await expect(page.locator("#cart-lines .cart-line")).toHaveCount(1);
  await page.getByRole("button", { name: "Checkout" }).click();
  await page.getByRole("button", { name: "Continue" }).click();
  await page.getByRole("button", { name: "Review" }).click();
  await page.getByRole("button", { name: "Place orders" }).click();
  await expect(page.getByText("Uncertain — retry safely")).toBeVisible({ timeout: 10_000 });
  expect(posted).toHaveLength(1);

  // Reload: the uncertain outcome must not be silently lost.
  await page.reload();
  await expect(page.getByText("may or may not have reached the server")).toBeVisible({ timeout: 10_000 });
  await page.getByRole("button", { name: "Review and retry" }).click();
  await expect(page.getByText("Uncertain — retry safely")).toBeVisible();

  // Exact retry: same body, same idempotency key, then cart reconciled.
  await page.getByRole("button", { name: "Retry safely" }).click();
  await expect(page.getByText("Confirmed o-retry1")).toBeVisible({ timeout: 10_000 });
  expect(posted).toHaveLength(2);
  expect(posted[1].body).toEqual(posted[0].body);
  expect(posted[1].headers["idempotency-key"]).toBe(posted[0].headers["idempotency-key"]);
  // Cart reconciled: the confirmed line is gone, the draft is empty, the
  // resume banner cleared with it, and the cart button is inert.
  await page.getByRole("button", { name: "Done" }).click();
  await expect(page.getByText("may or may not have reached the server")).toHaveCount(0);
  await expect(page.getByRole("button", { name: /cart/i }).first()).toBeDisabled();
});
