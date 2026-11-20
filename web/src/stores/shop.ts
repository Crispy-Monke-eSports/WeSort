import { z } from "zod";
import { create } from "zustand";
import { persist } from "zustand/middleware";
import type { OrderLine } from "../api/types";

export interface CartLine extends OrderLine {
  stash: string;
}

const CartLineSchema = z.object({
  type: z.enum(["item", "shulker"]),
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
