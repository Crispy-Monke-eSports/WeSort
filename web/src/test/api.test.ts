import { beforeEach, describe, expect, it, vi } from "vitest";
import { api } from "../api/client";

describe("api client (Java BrowserWrite contract)", () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
  });

  it("sends JSON with X-Stash-Manager and custom headers (Idempotency-Key)", async () => {
    const calls: any[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string, init: any) => {
        calls.push({ url, init });
        return new Response(JSON.stringify({ ok: true }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }),
    );
    await api("/api/v1/orders", {
      method: "POST",
      headers: { "Idempotency-Key": "key-1" },
      body: { stash: "a", lines: [] },
    });
    expect(calls).toHaveLength(1);
    expect(calls[0].init.headers["X-Stash-Manager"]).toBe("1");
    expect(calls[0].init.headers["Content-Type"]).toBe("application/json");
    expect(calls[0].init.headers["Idempotency-Key"]).toBe("key-1");
    expect(JSON.parse(calls[0].init.body)).toEqual({ stash: "a", lines: [] });
  });

  it("never puts the idempotency key in the JSON body", async () => {
    let body = "";
    vi.stubGlobal(
      "fetch",
      vi.fn(async (_url: string, init: any) => {
        body = init.body;
        return new Response(JSON.stringify({ ok: true }), { status: 200 });
      }),
    );
    await api("/api/v1/orders", {
      method: "POST",
      headers: { "Idempotency-Key": "k" },
      body: { lines: [] },
    });
    expect(body).not.toContain("Idempotency");
  });

  it("surfaces 401 as session expiry and 403 as permission denial", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response("{}", { status: 401 })),
    );
    await expect(api("/x")).rejects.toMatchObject({ status: 401 });
    vi.stubGlobal(
      "fetch",
      vi.fn(
        async () =>
          new Response(JSON.stringify({ ok: false, error: "nope" }), {
            status: 403,
          }),
      ),
    );
    await expect(api("/x")).rejects.toMatchObject({ status: 403 });
  });

  it("keeps line-level shortage info from the body", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(
        async () =>
          new Response(JSON.stringify({ ok: false, error: "short", line: 2 }), {
            status: 400,
          }),
      ),
    );
    await expect(api("/x")).rejects.toMatchObject({
      status: 400,
      body: { line: 2 },
    });
  });
});
