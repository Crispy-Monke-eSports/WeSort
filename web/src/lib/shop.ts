import type { IndexItem, OrderLine, ShulkerEntry } from "../api/types";

export const MAX_SHULKERS = 54;
export const SHULKER_SLOTS = 27;

/** One stash's slice of the unified shop: its own items, boxes and net availability. */
export interface StashSlice {
  stash: string;
  online: boolean;
  items: IndexItem[];
  shulkers: ShulkerEntry[];
  availableItems: Record<string, number>;
  availableShulkers: Record<string, number>;
}

export interface CartLine extends OrderLine {
  stash: string;
}

export function isShulkerBoxId(id: string | undefined): boolean {
  return !!id && /^minecraft:([a-z_]+_)?shulker_box$/.test(id);
}

export function approx(n: number): string {
  if (n < 1000) return String(Math.round(n * 10) / 10);
  if (n < 1_000_000) {
    const v = n / 1000;
    return `${v >= 100 ? Math.round(v) : Math.round(v * 10) / 10}k`;
  }
  const v = n / 1_000_000;
  return `${v >= 100 ? Math.round(v) : Math.round(v * 10) / 10}M`;
}

export function grouped(n: number): string {
  return new Intl.NumberFormat("en-US").format(n);
}

/** Units of an item key stored inside filled shulker boxes (all groups, current index). */
export function boxedContents(key: string, shulkers: ShulkerEntry[]): number {
  let n = 0;
  for (const e of shulkers) {
    let perBox = 0;
    for (const c of e.contents ?? []) if (c?.key === key) perBox += Math.max(0, c.count);
    n += perBox * Math.max(0, e.count);
  }
  return n;
}

/** Deterministic JSON: object keys sorted recursively, so equal payloads hash equally. */
export function stable(value: unknown): string {
  if (value === null || typeof value !== "object") return JSON.stringify(value) ?? "null";
  if (Array.isArray(value)) return `[${value.map(stable).join(",")}]`;
  const o = value as Record<string, unknown>;
  return `{${Object.keys(o).sort().map((k) => `${JSON.stringify(k)}:${stable(o[k])}`).join(",")}}`;
}

/** Short hex digest (FNV-1a, 64-bit) of a stable payload string: lookup key, not a secret. */
export function payloadHash(value: unknown): string {
  const s = stable(value);
  let h1 = 0xcbf29ce4;
  let h2 = 0x84222325;
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    h1 = Math.imul(h1 ^ c, 16777619);
    h2 = Math.imul(h2 ^ c, 16777619) ^ (h1 >>> 0);
  }
  return (h2 >>> 0).toString(16).padStart(8, "0") + (h1 >>> 0).toString(16).padStart(8, "0");
}

export interface BoxSource {
  key: string;
  perBox: number;
  boxes: number;
}

/**
 * True when every non-empty slot of the box holds the requested key. Only such
 * single-content boxes may become whole-shulker order lines: a mixed box taken
 * whole would deliver items the order never asked for (over-delivery).
 * Item variants beyond the index key (enchantments/NBT) are not visible here;
 * exactness for those relies on the Manager's physical allocation at claim.
 */
export function isSingleContent(box: ShulkerEntry, key: string): boolean {
  const contents = box.contents ?? [];
  let n = 0;
  for (const c of contents) {
    if (c == null || c.count <= 0) continue;
    if (c.key !== key) return false;
    n += c.count;
  }
  return n > 0;
}

/** Units of a key inside single-content boxes (safe to order as whole shulkers). */
export function singleBoxedContents(key: string, shulkers: ShulkerEntry[]): number {
  let n = 0;
  for (const e of shulkers) {
    if (!isSingleContent(e, key)) continue;
    let perBox = 0;
    for (const c of e.contents ?? []) perBox += Math.max(0, c?.count ?? 0);
    n += perBox * Math.max(0, e.count);
  }
  return n;
}

/**
 * Net available whole boxes of a group: the server's reservation-safe count
 * (`available.shulkers`, net of open orders) capped by observed boxes.
 * Never observed-only: a box reserved by another open order is not shown
 * as free. Missing keys count as zero (fully reserved or unknown).
 */
export function netBoxes(boxKey: string, observed: number, availableShulkers: Record<string, number>): number {
  return Math.max(0, Math.min(Math.max(0, observed), Math.max(0, availableShulkers[boxKey] ?? 0)));
}

/** Reservation-safe single-content units of a key: whole boxes minus open-order holds. */
export function netBoxedContents(
  key: string,
  shulkers: ShulkerEntry[],
  availableShulkers: Record<string, number>,
): number {
  let n = 0;
  for (const e of shulkers) {
    if (!isSingleContent(e, key)) continue;
    let perBox = 0;
    for (const c of e.contents ?? []) perBox += Math.max(0, c?.count ?? 0);
    n += perBox * netBoxes(e.key, e.count, availableShulkers);
  }
  return n;
}

/** Units of a key trapped inside mixed boxes: visible, but never auto-packed whole. */
export function mixedBoxedContents(key: string, shulkers: ShulkerEntry[]): number {
  let n = 0;
  for (const e of shulkers) {
    let mine = 0;
    let other = false;
    for (const c of e.contents ?? []) {
      if (c == null || c.count <= 0) continue;
      if (c.key === key) mine += Math.max(0, c.count);
      else other = true;
    }
    if (mine > 0 && other) n += mine * Math.max(0, e.count);
  }
  return n;
}

/** Filled single-content groups holding an item, most per box first. cartPacked excludes boxes already taken as packed. */
export function boxSources(key: string, shulkers: ShulkerEntry[], cartPacked: (boxKey: string) => number): BoxSource[] {
  const out: BoxSource[] = [];
  for (const e of shulkers) {
    if (!isSingleContent(e, key)) continue;
    let perBox = 0;
    for (const c of e.contents ?? []) perBox += Math.max(0, c?.count ?? 0);
    const boxes = Math.max(0, e.count - cartPacked(e.key));
    if (perBox > 0 && boxes > 0) out.push({ key: e.key, perBox, boxes });
  }
  out.sort((a, b) => b.perBox - a.perBox);
  return out;
}

/** Covers n units from whole filled shulkers plus a loose remainder. Boxes only when fully inside n. */
export function takeBoxes(
  key: string,
  n: number,
  shulkers: ShulkerEntry[],
  cartPacked: (boxKey: string) => number,
): { took: { key: string; count: number }[]; remainder: number } {
  const took: { key: string; count: number }[] = [];
  let remainder = n;
  for (const s of boxSources(key, shulkers, cartPacked)) {
    while (s.boxes > 0 && s.perBox <= remainder) {
      remainder -= s.perBox;
      s.boxes -= 1;
      const line = took.find((t) => t.key === s.key);
      if (line) line.count += 1;
      else took.push({ key: s.key, count: 1 });
    }
  }
  return { took, remainder };
}

/** Shulkers the lines need (packed lines 1:1, item lines via ceil(count/maxStack) into 27-slot bins). */
export function shulkersNeeded(lines: OrderLine[], maxStackOf: (key: string) => number, isBoxKey: (key: string) => boolean): number {
  let units = 0;
  let slots = 0;
  for (const l of lines) {
    if (l.type === "shulker" || isBoxKey(l.key)) units += l.count;
    else {
      const max = Math.max(1, maxStackOf(l.key));
      slots += Math.ceil(l.count / max);
    }
  }
  return units + Math.ceil(slots / SHULKER_SLOTS);
}

/** Display total for a shelf item: loose plus boxed (non-box keys only). */
export function shelfCount(item: IndexItem, shulkers: ShulkerEntry[]): number {
  if (isShulkerBoxId(item.id)) return item.count;
  return item.count + boxedContents(item.key, shulkers);
}

/** Orderable breakdown of an item key: loose units plus whole single-content boxes. */
export interface Availability {
  loose: number;
  boxed: number;
  mixed: number;
  orderable: number;
}

export function availabilityOf(key: string, looseAvail: number, shulkers: ShulkerEntry[]): Availability {
  const boxed = singleBoxedContents(key, shulkers);
  const mixed = mixedBoxedContents(key, shulkers);
  return { loose: looseAvail, boxed, mixed, orderable: looseAvail + boxed };
}

/** Net orderable units of an item key inside one slice (loose + whole single-content boxes). */
export function sliceAvailability(key: string, slice: StashSlice): number {
  return Math.max(0, slice.availableItems[key] ?? 0) + singleBoxedContents(key, slice.shulkers);
}

/** Pick the slice holding the most orderable units of a key (ties: first). */
export function chooseSlice(
  key: string,
  slices: StashSlice[],
  packedInCart: (stash: string, boxKey: string) => number,
  looseInCart: (stash: string, key: string) => number,
): StashSlice | null {
  let best: StashSlice | null = null;
  let bestN = 0;
  for (const s of slices) {
    // Only single-content boxes count: a mixed box taken whole would over-deliver.
    let exact = 0;
    for (const b of s.shulkers) {
      if (!isSingleContent(b, key)) continue;
      let perBox = 0;
      for (const c of b.contents ?? []) perBox += Math.max(0, c?.count ?? 0);
      exact += perBox * Math.max(0, b.count - packedInCart(s.stash, b.key));
    }
    const n = Math.max(0, (s.availableItems[key] ?? 0) - looseInCart(s.stash, key)) + exact;
    if (n > bestN) {
      bestN = n;
      best = s;
    }
  }
  return bestN > 0 ? best : null;
}

export interface SplitInput {
  type: "item" | "shulker";
  key: string;
  count: number;
  stash?: string;
}

/**
 * Moves whole filled shulkers out of an item line (working copy): each taken box becomes a
 * packed line, the rest stays loose. Returns null when the loose remainder exceeds loose
 * availability (backend would refuse too) — caller keeps the old lines and shows the message.
 * The returned array is always fresh; callers may mutate it in place.
 */
export function splitItemLine(
  next: SplitInput[],
  key: string,
  looseAvail: number,
  shulkers: ShulkerEntry[],
): SplitInput[] | null {
  return splitScopedItemLine(next, key, undefined, looseAvail, shulkers);
}

/**
 * Stash-scoped variant for the unified multi-stash shop: only item lines for the same
 * stash take part in the split, and taken boxes are tagged with that stash.
 * Other lines pass through untouched. Pass stash as undefined for the legacy
 * single-stash behavior.
 */
export function splitScopedItemLine(
  next: SplitInput[],
  key: string,
  stash: string | undefined,
  looseAvail: number,
  shulkers: ShulkerEntry[],
): SplitInput[] | null {
  const inScope = (l: SplitInput) =>
    l.type === "item" &&
    l.key === key &&
    (stash === undefined || l.stash === stash);
  const total = next.filter(inScope).reduce((a, l) => a + l.count, 0);
  // Return a fresh array: callers mutate the result in place, so aliasing `next` would corrupt it.
  if (total <= looseAvail) return [...next];
  const cartPacked = (boxKey: string) =>
    next
      .filter(
        (l) =>
          l.type === "shulker" &&
          l.key === boxKey &&
          (stash === undefined || l.stash === stash),
      )
      .reduce((a, l) => a + l.count, 0);
  const take = takeBoxes(key, total, shulkers, cartPacked);
  if (take.remainder > looseAvail) return null;
  const kept = next.filter((l) => !inScope(l));
  if (take.remainder > 0) kept.push({ type: "item", key, count: take.remainder, stash });
  for (const t of take.took) kept.push({ type: "shulker", key: t.key, count: t.count, stash });
  return kept;
}

/** W-064: user box-label text budget is 33 UTF-16 units (50 anvil minus the 17-unit prefix). */
export const LABEL_MAX_TEXT = 33;
/** W-064: at most 54 boxes per order. */
export const LABEL_MAX_BOXES = 54;

/**
 * Client pre-check mirroring the server's PackageLabel.text rules (JS string
 * length is UTF-16 units, like Java's String.length). Returns the error or
 * null when the text is acceptable. The server stays authoritative (400s
 * surface as rejected groups with the server message).
 */
export function labelTextError(raw: string): string | null {
  const value = (raw ?? "").trim();
  if (!value) return null; // blank falls back to the server default “Beställning”
  if (value.length > LABEL_MAX_TEXT)
    return `At most ${LABEL_MAX_TEXT} characters (${value.length} now; the full box name is capped at 50)`;
  if (value.includes("§")) return "§ is not allowed in box text";
  // eslint-disable-next-line no-control-regex
  if (/[\u0000-\u001f\u007f]/.test(value)) return "Control characters are not allowed";
  if (/[\u200b-\u200f\u2028-\u202f\u2060-\u206f\ufeff\u061c\u180e]/.test(value))
    return "Invisible or direction-changing characters are not allowed";
  if (/[\ud800-\udbff](?![\udc00-\udfff])|(?<![\ud800-\udbff])[\udc00-\udfff]/.test(value))
    return "Broken surrogate pairs are not allowed";
  return null;
}
