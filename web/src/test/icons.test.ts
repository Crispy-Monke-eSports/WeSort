import { describe, expect, it } from "vitest";
import { iconUrl } from "../components/icons";

describe("iconUrl (Java /icon/<namespace>/<path>.png)", () => {
  it("builds namespaced URLs", () => {
    expect(iconUrl("minecraft:diamond")).toBe("/icon/minecraft/diamond.png");
    expect(iconUrl("minecraft:shulker_box")).toBe(
      "/icon/minecraft/shulker_box.png",
    );
  });

  it("strips variant suffixes from the path", () => {
    expect(iconUrl("minecraft:potion#abc")).toBe("/icon/minecraft/potion.png");
  });

  it("appends the game-jar cache token when the index version is known", () => {
    expect(iconUrl("minecraft:diamond", "26.3")).toBe(
      "/icon/minecraft/diamond.png?v=26.3-g1",
    );
    expect(iconUrl("minecraft:diamond")).toBe("/icon/minecraft/diamond.png");
  });

  it("never produces the retired /icons/ or ?id= shapes", () => {
    for (const id of ["minecraft:diamond", "minecraft:oak_planks", "x:y#z"]) {
      const u = iconUrl(id);
      expect(u.startsWith("/icon/")).toBe(true);
      expect(u).not.toContain("/icons/");
      expect(u).not.toContain("?id=");
      expect(u).not.toContain("%3A");
      expect(u).not.toContain("#");
    }
  });
});
