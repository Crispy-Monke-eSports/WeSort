import { z } from "zod";
import { create } from "zustand";
import { persist } from "zustand/middleware";
import type { OrderLine } from "../api/types";

export interface CartLine extends OrderLine {
  stash: string;
}

const CartLineSchema = z.object({
  type: z.enum(["item", "shulker", "kit"]),
  key: z.string().min(1).max(512),
  count: z.number().int().min(1).max(2 ** 31 - 1),
  stash: z.string().min(1).max(256),
});

const PersistedSchema = z.object({
  carts: z.record(z.string(), z.array(CartLineSchema)).default({}),
});

function scopeKey(user: string): string {
  return `user:${user}`;
}

interface CartState {
  /** Active scope; set from the logged-in user id. */
  activeScope: string;
  carts: Record<string, CartLine[]>;
  badLines: Record<string, number>;
  setScope: (user: string) => void;
  lines: () => CartLine[];
  badLine: () => number;
  setLines: (lines: CartLine[]) => void;
  clear: () => void;
  setBadLine: (n: number) => void;
  countOf: (type: string, key: string, stash?: string) => number;
  /** Logout: forget which scope is active. Persisted per-user carts stay on disk. */
  resetScope: () => void;
}

export const useCart = create<CartState>()(
  persist(
    (set, get) => ({
      activeScope: "",
      carts: {},
      badLines: {},
      setScope: (user) => {
        const key = scopeKey(user || "anon");
        if (get().activeScope !== key) set({ activeScope: key });
      },
      lines: () => get().carts[get().activeScope] ?? [],
      badLine: () => get().badLines[get().activeScope] ?? -1,
      setLines: (lines) => {
        const scope = get().activeScope;
        if (!scope) return;
        set((s) => ({ carts: { ...s.carts, [scope]: lines }, badLines: { ...s.badLines, [scope]: -1 } }));
      },
      clear: () => {
        const scope = get().activeScope;
        if (!scope) return;
        set((s) => {
          const carts = { ...s.carts };
          delete carts[scope];
          return { carts, badLines: { ...s.badLines, [scope]: -1 } };
        });
      },
      setBadLine: (n) => {
        const scope = get().activeScope;
        if (!scope) return;
        set((s) => ({ badLines: { ...s.badLines, [scope]: n } }));
      },
      countOf: (type, key, stash) =>
        (get().carts[get().activeScope] ?? [])
          .filter(
            (l) =>
              l.type === type &&
              l.key === key &&
              (stash === undefined || l.stash === stash),
          )
          .reduce((a, l) => a + l.count, 0),
      resetScope: () => set({ activeScope: "" }),
    }),
    {
      name: "wesort.carts.v1",
      partialize: (s) => ({ carts: s.carts }) as CartState,
      merge: (persisted, current) => {
        const parsed = PersistedSchema.safeParse(persisted);
        return { ...current, carts: parsed.success ? parsed.data.carts : {}, badLines: {}, activeScope: "" };
      },
    },
  ),
);

/** Backwards-compatible scope setter used by the Shop page. */
export function setCartScope(user: string): void {
  useCart.getState().setScope(user);
}

// ---------- multi-stash checkout: explicit per-group state machine ----------

export type CheckoutStatus = "not_submitted" | "submitting" | "confirmed" | "uncertain" | "rejected";

/** One stash's order: the EXACT request payload plus its Idempotency-Key. */
export interface OrderBody {
  stash: string;
  lines: { type: "item" | "shulker" | "kit"; key: string; count: number }[];
  /** Legacy free names (kept for persisted-group compat; new orders send null). */
  names: { mode: "all"; name: string } | { mode: "each"; names: string[] } | null;
  /** W-064 label: the server generates `WeSort (X/Y) - text` from the frozen total. */
  packageLabel: { mode: "all"; text: string } | { mode: "each"; texts: string[] } | null;
  purpose: "SELF" | "GUILD";
  recipient: string | null;
  comment: string | null;
}

export interface CheckoutGroup {
  body: OrderBody;
  /** Hash of the exact body: retries reuse the stored key/body only on exact match. */
  hash: string;
  key: string;
  status: CheckoutStatus;
  orderId?: string;
  error?: string;
  updatedAt: number;
}

const OrderBodyLineSchema = z.object({
  type: z.enum(["item", "shulker", "kit"]),
  key: z.string(),
  count: z.number(),
});
const CheckoutGroupSchema = z.object({
  body: z.object({
    stash: z.string(),
    lines: z.array(OrderBodyLineSchema),
    names: z
      .union([
        z.object({ mode: z.literal("all"), name: z.string() }),
        z.object({ mode: z.literal("each"), names: z.array(z.string()) }),
      ])
      .nullable(),
    packageLabel: z
      .union([
        z.object({ mode: z.literal("all"), text: z.string().max(33) }),
        z.object({ mode: z.literal("each"), texts: z.array(z.string().max(33)).max(54) }),
      ])
      .nullable(),
    purpose: z.enum(["SELF", "GUILD"]),
    recipient: z.string().nullable(),
    comment: z.string().nullable(),
  }),
  hash: z.string(),
  key: z.string(),
  status: z.enum(["not_submitted", "submitting", "confirmed", "uncertain", "rejected"]),
  orderId: z.string().optional(),
  error: z.string().optional(),
  updatedAt: z.number(),
});
const CheckoutPersistedSchema = z.object({
  pending: z.record(z.string(), z.array(CheckoutGroupSchema)),
});

interface CheckoutState {
  /** Per-user pending groups; survives reload so uncertain checkouts can be retried exactly. */
  pending: Record<string, CheckoutGroup[]>;
  getPending: (user: string) => CheckoutGroup[];
  setPending: (user: string, groups: CheckoutGroup[]) => void;
}

export const useCheckout = create<CheckoutState>()(
  persist(
    (set, get) => ({
      pending: {},
      getPending: (user) => get().pending[scopeKey(user || "anon")] ?? [],
      setPending: (user, groups) =>
        set((s) => ({ pending: { ...s.pending, [scopeKey(user || "anon")]: groups } })),
    }),
    {
      name: "wesort.checkout.v1",
      partialize: (s) => ({ pending: s.pending }) as CheckoutState,
      merge: (persisted, current) => {
        const parsed = CheckoutPersistedSchema.safeParse(persisted);
        return { ...current, pending: parsed.success ? parsed.data.pending : {} };
      },
    },
  ),
);

interface PrefsState {
  theme: "modern" | "classic";
  sort: "creative" | "count" | "name" | "id";
  query: string;
  category: string;
  availability: "all" | "available" | "low" | "empty";
  setTheme: (t: "modern" | "classic") => void;
  setSort: (s: PrefsState["sort"]) => void;
  setQuery: (q: string) => void;
  setCategory: (c: string) => void;
  setAvailability: (a: PrefsState["availability"]) => void;
}

export const usePrefs = create<PrefsState>()(
  persist(
    (set) => ({
      theme: "modern",
      sort: "count",
      query: "",
      category: "all",
      availability: "all",
      setTheme: (theme) => set({ theme }),
      setSort: (sort) => set({ sort }),
      setQuery: (query) => set({ query }),
      setCategory: (category) => set({ category }),
      setAvailability: (availability) => set({ availability }),
    }),
    { name: "wesort.prefs" },
  ),
);
