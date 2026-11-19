import { create } from "zustand";
import { persist } from "zustand/middleware";
import type { OrderLine } from "../api/types";

export interface CartLine extends OrderLine {}

interface CartState {
  lines: CartLine[];
  badLine: number;
  setLines: (lines: CartLine[]) => void;
  clear: () => void;
  setBadLine: (n: number) => void;
  countOf: (type: string, key: string) => number;
}

function scopeKey(user: string, server: string, stash: string): string {
  return `wesort.cart:${user || "anon"}:${server}:${stash}`;
}

// The active scope is set by the Shop page (user + stash). Zustand persist needs a
// stable storage key per scope so carts never leak across users or stashes.
let activeScope = "wesort.cart:anon::";
export function setCartScope(user: string, server: string, stash: string): void {
  activeScope = scopeKey(user, server, stash);
}

const scopedStorage = {
  getItem: (name: string) => {
    try {
      return localStorage.getItem(`${activeScope}:${name}`);
    } catch {
      return null;
    }
  },
  setItem: (name: string, value: string) => {
    try {
      localStorage.setItem(`${activeScope}:${name}`, value);
    } catch {
      /* storage full or blocked: cart stays in memory */
    }
  },
  removeItem: (name: string) => {
    try {
      localStorage.removeItem(`${activeScope}:${name}`);
    } catch {
      /* ignore */
    }
  },
};

export const useCart = create<CartState>()(
  persist(
    (set, get) => ({
      lines: [],
      badLine: -1,
      setLines: (lines) => set({ lines, badLine: -1 }),
      clear: () => set({ lines: [], badLine: -1 }),
      setBadLine: (n) => set({ badLine: n }),
      countOf: (type, key) => get().lines.filter((l) => l.type === type && l.key === key).reduce((a, l) => a + l.count, 0),
    }),
    { name: "cart", storage: scopedStorage as any },
  ),
);

interface PrefsState {
  theme: "classic" | "clean";
  streamer: boolean;
  sort: "creative" | "count" | "name" | "id";
  query: string;
  setTheme: (t: "classic" | "clean") => void;
  setStreamer: (b: boolean) => void;
  setSort: (s: PrefsState["sort"]) => void;
  setQuery: (q: string) => void;
}

export const usePrefs = create<PrefsState>()(
  persist(
    (set) => ({
      theme: "classic",
      streamer: false,
      sort: "count",
      query: "",
      setTheme: (theme) => set({ theme }),
      setStreamer: (streamer) => set({ streamer }),
      setSort: (sort) => set({ sort }),
      setQuery: (query) => set({ query }),
    }),
    { name: "wesort.prefs" },
  ),
);
