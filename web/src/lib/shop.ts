import type { IndexItem, OrderLine, ShulkerEntry } from "../api/types";

export const MAX_SHULKERS = 54;
export const SHULKER_SLOTS = 27;

/** One stash's slice of the unified shop: its own items, boxes and net availability. */
export interface StashSlice {
  server: string;
  stash: string;
  online: boolean;
  items: IndexItem[];
  shulkers: ShulkerEntry[];
  availableItems: Record<string, number>;
  availableShulkers: Record<string, number>;
}

export interface CartLine extends OrderLine {
  server: string;
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

export interface BoxSource {
  key: string;
  perBox: number;
  boxes: number;
}

/** Filled groups holding an item, most per box first. cartPacked excludes boxes already taken as packed. */
export function boxSources(key: string, shulkers: ShulkerEntry[], cartPacked: (boxKey: string) => number): BoxSource[] {
  const out: BoxSource[] = [];
  for (const e of shulkers) {
    let perBox = 0;
    for (const c of e.contents ?? []) if (c?.key === key) perBox += Math.max(0, c.count);
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

/** Net orderable units of an item key inside one slice (loose + whole-box contents). */
export function sliceAvailability(key: string, slice: StashSlice): number {
  return Math.max(0, slice.availableItems[key] ?? 0) + boxedContents(key, slice.shulkers);
}

/** Pick the slice holding the most orderable units of a key (ties: first). */
export function chooseSlice(
  key: string,
  slices: StashSlice[],
  packedInCart: (server: string, stash: string, boxKey: string) => number,
  looseInCart: (server: string, stash: string, key: string) => number,
): StashSlice | null {
  let best: StashSlice | null = null;
  let bestN = 0;
  for (const s of slices) {
    let boxed = 0;
    for (const b of s.shulkers) {
      let perBox = 0;
      for (const c of b.contents ?? []) if (c?.key === key) perBox += Math.max(0, c.count);
      boxed += perBox * Math.max(0, b.count - packedInCart(s.server, s.stash, b.key));
    }
    const n = Math.max(0, (s.availableItems[key] ?? 0) - looseInCart(s.server, s.stash, key)) + boxed;
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
  server?: string;
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
  return splitScopedItemLine(next, key, undefined, undefined, looseAvail, shulkers);
}

/**
 * Stash-scoped variant for the unified multi-stash shop: only item lines for the same
 * (server, stash) take part in the split, and taken boxes are tagged with that stash.
 * Other lines pass through untouched. Pass server/stash as undefined for the legacy
 * single-stash behavior.
 */
export function splitScopedItemLine(
  next: SplitInput[],
  key: string,
  server: string | undefined,
  stash: string | undefined,
  looseAvail: number,
  shulkers: ShulkerEntry[],
): SplitInput[] | null {
  const inScope = (l: SplitInput) =>
    l.type === "item" &&
    l.key === key &&
    (server === undefined || l.server === server) &&
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
          (server === undefined || l.server === server) &&
          (stash === undefined || l.stash === stash),
      )
      .reduce((a, l) => a + l.count, 0);
  const take = takeBoxes(key, total, shulkers, cartPacked);
  if (take.remainder > looseAvail) return null;
  const kept = next.filter((l) => !inScope(l));
  if (take.remainder > 0) kept.push({ type: "item", key, count: take.remainder, server, stash });
  for (const t of take.took) kept.push({ type: "shulker", key: t.key, count: t.count, server, stash });
  return kept;
}
