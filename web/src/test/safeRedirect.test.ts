import { describe, expect, it } from "vitest";
import { safeLoginRedirect } from "../lib/safeRedirect";

describe("post-login redirect", () => {
  it("preserves known local routes", () => {
    expect(safeLoginRedirect("/orders?q=diamond")).toBe("/orders?q=diamond");
    expect(safeLoginRedirect("/settings")).toBe("/settings");
  });
  it("rejects external and unknown destinations", () => {
    for (const target of ["//evil.example", "https://evil.example", "javascript:alert(1)",
      "/api/v1/auth/state", "/not-a-page", "orders"]) {
      expect(safeLoginRedirect(target)).toBe("/overview");
    }
  });
});
