import { useQueries, useQueryClient } from "@tanstack/react-query";
import { useVirtualizer } from "@tanstack/react-virtual";
import { useForm } from "@tanstack/react-form";
import { getRouteApi } from "@tanstack/react-router";
import { useEffect, useMemo, useRef, useState } from "react";
import { z } from "zod";
import { api, idempotencyKey } from "../api/client";
import { keys } from "../api/queries";
import { useAuth, usePermissions, useStashes } from "../api/queries";
import { IndexResponse, OrdersResponse } from "../api/types";
import { IndexResponseSchema, OrdersResponseSchema } from "../api/types";
import { ItemIcon } from "../components/icons";
import { Badge } from "../components/ui/badge";
import { Button } from "../components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "../components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "../components/ui/dialog";
import { Sheet, SheetContent, SheetHeader, SheetTitle } from "../components/ui/sheet";
import { QtyInput, Steps, toast } from "../components/ui/feedback";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";
import { Separator } from "../components/ui/tabs";
import { Skeleton } from "../components/ui/tabs";
import { InfoTip } from "../components/ui/tooltip";
import {
  CartLine,
  StashSlice,
  approx,
  boxedContents,
  chooseSlice,
  grouped,
  isShulkerBoxId,
  isSingleContent,
  shulkersNeeded,
  splitScopedItemLine,
} from "../lib/shop";
import { setCartScope, useCart } from "../stores/shop";

const shopRoute = getRouteApi("/shop");

const TAB_ORDER = [
  "building_blocks",
  "colored_blocks",
  "natural_blocks",
  "functional_blocks",
  "redstone_blocks",
  "tools_and_utilities",
  "combat",
  "food_and_drinks",
  "ingredients",
  "spawn_eggs",
  "op_blocks",
  "other",
];

function tabLabel(tab: string): string {
  return tab
    .split("_")
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(" ");
}

function parseQuery(q: string): string[] {
  return q.toLowerCase().split(/\s+/).filter(Boolean);
}

interface MergedItem {
  key: string;
  id: string;
  name?: string | null;
  englishName?: string | null;
  maxStack: number;
  tab?: string | null;
  count: number;
  boxed: number;
  contents?: { slot: number; key: string; count: number }[] | null;
}

function matches(item: MergedItem, tokens: string[]): boolean {
  if (!tokens.length) return true;
  const hay = `${item.name ?? ""} ${item.englishName ?? ""} ${item.id} ${item.key}`.toLowerCase();
  return tokens.every((t) => {
    if (t.startsWith("@")) return item.id.toLowerCase().split(":")[0]?.includes(t.slice(1)) ?? false;
    if (t.startsWith("=")) return item.key.toLowerCase() === t.slice(1) || item.id.toLowerCase() === t.slice(1);
    return hay.includes(t);
  });
}

function zodField<T>(schema: z.ZodType<T>) {
  return ({ value }: { value: T }) => {
    const r = schema.safeParse(value);
    return r.success ? undefined : (r.error.issues[0]?.message as string | undefined);
  };
}

const deliverySchemas = {
  recipient: z.string().max(16, "At most 16 characters").regex(/^[A-Za-z0-9_]*$/, "Letters, digits and underscore only"),
  comment: z.string().max(500, "At most 500 characters"),
  namesAll: z.string().max(50, "At most 50 characters"),
  namesEachItem: z.string().max(50, "At most 50 characters"),
};

interface DeliveryValues {
  purpose: "SELF" | "GUILD";
  recipient: string;
  comment: string;
  namesMode: "none" | "all" | "each";
  namesAll: string;
  namesEach: string[];
}

function createCheckoutForm(onSubmit: (value: DeliveryValues) => Promise<void>) {
  return useForm({
    defaultValues: { purpose: "SELF", recipient: "", comment: "", namesMode: "none", namesAll: "", namesEach: [] } as DeliveryValues,
    onSubmit: async ({ value }) => {
      await onSubmit(value);
    },
  });
}

type CheckoutForm = ReturnType<typeof createCheckoutForm>;

function stashLabel(stash: string): string {
  return stash;
}

export function ShopPage() {
  const auth = useAuth();
  const perms = usePermissions(auth.data);
  const cart = useCart();
  const search = shopRoute.useSearch();
  const navigate = shopRoute.useNavigate();
  const queryClient = useQueryClient();
  const stashes = useStashes(!!auth.data?.loggedIn && perms.can("view"));

  const q = search.q ?? "";
  const sort = search.sort ?? "count";
  const cat = search.cat ?? "all";
  const avail = search.avail ?? "all";

  const stashList = useMemo(() => stashes.data ?? [], [stashes.data]);
  const canView = !!auth.data?.loggedIn && perms.can("view");

  const [visible, setVisible] = useState(true);
  useEffect(() => {
    const onVis = () => setVisible(!document.hidden);
    document.addEventListener("visibilitychange", onVis);
    return () => document.removeEventListener("visibilitychange", onVis);
  }, []);

  const indexQueries = useQueries({
    queries: stashList.map((s) => ({
      queryKey: keys.index(s.stash),
      queryFn: async () =>
        IndexResponseSchema.parse(
          await api<unknown>(`/api/v1/index?stash=${encodeURIComponent(s.stash)}`),
        ) as IndexResponse,
      enabled: canView,
      staleTime: 5_000,
      retry: 1 as const,
    })),
  });
  const orderQueries = useQueries({
    queries: stashList.map((s) => ({
      queryKey: keys.orders(s.stash),
      queryFn: async () =>
        OrdersResponseSchema.parse(
          await api<unknown>(`/api/v1/orders?stash=${encodeURIComponent(s.stash)}`),
        ) as OrdersResponse,
      enabled: canView,
      staleTime: 2_000,
      retry: 1 as const,
      refetchInterval: canView ? (visible ? 3000 : 15000) : false,
    })),
  });

  const slices: StashSlice[] = useMemo(
    () =>
      stashList.map((s, i) => ({
        stash: s.stash,
        online: s.online,
        items: indexQueries[i]?.data?.items ?? [],
        shulkers: indexQueries[i]?.data?.shulkers ?? [],
        availableItems: orderQueries[i]?.data?.available.items ?? {},
        availableShulkers: orderQueries[i]?.data?.available.shulkers ?? {},
      })),
    [stashList, indexQueries, orderQueries],
  );
  const mcVersion = indexQueries.find((qq) => qq.data?.mcVersion)?.data?.mcVersion ?? null;
  const indexLoading = canView && stashList.length > 0 && indexQueries.some((qq) => qq.isLoading);
  const indexError = canView && stashList.length > 0 && indexQueries.some((qq) => qq.isError) && slices.every((s) => s.items.length === 0);

  const userId = auth.data?.user?.id ?? "anon";
  useEffect(() => {
    setCartScope(userId);
  }, [userId]);

  const [buy, setBuy] = useState<MergedItem | null>(null);
  const [step, setStep] = useState<1 | 2 | 3>(1);
  const [placing, setPlacing] = useState(false);
  const [submitState, setSubmitState] = useState<{ status: string; unknownOutcome?: boolean } | null>(null);
  const [cartOpen, setCartOpen] = useState(false);
  const pendingKeys = useRef<Record<string, string>>({});

  const merged = useMemo(() => {
    const byKey = new Map<string, MergedItem>();
    const shulkersAll: StashSlice["shulkers"] = [];
    for (const s of slices) {
      for (const b of s.shulkers) shulkersAll.push(b);
      for (const it of s.items) {
        if (isShulkerBoxId(it.id)) continue;
        const m = byKey.get(it.key);
        if (!m) {
          byKey.set(it.key, {
            key: it.key, id: it.id, name: it.name, englishName: it.englishName,
            maxStack: it.maxStack ?? 64, tab: it.tab ?? "other", count: it.count,
            boxed: boxedContents(it.key, s.shulkers), contents: it.contents ?? null,
          });
        } else {
          m.count += it.count;
          m.boxed += boxedContents(it.key, s.shulkers);
          if (!m.name && it.name) { m.name = it.name; m.id = it.id; }
        }
      }
    }
    return { items: [...byKey.values()], shulkers: shulkersAll };
  }, [slices]);

  const items = useMemo(() => {
    const tokens = parseQuery(q);
    let shown = merged.items.filter((e) => matches(e, tokens));
    if (cat !== "all") shown = shown.filter((e) => (e.tab ?? "other") === cat);
    if (avail !== "all") {
      shown = shown.filter((e) => {
        const total = e.count + e.boxed;
        return avail === "available" ? total > 0 : avail === "low" ? total > 0 && total <= e.maxStack : total <= 0;
      });
    }
    shown.sort((a, b) => {
      if (sort === "name") return (a.name ?? a.id).localeCompare(b.name ?? b.id);
      if (sort === "id") return a.id.localeCompare(b.id);
      if (sort === "creative") {
        const ta = TAB_ORDER.indexOf(a.tab ?? "other");
        const tb = TAB_ORDER.indexOf(b.tab ?? "other");
        return ta - tb || (a.name ?? a.id).localeCompare(b.name ?? b.id);
      }
      return b.count + b.boxed - (a.count + a.boxed);
    });
    return shown;
  }, [merged, q, sort, cat, avail]);

  const categories = useMemo(() => {
    const counts = new Map<string, number>();
    for (const e of merged.items) counts.set(e.tab ?? "other", (counts.get(e.tab ?? "other") ?? 0) + 1);
    return [...counts.entries()].sort((a, b) => TAB_ORDER.indexOf(a[0]) - TAB_ORDER.indexOf(b[0]));
  }, [merged]);

  const cartLines = cart.lines();
  const cartBad = cart.badLine();

  const packedInCart = (stash: string, boxKey: string) =>
    cartLines.filter((l) => l.type === "shulker" && l.stash === stash && l.key === boxKey).reduce((a, l) => a + l.count, 0);
  const looseInCart = (stash: string, key: string) =>
    cartLines.filter((l) => l.type === "item" && l.stash === stash && l.key === key).reduce((a, l) => a + l.count, 0);

  /** Aggregate orderable units of a key across all stashes (for display + button state). */
  function orderableMax(key: string): number {
    let n = 0;
    for (const s of slices) {
      let boxed = 0;
      for (const b of s.shulkers) {
        if (!isSingleContent(b, key)) continue;
        let perBox = 0;
        for (const c of b.contents ?? []) perBox += Math.max(0, c?.count ?? 0);
        boxed += perBox * Math.max(0, b.count - packedInCart(s.stash, b.key));
      }
      n += Math.max(0, (s.availableItems[key] ?? 0) - looseInCart(s.stash, key)) + boxed;
    }
    return n;
  }

  function sliceOf(line: { stash: string }): StashSlice | undefined {
    return slices.find((s) => s.stash === line.stash);
  }

  function addToCart(key: string, n: number): boolean {
    if (n < 1) return false;
    const slice = chooseSlice(key, slices, packedInCart, looseInCart);
    if (!slice) {
      toast("None left to order.", "err");
      return false;
    }
    const next: (CartLine & { stash?: string })[] = cartLines.map((l) => ({ ...l }));
    const found = next.find((l) => l.type === "item" && l.key === key && l.stash === slice.stash);
    if (found) found.count += n;
    else next.push({ type: "item", key, count: n, stash: slice.stash });
    const split = splitScopedItemLine(next, key, slice.stash, Math.max(0, slice.availableItems[key] ?? 0), slice.shulkers);
    if (!split) {
      const boxed = boxedContents(key, slice.shulkers);
      toast(`Only ${grouped(Math.max(0, slice.availableItems[key] ?? 0))} loose available in ${stashLabel(slice.stash)} (${grouped(boxed)} in full shulkers).`, "err");
      return false;
    }
    const need = shulkersNeeded(
      split as CartLine[],
      (k) => merged.items.find((i) => i.key === k)?.maxStack ?? 64,
      (k) => !!merged.items.find((i) => i.key === k && isShulkerBoxId(i.id)),
    );
    if (need > 54) {
      toast(`That needs ${need} shulkers. One order can fill at most 54.`, "err");
      return false;
    }
    cart.setLines(split as CartLine[]);
    return true;
  }

  /** Set one line's quantity, then re-split that line's stash group (no duplicated lines). */
  function setLineQuantity(index_: number, n: number): void {
    const line = cartLines[index_];
    if (!line) return;
    if (line.type !== "item") {
      const next = cartLines.map((l) => ({ ...l }));
      next[index_].count = Math.max(1, n);
      cart.setLines(next);
      return;
    }
    const slice = sliceOf(line);
    if (!slice) return;
    const rest = cartLines
      .filter((l, xi) => xi !== index_ && !(l.type === "item" && l.key === line.key && l.stash === line.stash))
      .map((l) => ({ ...l }));
    rest.push({ type: "item", key: line.key, count: Math.max(1, n), stash: line.stash });
    const split = splitScopedItemLine(rest, line.key, line.stash, Math.max(0, slice.availableItems[line.key] ?? 0), slice.shulkers);
    if (!split) {
      cart.setBadLine(index_);
      toast(`Only ${grouped(Math.max(0, slice.availableItems[line.key] ?? 0))} loose available for that item.`, "err");
      return;
    }
    cart.setLines(split as CartLine[]);
  }

  // ---- totals (actual quantities, not line counts) ----
  const looseTotal = cartLines.filter((l) => l.type === "item").reduce((a, l) => a + l.count, 0);
  const packedBoxes = cartLines.filter((l) => l.type === "shulker").reduce((a, l) => a + l.count, 0);
  const cartUnits = looseTotal + packedBoxes;
  const estimatedExtra = useMemo(
    () =>
      Math.max(
        0,
        shulkersNeeded(
          cartLines.filter((l) => l.type === "item"),
          (k) => merged.items.find((i) => i.key === k)?.maxStack ?? 64,
          () => false,
        ),
      ),
    [cartLines, merged],
  );
  const estimatedTotal = packedBoxes + estimatedExtra;

  // ---- cart revalidation against current availability ----
  const overLines = useMemo(() => {
    const bad: number[] = [];
    cartLines.forEach((l, i) => {
      const s = sliceOf(l);
      if (!s) {
        bad.push(i);
        return;
      }
      if (l.type === "item" && !merged.items.find((x) => x.key === l.key && isShulkerBoxId(x.id))) {
        const groupTotal = cartLines
          .filter((x) => x.type === "item" && x.key === l.key && x.stash === l.stash)
          .reduce((a, x) => a + x.count, 0);
        if (groupTotal > Math.max(0, s.availableItems[l.key] ?? 0) + boxedContents(l.key, s.shulkers)) bad.push(i);
      } else if (l.count > orderableMaxForLine(l, l.count)) bad.push(i);
    });
    return bad;
  }, [cartLines, slices, merged]);

  function orderableMaxForLine(line: CartLine, exclude: number): number {
    const s = sliceOf(line);
    if (!s) return 0;
    if (line.type !== "item") return Math.max(0, (s.availableShulkers[line.key] ?? 0) - (cart.countOf(line.type, line.key, line.stash) - exclude));
    let boxed = 0;
    for (const b of s.shulkers) {
      if (!isSingleContent(b, line.key)) continue;
      let perBox = 0;
      for (const c of b.contents ?? []) perBox += Math.max(0, c?.count ?? 0);
      boxed += perBox * Math.max(0, b.count - packedInCart(s.stash, b.key));
    }
    return Math.max(0, (s.availableItems[line.key] ?? 0) - (cart.countOf(line.type, line.key, line.stash) - exclude)) + boxed;
  }

  // ---- checkout form (TanStack Form + Zod) ----
  const form = createCheckoutForm(place);

  function cartHash(lines: CartLine[]): string {
    return JSON.stringify(lines.map((l) => [l.type, l.key, l.count, l.stash]));
  }

  async function place(value: DeliveryValues): Promise<void> {
    if (!cartLines.length || placing) return;
    if (!perms.can("order")) {
      toast("Your account can't place orders.", "err");
      return;
    }
    setPlacing(true);
    // One order per stash: group lines, one idempotency key per group (reused on safe retry).
    const groups = new Map<string, CartLine[]>();
    for (const l of cartLines) {
      const k = l.stash;
      if (!groups.has(k)) groups.set(k, []);
      groups.get(k)!.push(l);
    }
    const names =
      value.namesMode === "all" && value.namesAll.trim()
        ? { mode: "all", name: value.namesAll.trim() }
        : value.namesMode === "each"
          ? { mode: "each", names: value.namesEach.map((s) => (s ?? "").trim()) }
          : null;
    const done: string[] = [];
    const failed: string[] = [];
    let unknown = false;
    for (const [stash, lines] of groups) {
      const hash = stash + "::" + cartHash(lines);
      if (!pendingKeys.current[hash]) pendingKeys.current[hash] = idempotencyKey();
      setSubmitState({ status: `Placing order for ${stash}…` });
      try {
        const res = await api<{ ok: boolean; order: { id: string } }>(`/api/v1/orders`, {
          method: "POST",
          headers: { "Idempotency-Key": pendingKeys.current[hash] },
          body: {
            stash,
            lines: lines.map((l) => ({ type: l.type, key: l.key, count: l.count })),
            names,
            packageLabel: null,
            purpose: value.purpose,
            recipient: value.recipient?.trim() ? value.recipient.trim() : null,
            comment: value.comment?.trim() ? value.comment.trim() : null,
          },
        });
        delete pendingKeys.current[hash];
        done.push(`${res.order.id} (${stash})`);
      } catch (e: any) {
        const status: number | undefined = e?.status;
        const line = e?.body?.line;
        if (typeof line === "number") {
          const idx = cartLines.findIndex((l) => l.stash === stash);
          if (idx >= 0) cart.setBadLine(idx);
        }
        if (status === 0 || status === undefined) {
          unknown = true;
          failed.push(`${stash}: uncertain outcome — retry reuses the same idempotency key`);
        } else {
          delete pendingKeys.current[hash];
          failed.push(`${stash}: ${e?.message ?? "failed"}`);
        }
      }
    }
    for (const stash of [...groups.keys()]) {
      queryClient.invalidateQueries({ queryKey: keys.orders(stash) });
    }
    if (failed.length === 0) {
      cart.clear();
      setStep(1);
      form.reset();
    } else {
      // Keep failed groups' lines so nothing is lost.
      const failedKeys = new Set(failed.map((f) => f.split(":")[0]));
      void failedKeys;
    }
    const offlineNote = slices.some((s) => s.online === false) ? " It will start when the bot reconnects." : "";
    const msg =
      (done.length ? `Placed ${done.join(", ")}.${offlineNote}` : "") +
      (failed.length ? `${done.length ? " " : ""}Failed: ${failed.join("; ")}` : "");
    setSubmitState({ status: msg || "Nothing to place.", unknownOutcome: unknown });
    if (done.length && !failed.length) toast(`Order${done.length > 1 ? "s" : ""} placed`);
    setPlacing(false);
  }

  // ---- row virtualization (uniform rows, correct total scroll model) ----
  const gridRef = useRef<HTMLDivElement>(null);
  const [cols, setCols] = useState(5);
  useEffect(() => {
    const el = gridRef.current;
    if (!el) return;
    const update = () => {
      const w = el.clientWidth;
      setCols(Math.max(1, Math.floor((w + 12) / 176)));
    };
    update();
    const ro = new ResizeObserver(update);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  const rows = useMemo(() => {
    const out: MergedItem[][] = [];
    for (let i = 0; i < items.length; i += cols) out.push(items.slice(i, cols === 0 ? items.length : i + cols));
    return out;
  }, [items, cols]);
  const ROW_H = 296;
  const virtual = useVirtualizer({ count: rows.length, getScrollElement: () => gridRef.current, estimateSize: () => ROW_H, overscan: 3 });

  function setParam(patch: { q?: string; sort?: "creative" | "count" | "name" | "id"; cat?: string; avail?: "all" | "available" | "low" | "empty" }) {
    void navigate({ search: (prev) => ({ ...prev, ...patch }) });
  }

  const recentOrders = useMemo(() => {
    const all: { id: string; status: string; lines: { type: string; key: string; count: number }[]; message?: string | null; createdAt?: number }[] = [];
    orderQueries.forEach((qq) => {
      for (const o of qq.data?.orders ?? []) all.push(o);
    });
    return all.sort((a, b) => (b.createdAt ?? 0) - (a.createdAt ?? 0)).slice(0, 5);
  }, [orderQueries]);

  const anyOffline = slices.some((s) => s.online === false);
  const anyStash = stashList.length > 0;

  return (
    <div className="flex flex-col gap-4 lg:flex-row">
      {/* ---------- left: filters ---------- */}
      <aside className="w-full shrink-0 lg:w-60">
        <Card className="lg:sticky lg:top-20">
          <CardHeader className="pb-2">
            <CardTitle className="text-base">Filters</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <div>
              <span className="mb-1.5 block text-xs font-semibold uppercase tracking-wide text-muted-foreground">Stashes ({stashList.length})</span>
              {anyOffline && (
                <p className="mb-1.5 rounded-lg border border-amber-500/40 bg-amber-500/10 p-1.5 text-xs text-amber-600 dark:text-amber-300" role="status">
                  A bot is offline — orders queue and start on reconnect.
                </p>
              )}
              <ul className="flex flex-col gap-1">
                {stashList.map((s) => (
                  <li key={s.stash} className="flex items-center gap-1.5 rounded-lg px-2 py-1 text-sm hover:bg-muted">
                    <span className={`h-2 w-2 shrink-0 rounded-full ${s.online === false ? "bg-amber-500" : "bg-emerald-500"}`} aria-hidden />
                    <span className="truncate">{s.stash}</span>
                    {perms.can("rescan") && <RescanButton stash={s.stash} compact />}
                  </li>
                ))}
              </ul>
              {!stashList.length && <p className="text-xs text-muted-foreground">No stashes visible.</p>}
            </div>
            <div>
              <span className="mb-1.5 block text-xs font-semibold uppercase tracking-wide text-muted-foreground">Category</span>
              <div className="flex flex-col gap-0.5" role="group" aria-label="Category">
                <FilterRow active={cat === "all"} onClick={() => setParam({ cat: "all" })} label="All items" count={items.length} />
                {categories.map(([tab, count]) => (
                  <FilterRow key={tab} active={cat === tab} onClick={() => setParam({ cat: tab })} label={tabLabel(tab)} count={count} />
                ))}
              </div>
            </div>
            <div>
              <span className="mb-1.5 block text-xs font-semibold uppercase tracking-wide text-muted-foreground">Availability</span>
              <div className="flex flex-wrap gap-1.5" role="group" aria-label="Availability">
                {(["all", "available", "low", "empty"] as const).map((a) => (
                  <button
                    key={a}
                    type="button"
                    onClick={() => setParam({ avail: a })}
                    aria-pressed={avail === a}
                    className={`rounded-full border px-2.5 py-1 text-xs font-medium transition-colors ${avail === a ? "border-primary bg-primary/15 text-primary" : "border-border text-muted-foreground hover:bg-muted"}`}
                  >
                    {a === "all" ? "All" : a === "available" ? "In stock" : a === "low" ? "Low" : "Out of stock"}
                  </button>
                ))}
              </div>
            </div>
            <div>
              <Label className="mb-1.5 block text-xs font-semibold uppercase tracking-wide text-muted-foreground">Sort</Label>
              <select
                aria-label="Sort"
                className="flex h-9 w-full rounded-lg border border-input bg-background px-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
                value={sort}
                onChange={(e) => setParam({ sort: e.target.value as "creative" | "count" | "name" | "id" })}
              >
                <option value="count">Most stocked</option>
                <option value="creative">Creative order</option>
                <option value="name">Name A–Z</option>
                <option value="id">ID</option>
              </select>
            </div>
          </CardContent>
        </Card>
      </aside>

      {/* ---------- center: catalog ---------- */}
      <div className="min-w-0 flex-1">
        <div className="mb-3 flex items-baseline justify-between gap-2">
          <h1 className="text-2xl font-bold tracking-tight">Shop the stash</h1>
          <p className="summary shrink-0 text-sm text-muted-foreground" role="status">
            {indexLoading ? "Loading…" : `${grouped(items.length)} items`}{mcVersion ? ` · MC ${mcVersion}` : ""}
          </p>
        </div>
        <div className="relative mb-3">
          <span aria-hidden className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground">⌕</span>
          <Input
            className="central-search h-11 pl-9 text-[15px]"
            type="search"
            aria-label="Search the stash"
            placeholder='Search all stashes — try "diamond", "@minecraft" or "=minecraft:diamond"'
            value={q}
            onChange={(e) => setParam({ q: e.target.value })}
          />
        </div>

        {indexLoading ? (
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-4">
            {Array.from({ length: 8 }).map((_, i) => (
              <Card key={i} className="h-[296px] p-3"><Skeleton className="h-32 w-full" /><Skeleton className="mt-3 h-4 w-3/4" /><Skeleton className="mt-2 h-4 w-1/2" /></Card>
            ))}
          </div>
        ) : indexError ? (
          <Card className="p-8 text-center">
            <p className="text-lg font-semibold">Stash unavailable</p>
            <p className="mt-1 text-sm text-muted-foreground">Could not load the inventory. Check the connection and retry.</p>
            <Button variant="secondary" className="mt-3" onClick={() => queryClient.invalidateQueries({ queryKey: ["index"] })}>Retry</Button>
          </Card>
        ) : !anyStash ? (
          <Card className="p-8 text-center">
            <p className="text-lg font-semibold">No stash available</p>
            <p className="mt-1 text-sm text-muted-foreground">You don't have access to any stash yet, or none exists.</p>
          </Card>
        ) : items.length === 0 ? (
          <Card className="p-8 text-center">
            <p className="text-lg font-semibold">{q || cat !== "all" ? "No matching items" : "Empty inventory"}</p>
            <p className="mt-1 text-sm text-muted-foreground">
              {q || cat !== "all" ? "Try a different search or category." : "The stashes hold no orderable items right now."}
            </p>
            {(q || cat !== "all") && (
              <Button variant="secondary" className="mt-3" onClick={() => setParam({ q: "", cat: "all" })}>Clear filters</Button>
            )}
          </Card>
        ) : (
          <div ref={gridRef} id="item-grid" className="overflow-auto pb-1" style={{ maxHeight: "62vh", minHeight: 320 }} role="list" aria-label="Products">
            <div style={{ height: virtual.getTotalSize(), position: "relative" }}>
              {virtual.getVirtualItems().map((row) => {
                const entries = rows[row.index] ?? [];
                return (
                  <div
                    key={row.index}
                    className="grid gap-3"
                    style={{ position: "absolute", top: 0, left: 0, right: 0, height: ROW_H, transform: `translateY(${row.start}px)`, gridTemplateColumns: `repeat(${cols}, minmax(0, 1fr))` }}
                  >
                    {entries.map((entry) => (
                      <ProductCard
                        key={entry.key}
                        entry={entry}
                        mcVersion={mcVersion}
                        max={orderableMax(entry.key)}
                        onAdd={(n) => {
                          if (addToCart(entry.key, n)) toast(`Added ${grouped(n)} × ${entry.name ?? entry.id}`);
                        }}
                        onOpen={() => setBuy(entry)}
                      />
                    ))}
                  </div>
                );
              })}
            </div>
          </div>
        )}
        <p className="hint mt-2 text-xs text-muted-foreground">Click a card for amount options · Shift-click or double-click for a quick stack. Touch: tap, or hit +.</p>

        {recentOrders.length > 0 && (
          <Card className="mt-4">
            <CardHeader className="flex-row items-center justify-between pb-2">
              <CardTitle className="text-base">Recent orders</CardTitle>
              <Button variant="ghost" size="sm" onClick={() => navigate({ to: "/orders", search: { q: "" } })}>View all →</Button>
            </CardHeader>
            <CardContent className="flex flex-col gap-1.5">
              {recentOrders.map((o) => (
                <OrderRow key={o.id} orderId={o.id} status={o.status} lines={o.lines.map((l) => `${l.key} ×${l.count}`).join(" · ")} message={o.message} />
              ))}
            </CardContent>
          </Card>
        )}
      </div>

      {/* ---------- right: sticky cart ---------- */}
      <aside className="hidden w-80 shrink-0 xl:block">
        <div className="sticky top-20">
          <CartPanel
            lines={cartLines}
            badLine={cartBad}
            step={step}
            setStep={setStep}
            looseTotal={looseTotal}
            packedBoxes={packedBoxes}
            cartUnits={cartUnits}
            estimatedTotal={estimatedTotal}
            overLines={overLines}
            placing={placing}
            submitState={submitState}
            stashOnline={!anyOffline}
            slices={slices}
            form={form}
            canGuild={perms.can("guildOrders")}
            onRemove={(i) => cart.setLines(cartLines.filter((_, xi) => xi !== i))}
            onClear={() => {
              if (cartLines.length && window.confirm("Remove everything from the cart?")) cart.clear();
            }}
            onSetQty={setLineQuantity}
            onPlace={() => form.handleSubmit()}
            canOrder={perms.can("order")}
          />
        </div>
      </aside>

      {/* ---------- mobile: sticky cart bar + drawer ---------- */}
      <div className="fixed inset-x-0 bottom-0 z-40 border-t border-border bg-background/95 p-3 backdrop-blur xl:hidden">
        <div className="mx-auto flex max-w-[1400px] items-center gap-3">
          <div className="text-sm">
            <span className="font-bold">{grouped(cartUnits)} items</span>
            <span className="text-muted-foreground"> · {packedBoxes} packed · ≈{estimatedTotal} shulkers</span>
          </div>
          <Button className="ml-auto" disabled={!cartLines.length} onClick={() => setCartOpen(true)}>
            View cart{cartLines.length ? ` (${cartLines.length})` : ""}
          </Button>
        </div>
      </div>

      <Sheet open={cartOpen} onOpenChange={setCartOpen}>
        <SheetContent side="right" aria-label="Shopping cart">
          <SheetHeader>
            <SheetTitle>Cart</SheetTitle>
          </SheetHeader>
          <div className="flex-1 overflow-auto">
            <CartPanel
              lines={cartLines}
              badLine={cartBad}
              step={step}
              setStep={setStep}
              looseTotal={looseTotal}
              packedBoxes={packedBoxes}
              cartUnits={cartUnits}
              estimatedTotal={estimatedTotal}
              overLines={overLines}
              placing={placing}
              submitState={submitState}
              stashOnline={!anyOffline}
              slices={slices}
              form={form}
              canGuild={perms.can("guildOrders")}
              onRemove={(i) => cart.setLines(cartLines.filter((_, xi) => xi !== i))}
              onClear={() => {
                if (cartLines.length && window.confirm("Remove everything from the cart?")) cart.clear();
              }}
              onSetQty={setLineQuantity}
              onPlace={() => form.handleSubmit()}
              canOrder={perms.can("order")}
            />
          </div>
        </SheetContent>
      </Sheet>

      <Dialog open={buy !== null} onOpenChange={(o) => { if (!o) setBuy(null); }}>
        <DialogContent aria-label={buy ? (buy.name ?? buy.id) : "Buy"}>
          {buy && (
            <BuyDialog
              item={buy}
              available={buy.count}
              max={orderableMax(buy.key)}
              boxed={buy.boxed}
              onAdd={(n) => {
                if (addToCart(buy.key, n)) {
                  toast(`Added ${grouped(n)} × ${buy.name ?? buy.id}`);
                  setBuy(null);
                }
              }}
            />
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}

function FilterRow({ active, onClick, label, count }: { active: boolean; onClick: () => void; label: string; count: number }) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={`flex items-center justify-between rounded-lg px-2.5 py-1.5 text-sm transition-colors ${active ? "bg-primary/15 font-semibold text-primary" : "text-muted-foreground hover:bg-muted hover:text-foreground"}`}
    >
      <span className="truncate">{label}</span>
      <span className="ml-2 font-mono text-xs opacity-70">{grouped(count)}</span>
    </button>
  );
}

function RescanButton({ stash, compact }: { stash: string; compact?: boolean }) {
  const [state, setState] = useState("");
  async function rescan() {
    setState("Requesting…");
    try {
      await api<{ rescanId: number }>("/api/v1/rescan", { method: "POST", body: { stash } });
      setState("Rescan requested.");
    } catch (e: any) {
      setState(e?.message ?? "Rescan failed.");
    }
  }
  return (
    <span className={compact ? "ml-auto inline-flex shrink-0 items-center gap-1" : "block"}>
      <Button variant="secondary" size="sm" className={compact ? "h-6 px-1.5 text-[11px]" : "w-full"} onClick={rescan}>Rescan</Button>
      {state && !compact && <span role="status" className="mt-1 block text-xs text-muted-foreground">{state}</span>}
    </span>
  );
}

export function OrderRow({ orderId, status, lines, message, stash }: { orderId: string; status: string; lines: string; message?: string | null; stash?: string }) {
  return (
    <div className="order-card flex items-center gap-2.5 rounded-lg border border-border/70 px-3 py-2 text-sm">
      <span className={`h-2.5 w-2.5 shrink-0 rounded-full ${statusDot(status)}`} aria-hidden />
      <Badge variant={statusVariant(status)}>{status.replace(/_/g, " ")}</Badge>
      <span className="font-mono text-xs text-muted-foreground">{orderId}</span>
      <span className="min-w-0 flex-1 truncate opacity-90">{lines}</span>
      {stash && <span className="hidden shrink-0 font-mono text-[11px] text-muted-foreground md:inline">{stash}</span>}
      {message && <span className="hidden max-w-48 truncate text-xs text-muted-foreground lg:inline">{message}</span>}
    </div>
  );
}

export function statusDot(status: string): string {
  if (status === "delivered") return "bg-emerald-500";
  if (status === "failed" || status === "cancelled" || status === "needs_reconciliation") return "bg-red-500";
  if (status === "pending" || status === "packed") return "bg-amber-500";
  return "bg-sky-500";
}

export function statusVariant(status: string): "success" | "warning" | "destructive" | "secondary" {
  if (status === "delivered") return "success";
  if (status === "failed" || status === "cancelled" || status === "needs_reconciliation") return "destructive";
  if (status === "pending" || status === "packed") return "warning";
  return "secondary";
}

function ProductCard({
  entry,
  mcVersion,
  max,
  onAdd,
  onOpen,
}: {
  entry: MergedItem;
  mcVersion: string | null;
  max: number;
  onAdd: (n: number) => void;
  onOpen: () => void;
}) {
  const total = entry.count + entry.boxed;
  const empty = total <= 0;
  const low = !empty && total <= entry.maxStack;
  const tip = `Count: ${grouped(total)} (≈ ${approx(total / entry.maxStack)} stacks, ≈ ${approx(total / (entry.maxStack * 27))} shulkers)${entry.boxed > 0 ? ` · ${grouped(entry.count)} loose + ${grouped(entry.boxed)} in shulker boxes` : ""}`;
  return (
    <InfoTip text={tip}>
      <button
        type="button"
        role="listitem"
        className="slot group relative flex h-[296px] w-full flex-col gap-2 rounded-xl border border-border bg-card p-3 text-left shadow-sm transition-all hover:-translate-y-0.5 hover:border-primary/50 hover:shadow-lg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-60"
        aria-label={`${entry.name ?? entry.id} ×${grouped(total)}`}
        disabled={empty}
        onClick={(e) => {
          if (e.shiftKey) {
            const n = Math.min(entry.maxStack, max);
            if (n > 0) onAdd(n);
          } else onOpen();
        }}
        onDoubleClick={() => {
          const n = Math.min(entry.maxStack, max);
          if (n > 0) onAdd(n);
        }}
      >
        <span className="ws-slot h-32 w-full shrink-0">
          <ItemIcon id={entry.id} size={56} contents={entry.contents} label={entry.name ?? undefined} mcVersion={mcVersion} />
        </span>
        <span className="line-clamp-2 min-h-10 text-[13px] font-semibold leading-snug">{entry.name ?? entry.id}</span>
        <span className="mt-auto font-mono text-lg font-bold text-emerald-600 dark:text-emerald-300">×{grouped(total)}</span>
        <span className="flex items-center gap-1 text-[11px] text-muted-foreground">
          <span className={`h-1.5 w-1.5 shrink-0 rounded-full ${empty ? "bg-red-500" : low ? "bg-amber-500" : "bg-emerald-500"}`} aria-hidden />
          <span className="truncate">{entry.boxed > 0 && !empty ? `${grouped(entry.boxed)} boxed` : empty ? "out of stock" : "in stock"}</span>
          {!empty && max > 0 && (
            <span
              role="button"
              tabIndex={-1}
              aria-label={`Quick add ${entry.name ?? entry.id}`}
              className="ml-auto rounded-md bg-primary/15 px-2 py-0.5 font-bold text-primary transition-colors hover:bg-primary hover:text-primary-foreground"
              onClick={(e) => {
                e.stopPropagation();
                const n = Math.min(entry.maxStack, max);
                if (n > 0) onAdd(n);
              }}
            >
              +
            </span>
          )}
        </span>
      </button>
    </InfoTip>
  );
}

function groupLines(lines: CartLine[]): { stash: string; lines: CartLine[] }[] {
  const out = new Map<string, { stash: string; lines: CartLine[] }>();
  for (const l of lines) {
    const k = l.stash;
    if (!out.has(k)) out.set(k, { stash: l.stash, lines: [] });
    out.get(k)!.lines.push(l);
  }
  return [...out.values()];
}

function CartPanel(props: {
  lines: CartLine[];
  badLine: number;
  step: 1 | 2 | 3;
  setStep: (n: 1 | 2 | 3) => void;
  looseTotal: number;
  packedBoxes: number;
  cartUnits: number;
  estimatedTotal: number;
  overLines: number[];
  placing: boolean;
  submitState: { status: string; unknownOutcome?: boolean } | null;
  stashOnline: boolean;
  slices: StashSlice[];
  form: CheckoutForm;
  canGuild: boolean;
  onRemove: (i: number) => void;
  onClear: () => void;
  onSetQty: (i: number, n: number) => void;
  onPlace: () => void;
  canOrder: boolean;
}) {
  const { lines, badLine, step, setStep } = props;
  const groups = groupLines(lines);
  const names = (key: string) => {
    for (const s of props.slices) {
      const it = s.items.find((x) => x.key === key);
      if (it?.name) return it.name;
    }
    return key;
  };
  const itemIdOf = (key: string) => {
    for (const s of props.slices) {
      const it = s.items.find((x) => x.key === key);
      if (it) return it.id;
    }
    return undefined;
  };
  return (
    <Card>
      <CardHeader className="flex-row items-center justify-between pb-2">
        <CardTitle className="text-base">Cart <Badge variant="secondary" className="ml-1">{lines.length}</Badge></CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <Steps step={step} onGo={(n) => {
          if ((n === 2 || n === 3) && !lines.length) return;
          setStep(n);
        }} />
        {step === 1 && (
          <div id="cart-step-1" className="flex flex-col gap-2">
            {!lines.length ? (
              <div className="rounded-xl border border-dashed border-border px-4 py-8 text-center">
                <p className="text-2xl" aria-hidden>🛒</p>
                <p className="mt-1 text-sm text-muted-foreground">Cart is empty. Add items from the grid.</p>
              </div>
            ) : (
              <>
                <ul id="cart-lines" className="flex max-h-80 flex-col gap-2 overflow-auto">
                  {lines.map((l, i) => (
                    <li key={`${l.type}:${l.key}:${l.stash}`} className={`cart-line flex items-center gap-2 rounded-lg border p-2 ${badLine === i || props.overLines.includes(i) ? "border-destructive/60" : "border-border/70"}`}>
                      <span className="ws-slot h-11 w-11 shrink-0">
                        <ItemIcon id={l.type === "shulker" ? "minecraft:shulker_box" : (itemIdOf(l.key) ?? "minecraft:stone")} size={30} label={l.type === "shulker" ? undefined : names(l.key)} />
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-[13px] font-semibold" title={l.type === "shulker" ? `Packed: ${l.key}` : names(l.key)}>{l.type === "shulker" ? `Packed: ${l.key}` : (names(l.key))}</span>
                        <span className="block truncate text-[11px] text-muted-foreground" title={`${l.type === "shulker" ? "whole shulker box" : "loose items"} · ${l.stash}`}>{l.type === "shulker" ? "whole box" : "loose"} · {l.stash}</span>
                      </span>
                      <QtyInput small value={l.count} min={1} onChange={(n) => props.onSetQty(i, n)} />
                      <button type="button" aria-label={`Remove ${l.key}`} className="rounded-md px-1.5 py-1 text-muted-foreground hover:bg-muted hover:text-foreground" onClick={() => props.onRemove(i)}>✕</button>
                    </li>
                  ))}
                </ul>
                <div className="rounded-lg bg-muted/60 p-2.5 text-sm">
                  <div className="flex justify-between"><span>Total</span><span id="cart-totals" className="font-mono font-bold">{grouped(props.cartUnits)} items</span></div>
                  <div className="flex justify-between text-xs text-muted-foreground"><span>Packed boxes</span><span className="font-mono">{props.packedBoxes}</span></div>
                  <div className="flex justify-between text-xs text-muted-foreground"><span>Loose items</span><span className="font-mono">{grouped(props.looseTotal)}</span></div>
                  <div className="flex justify-between text-xs text-muted-foreground"><span>Est. shulkers to pack</span><span className="font-mono">≈{props.estimatedTotal}</span></div>
                </div>
                {props.overLines.length > 0 && (
                  <p role="alert" className="rounded-lg border border-amber-500/40 bg-amber-500/10 p-2 text-xs text-amber-600 dark:text-amber-300">
                    Availability changed — {props.overLines.length} line{props.overLines.length > 1 ? "s exceed" : " exceeds"} current stock. Adjust quantities before placing.
                  </p>
                )}
                <Button id="cart-continue" disabled={!lines.length} onClick={() => setStep(2)}>Continue to delivery →</Button>
                <Button id="cart-clear" variant="ghost" size="sm" onClick={props.onClear}>Clear cart</Button>
              </>
            )}
            <p id="cart-status" role="status" className="min-h-5 text-sm text-muted-foreground">{props.submitState?.status ?? ""}</p>
            {!props.stashOnline && <p id="cart-offline" className="rounded-lg border border-amber-500/40 bg-amber-500/10 p-2 text-xs text-amber-600 dark:text-amber-300">A bot is offline. Orders queue and start when it reconnects.</p>}
          </div>
        )}
        {step === 2 && <DeliveryForm form={props.form} setStep={setStep} groups={groups} canGuild={props.canGuild} />}
        {step === 3 && <ReviewStep {...props} groups={groups} />}
      </CardContent>
    </Card>
  );
}

function DeliveryForm({ form, setStep, groups, canGuild }: { form: CheckoutForm; setStep: (n: 1 | 2 | 3) => void; groups: { stash: string; lines: CartLine[] }[]; canGuild: boolean }) {
  return (
    <div id="cart-step-2" className="flex flex-col gap-3">
      <div className="rounded-lg bg-muted/60 p-2 text-xs text-muted-foreground">
        Ordering from {groups.length} stash{groups.length > 1 ? "es" : ""}: {groups.map((g) => g.stash).join(" · ")}.
        One order is placed per stash.
      </div>
      <form.Field name="purpose">
        {(field) => (
          <div className="flex flex-col gap-1">
            <Label>Order for</Label>
            <select
              id="order-purpose"
              className="flex h-9 w-full rounded-lg border border-input bg-background px-2 text-sm"
              value={field.state.value}
              onChange={(e) => field.handleChange(e.target.value as "SELF" | "GUILD")}
            >
              <option value="SELF">Myself</option>
              {canGuild && <option value="GUILD">The guild</option>}
            </select>
          </div>
        )}
      </form.Field>
      <form.Field name="recipient" validators={{ onChange: zodField(deliverySchemas.recipient) }}>
        {(field) => (
          <div className="flex flex-col gap-1">
            <Label htmlFor="order-recipient">Confirmed recipient (optional)</Label>
            <Input id="order-recipient" maxLength={16} autoComplete="off" value={field.state.value} onChange={(e) => field.handleChange(e.target.value)} />
            {field.state.meta.errors?.length ? <p className="text-xs text-destructive">{String(field.state.meta.errors[0])}</p> : null}
          </div>
        )}
      </form.Field>
      <form.Field name="comment" validators={{ onChange: zodField(deliverySchemas.comment) }}>
        {(field) => (
          <div className="flex flex-col gap-1">
            <Label htmlFor="order-comment">Comment (optional)</Label>
            <Input id="order-comment" maxLength={500} value={field.state.value} onChange={(e) => field.handleChange(e.target.value)} />
            {field.state.meta.errors?.length ? <p className="text-xs text-destructive">{String(field.state.meta.errors[0])}</p> : null}
          </div>
        )}
      </form.Field>
      <form.Field name="namesMode">
        {(field) => (
          <fieldset className="rounded-xl border border-border p-2.5">
            <legend className="px-1 text-sm font-semibold">Shulker names</legend>
            <div className="flex gap-1.5" role="group" aria-label="Shulker names" id="names-seg">
              {(["none", "all", "each"] as const).map((m) => (
                <button
                  key={m}
                  type="button"
                  data-mode={m}
                  aria-pressed={field.state.value === m}
                  onClick={() => field.handleChange(m)}
                  className={`rounded-lg border px-2.5 py-1 text-xs font-semibold ${field.state.value === m ? "border-primary bg-primary/15 text-primary" : "border-border text-muted-foreground hover:bg-muted"}`}
                >
                  {m === "none" ? "No names" : m === "all" ? "Same name" : "Name each"}
                </button>
              ))}
            </div>
            {field.state.value === "all" && (
              <form.Field name="namesAll" validators={{ onChange: zodField(deliverySchemas.namesAll) }}>
                {(nf) => (
                  <div className="mt-2">
                    <Input id="names-all" maxLength={50} placeholder="Name for every shulker" value={nf.state.value} onChange={(e) => nf.handleChange(e.target.value)} />
                    {nf.state.meta.errors?.length ? <p className="mt-1 text-xs text-destructive">{String(nf.state.meta.errors[0])}</p> : null}
                  </div>
                )}
              </form.Field>
            )}
            {field.state.value === "each" && <EachNames form={form} />}
          </fieldset>
        )}
      </form.Field>
      <div className="flex gap-2">
        <Button id="cart-back-2" variant="secondary" onClick={() => setStep(1)}>← Back</Button>
        <form.Subscribe selector={(s) => [s.canSubmit, s.isSubmitting]}>
          {([canSubmit]) => (
            <Button id="cart-to-review" disabled={!canSubmit} onClick={() => setStep(3)}>Continue to review →</Button>
          )}
        </form.Subscribe>
      </div>
    </div>
  );
}

function EachNames({ form }: { form: CheckoutForm }) {
  return (
    <form.Field name="namesEach" mode="array">
      {(field) => (
        <div className="mt-2 flex flex-col gap-1.5">
          <p className="text-xs text-muted-foreground">One name per planned shulker (estimate — the bot plans again from what it can reach).</p>
          {(field.state.value ?? []).map((_: string, i: number) => (
            <form.Field key={i} name={`namesEach[${i}]`} validators={{ onChange: zodField(deliverySchemas.namesEachItem) }}>
              {(nf) => <Input aria-label={`Name for shulker ${i + 1}`} maxLength={50} placeholder={`Shulker ${i + 1} (unnamed)`} value={(nf.state.value ?? "") as string} onChange={(e) => nf.handleChange(e.target.value)} />}
            </form.Field>
          ))}
          <div>
            <button
              type="button"
              onClick={() => {
                const cur = field.state.value ?? [];
                if (cur.length < 54) field.handleChange([...cur, ""]);
              }}
              className="rounded-lg border border-border px-2.5 py-1 text-xs font-semibold text-muted-foreground hover:bg-muted"
            >
              + Add name row
            </button>
          </div>
        </div>
      )}
    </form.Field>
  );
}

function ReviewStep(props: Parameters<typeof CartPanel>[0] & { groups: { stash: string; lines: CartLine[] }[] }) {
  const v = props.form.state.values;
  return (
    <div id="cart-step-3" className="flex flex-col gap-2">
      <div id="order-review" className="flex min-w-0 flex-col gap-2 break-words rounded-lg bg-muted/60 p-2.5 text-sm">
        {props.groups.map((g) => (
          <div key={g.stash}>
            <p className="font-semibold">{g.stash}</p>
            {g.lines.map((l) => (
              <p key={`${l.type}:${l.key}`} className="break-words text-muted-foreground">
                <span className="font-mono font-bold text-foreground">×{grouped(l.count)}</span> {l.type === "shulker" ? `packed ${l.key}` : l.key}
              </p>
            ))}
          </div>
        ))}
        <Separator className="my-1" />
        <p className="break-words text-muted-foreground">
          For {v.purpose === "GUILD" ? "the guild" : "myself"}
          {v.recipient ? ` · recipient ${v.recipient}` : ""}
          {v.comment ? ` · “${v.comment}”` : ""}
          {v.namesMode === "all" && v.namesAll?.trim() ? ` · named “${v.namesAll.trim()}”` : ""}
          {v.namesMode === "each" ? ` · ${(v.namesEach ?? []).filter((s) => s?.trim()).length} custom names` : ""}
        </p>
        {!props.stashOnline && <p className="text-amber-500">Delivery: a bot is offline — orders queue and start on reconnect.</p>}
      </div>
      <div className="flex gap-2">
        <Button id="cart-back-3" variant="secondary" onClick={() => props.setStep(2)}>← Back</Button>
        <Button id="cart-place" disabled={props.placing || !props.lines.length || !props.canOrder} onClick={props.onPlace}>
          {props.placing ? "Placing…" : props.groups.length > 1 ? `Place ${props.groups.length} orders` : "Place order"}
        </Button>
      </div>
      {!props.canOrder && <p className="text-xs text-destructive">Your account can't place orders.</p>}
      <p id="cart-status" role="status" className="min-h-5 break-words text-sm text-muted-foreground">{props.submitState?.status ?? ""}</p>
      {props.submitState?.unknownOutcome && (
        <Button variant="secondary" size="sm" onClick={props.onPlace}>Retry safely (same idempotency keys)</Button>
      )}
    </div>
  );
}

function BuyDialog({
  item,
  available,
  max,
  boxed,
  onAdd,
}: {
  item: MergedItem;
  available: number;
  max: number;
  boxed: number;
  onAdd: (n: number) => void;
}) {
  const [n, setN] = useState(Math.max(1, Math.min(item.maxStack, available || max)));
  const unit = item.maxStack;
  return (
    <>
      <DialogHeader>
        <DialogTitle>{item.name ?? item.id}</DialogTitle>
        <DialogDescription className="font-mono">{item.id}</DialogDescription>
      </DialogHeader>
      <div className="buy-head flex items-center gap-3">
        <span className="ws-slot h-20 w-20 shrink-0">
          <ItemIcon id={item.id} size={60} contents={item.contents} label={item.name ?? undefined} />
        </span>
        <p className="text-sm">
          <span className="font-mono text-lg font-bold">×{grouped(available + boxed)}</span> available across all stashes
          <span className="block text-xs text-muted-foreground">
            {grouped(available)} loose{boxed > 0 ? ` + ${grouped(boxed)} in full shulkers, taken as-is` : ""}
          </span>
        </p>
      </div>
      <div className="buy-row flex flex-wrap items-center gap-2">
        <QtyInput min={1} max={Math.max(1, max)} value={n} onChange={setN} />
        <Button variant="secondary" size="sm" onClick={() => setN((v) => Math.min(Math.max(1, max), v + unit))}>+1 stack</Button>
        <Button variant="secondary" size="sm" onClick={() => setN(Math.max(1, max))}>Max</Button>
        <Button onClick={() => onAdd(Math.max(1, Math.min(n, Math.max(1, max))))} disabled={max < 1}>
          Add to cart
        </Button>
      </div>
      <p className="text-xs text-muted-foreground">
        ≈ {approx(n / unit)} stacks · ≈ {approx(n / (unit * 27))} shulkers. Full boxes pack automatically from the fullest stash.
      </p>
      {max < 1 && <p className="note warn mt-1 text-xs text-amber-500">None left to order.</p>}
    </>
  );
}
