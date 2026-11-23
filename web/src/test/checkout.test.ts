import { describe, expect, it } from "vitest";
import { z } from "zod";

const recipient = z
  .string()
  .max(16)
  .regex(/^[A-Za-z0-9_]*$/);
const comment = z.string().max(500);
const namesAll = z.string().max(50);

describe("checkout validation (matches Java OrderIntent/normalizeNames limits)", () => {
  it("accepts valid recipients", () => {
    expect(recipient.safeParse("Steve_123").success).toBe(true);
    expect(recipient.safeParse("").success).toBe(true);
  });

  it("rejects recipients Java would refuse", () => {
    expect(recipient.safeParse("has space").success).toBe(false);
    expect(recipient.safeParse("åäö").success).toBe(false);
    expect(recipient.safeParse("x".repeat(17)).success).toBe(false);
  });

  it("caps comment and shulker names like the server", () => {
    expect(comment.safeParse("x".repeat(500)).success).toBe(true);
    expect(comment.safeParse("x".repeat(501)).success).toBe(false);
    expect(namesAll.safeParse("x".repeat(50)).success).toBe(true);
    expect(namesAll.safeParse("x".repeat(51)).success).toBe(false);
  });
});
