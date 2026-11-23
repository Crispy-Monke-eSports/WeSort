import { expect, test } from "@playwright/test";

// P0-01 browser regression: editing one boolean permission must not drop the
// user's stash/bot scopes. The editor always sends explicit stashes/bots keys.
test("editing a flag preserves stash and bot scopes", async ({ page }) => {
  await page.route("**/api/v1/auth/state", (r) =>
    r.fulfill({
      json: {
        loggedIn: true,
        user: {
          id: "owner",
          name: "Owner",
          owner: true,
          perms: { view: true, order: true },
        },
      },
    }),
  );
  const scoped = {
    id: "u-abcdef123456",
    name: "erin",
    disabled: false,
    mustChange: false,
    sessions: 1,
    perms: {
      view: true,
      order: true,
      ordersAll: false,
      guildOrders: false,
      positions: false,
      activity: false,
      settings: false,
      rescan: false,
      link: false,
      stashes: [{ stash: "main-hall" }],
      bots: ["shot-bot"],
    },
  };
  await page.route("**/api/v1/users", (r) =>
    r.fulfill({
      json: {
        users: [scoped],
        presets: {
          viewer: { view: true, stashes: null, bots: null },
          member: { view: true, order: true, stashes: null, bots: null },
        },
        minLength: 12,
      },
    }),
  );
  await page.route("**/api/v1/stashes", (r) =>
    r.fulfill({ json: [{ stash: "main-hall" }, { stash: "annex" }] }),
  );
  await page.route("**/api/v1/bots", (r) =>
    r.fulfill({ json: [{ botId: "shot-bot" }, { botId: "spare-bot" }] }),
  );
  let posted: any = null;
  await page.route("**/api/v1/users/u-abcdef123456", (r) => {
    posted = r.request().postDataJSON();
    return r.fulfill({ json: { ok: true, user: scoped } });
  });

  await page.goto("/settings");
  await page.getByRole("tab", { name: "Users" }).click();
  // Readable scope presentation, not raw JSON.
  await expect(page.getByText("Stashes: main-hall")).toBeVisible();
  await expect(page.getByText("Bots: shot-bot")).toBeVisible();
  await expect(page.getByText("custom", { exact: true })).toBeVisible();

  await page.getByRole("button", { name: "Edit" }).click();
  const activity = page.getByRole("switch", { name: "activity" });
  await expect(activity).toBeVisible();
  await activity.click();
  await page.getByRole("button", { name: "Save" }).click();
  // Explicit diff before privileges change.
  await expect(page.getByText("activity: false → true")).toBeVisible();
  await page.getByRole("button", { name: "Apply", exact: true }).click();

  await expect.poll(() => posted).not.toBeNull();
  expect(posted.perms.activity).toBe(true);
  // The scope keys are explicit and unchanged: no silent widening.
  expect(posted.perms.stashes).toEqual([{ stash: "main-hall" }]);
  expect(posted.perms.bots).toEqual(["shot-bot"]);
});
