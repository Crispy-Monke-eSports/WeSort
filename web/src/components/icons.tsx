/** Item icons are rendered by Java: GET /icon/<namespace>/<path>.png (verified 26.3 client).
 *  Kept as the tested URL contract; rendering lives in components/minecraft/ItemIcon.tsx. */
export function iconUrl(id: string, mcVersion?: string | null): string {
  const hash = id.indexOf("#");
  const bare = (hash < 0 ? id : id.slice(0, hash)).trim();
  const colon = bare.indexOf(":");
  const ns = colon < 0 ? "minecraft" : bare.slice(0, colon).trim() || "minecraft";
  const path = (colon < 0 ? bare : bare.slice(colon + 1)).replace(/[^a-z0-9/_. -]/gi, "").trim() || "unknown";
  const base = `/icon/${encodeURIComponent(ns)}/${encodeURIComponent(path)}.png`;
  return mcVersion ? `${base}?v=${encodeURIComponent(mcVersion)}-g1` : base;
}
