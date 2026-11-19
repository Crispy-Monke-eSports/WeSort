import { useVirtualizer } from "@tanstack/react-virtual";
import { useEffect, useMemo, useRef, useState } from "react";
import { api, idempotencyKey } from "../api/client";
import { permissionsOf, useAuth, useIndex, useInvalidateOrders, useOrders, useStashes } from "../api/queries";
import type { IndexItem, OrderLine } from "../api/types";
import { ItemIcon, maskServer } from "../components/icons";
import { Badge, Button, Card, Dialog, Input, Select, Tooltip } from "../components/ui";
import { approx, boxedContents, grouped, isShulkerBoxId, shelfCount, shulkersNeeded, splitItemLine, takeBoxes, MAX_SHULKERS } from "../lib/shop";
import { setCartScope, useCart, usePrefs } from "../stores/shop";

interface StashSel {
  server: string;
  stash: string;
}

function parseQuery(q: string): string[] {
  return q.toLowerCase().split(/\s+/).filter(Boolean);
}

function matches(item: IndexItem, tokens: string[]): boolean {
  if (!tokens.length) return true;
  const hay = `${item.name ?? ""} ${item.englishName ?? ""} ${item.id} ${item.key}`.toLowerCase();
  return tokens.every((t) => {
    if (t.startsWith("@")) return item.id.toLowerCase().split(":")[0]?.includes(t.slice(1)) ?? false;
    if (t.startsWith("=")) return item.key.toLowerCase() === t.slice(1) || item.id.toLowerCase() === t.slice(1);
    return hay.includes(t);
  });
}

export function ShopPage() {
  const auth = useAuth();
  const perms = permissionsOf(auth.data);
  const prefs = usePrefs();
  const cart = useCart();
  const stashes = useStashes(!!auth.data?.loggedIn && perms.has("view"));
  const [sel, setSel] = useState<StashSel | null>(null);
  const active: StashSel | null = sel ?? (stashes.data?.[0] ? { server: stashes.data[0].server, stash: stashes.data[0].stash } : null);
  const stashMeta = stashes.data?.find((s) => active && s.server === active.server && s.stash === active.stash);
  const index = useIndex(active?.server ?? null, active?.stash ?? null, !!active && perms.has("view"));
  const [visible, setVisible] = useState(true);
  const orders = useOrders(active?.server ?? null, active?.stash ?? null, !!active && perms.has("view"), visible);
  const invalidateOrders = useInvalidateOrders();

  if (auth.data?.user && active) setCartScope(auth.data.user.id, active.server, active.stash);

  const [buy, setBuy] = useState<IndexItem | null>(null);
  const [step, setStep] = useState<1 | 2 | 3>(1);
  const [purpose, setPurpose] = useState("SELF");
  const [recipient, setRecipient] = useState("");
  const [comment, setComment] = useState("");
  const [quotaOverride, setQuotaOverride] = useState(false);
  const [namesMode, setNamesMode] = useState<"none" | "all" | "each">("none");
  const [namesAll, setNamesAll] = useState("");
  const [placing, setPlacing] = useState(false);
  const [status, setStatus] = useState("");

  useEffect(() => {
    const onVis = () => setVisible(!document.hidden);
    document.addEventListener("visibilitychange", onVis);
    return () => document.removeEventListener("visibilitychange", onVis);
  }, []);

  const items = useMemo(() => {
    const list = (index.data?.items ?? []).filter((e) => !isShulkerBoxId(e.id));
    const tokens = parseQuery(prefs.query);
    const shown = list.filter((e) => matches(e, tokens));
    const sort = prefs.sort;
    shown.sort((a, b) => {
      if (sort === "name") return (a.name ?? a.id).localeCompare(b.name ?? b.id);
      if (sort === "id") return a.id.localeCompare(b.id);
      const ca = shelfCount(a, index.data?.shulkers ?? []);
      const cb = shelfCount(b, index.data?.shulkers ?? []);
      return cb - ca;
    });
    return shown;
  }, [index.data, prefs.query, prefs.sort]);

  const gridRef = useRef<HTMLDivElement>(null);
  const virtual = useVirtualizer({ count: items.length, getScrollElement: () => gridRef.current, estimateSize: () => 96, overscan: 8 });

  const availableItems = orders.data?.available.items ?? {};
  const availableLeft = (key: string) => availableItems[key] ?? 0;

  function cartCountOf(type: string, key: string): number {
    return cart.lines.filter((l) => l.type === type && l.key === key).reduce((a, l) => a + l.count, 0);
  }

  function orderableMax(type: "item" | "shulker", key: string, exclude: number): number {
    const base = Math.max(1, (type === "item" ? availableLeft(key) : (orders.data?.available.shulkers[key] ?? 0)) - (cartCountOf(type, key) - exclude));
    if (type !== "item") return base;
    const shulkers = index.data?.shulkers ?? [];
    let boxed = 0;
    for (const s of shulkers) {
      let perBox = 0;
      for (const c of s.contents ?? []) if (c.key === key) perBox += Math.max(0, c.count);
      boxed += perBox * Math.max(0, s.count - cartCountOf("shulker", s.key));
    }
    return base + boxed;
  }

  function addToCart(type: "item" | "shulker", key: string, n: number): boolean {
    const shulkers = index.data?.shulkers ?? [];
    const next = cart.lines.map((l) => ({ ...l }));
    const found = next.find((l) => l.type === type && l.key === key);
    if (found) found.count += n;
    else next.push({ type, key, count: n });
    if (type === "item" && !isShulkerBoxId(key)) {
      const split = splitItemLine(next, key, availableLeft(key), shulkers);
      if (!split) {
        const boxed = boxedContents(key, shulkers);
        setStatus(`Only ${grouped(availableLeft(key))} loose available (${grouped(boxed)} in full shulkers).`);
        return false;
      }
      next.length = 0;
      next.push(...split);
    }
    const need = shulkersNeeded(
      next,
      (k) => index.data?.items.find((i) => i.key === k)?.maxStack ?? 64,
      (k) => !!index.data?.items.find((i) => i.key === k && isShulkerBoxId(i.id)),
    );
    if (need > MAX_SHULKERS) {
      setStatus(`That needs ${need} shulkers. One order can fill at most ${MAX_SHULKERS}.`);
      return false;
    }
    cart.setLines(next);
    setStatus("");
    return true;
  }

  async function place() {
    if (!active || !cart.lines.length || placing) return;
    setPlacing(true);
    setStatus("Placing the order...");
    const names = namesMode === "all" && namesAll.trim() ? { mode: "all", name: namesAll.trim() } : null;
    try {
      await api("/api/v1/orders", {
        method: "POST",
        body: {
          server: active.server,
          stash: active.stash,
          lines: cart.lines,
          names,
          purpose,
          comment,
          recipient: recipient || undefined,
          quotaOverride: quotaOverride || undefined,
          idempotencyKey: idempotencyKey(),
        },
      });
      cart.clear();
      setStep(1);
      setStatus(stashMeta?.online === false ? "Order placed. It will start when the bot reconnects." : "Order placed.");
      invalidateOrders(active.server, active.stash);
    } catch (e: any) {
      const line = e?.body?.line;
      if (typeof line === "number") cart.setBadLine(line);
      setStatus(e?.message ?? "Could not place the order.");
    } finally {
      setPlacing(false);
    }
  }

  return (
    <div>
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <Select id="stash-select" aria-label="Stash" value={active ? `${active.server}/${active.stash}` : ""} onChange={(e) => {
          const v = e.target.value.split("/");
          setSel({ server: v[0], stash: v.slice(1).join("/") });
          cart.clear();
          setStep(1);
        }}>
          {(stashes.data ?? []).map((s) => (
            <option key={`${s.server}/${s.stash}`} value={`${s.server}/${s.stash}`}>
              {prefs.streamer ? s.stash : `${s.server} / ${s.stash}`}{s.online === false ? " (offline)" : ""}
            </option>
          ))}
        </Select>
        <Input
          className="central-search min-w-64 flex-1"
          type="search"
          aria-label="Search the stash"
          placeholder="Search the stash (e.g. diamond sword, @minecraft)"
          value={prefs.query}
          onChange={(e) => prefs.setQuery(e.target.value)}
        />
        <Select aria-label="Sort" value={prefs.sort} onChange={(e) => prefs.setSort(e.target.value as any)}>
          <option value="count">Count</option>
          <option value="name">Name A-Z</option>
          <option value="id">ID</option>
          <option value="creative">Creative order</option>
        </Select>
      </div>

      {stashMeta?.online === false && (
        <p className="note warn mb-2" role="status">Bot offline. Orders queue and start when it reconnects.</p>
      )}

      <div ref={gridRef} id="item-grid" className="grid max-h-[50vh] grid-cols-[repeat(auto-fill,minmax(88px,1fr))] gap-2 overflow-auto" style={{ minHeight: 200 }}>
        {virtual.getVirtualItems().map((row) => {
          const entry = items[row.index];
          if (!entry) return null;
          const total = shelfCount(entry, index.data?.shulkers ?? []);
          const boxed = isShulkerBoxId(entry.id) ? 0 : boxedContents(entry.key, index.data?.shulkers ?? []);
          const tip = `Count: ${grouped(total)} (≈ ${approx(total / entry.maxStack)} stacks, ≈ ${approx(total / (entry.maxStack * 27))} shulkers)${boxed > 0 ? ` · ${grouped(entry.count)} loose + ${grouped(boxed)} in shulker boxes` : ""}`;
          return (
            <Tooltip key={entry.key} text={tip}>
              <button
                type="button"
                className="slot group flex flex-col items-center rounded border border-stone-700 bg-stone-800/60 p-2 hover:border-amber-400"
                style={{ height: row.size }}
                aria-label={`${entry.name ?? entry.id} ×${grouped(total)}`}
                onClick={(e) => {
                  if (e.shiftKey) {
                    const n = Math.min(entry.maxStack, orderableMax("item", entry.key, 0));
                    if (n > 0) addToCart("item", entry.key, n);
                  } else setBuy(entry);
                }}
                onDoubleClick={() => {
                  const n = Math.min(entry.maxStack, orderableMax("item", entry.key, 0));
                  if (n > 0) addToCart("item", entry.key, n);
                }}
              >
                <ItemIcon id={entry.id} size={40} contents={entry.contents} />
                <span className="mt-1 text-xs">{maskServer(entry.name ?? entry.id, false)}</span>
                <span className="text-xs font-bold">×{grouped(total)}</span>
                <span className="text-[10px] opacity-70">+ quick</span>
              </button>
            </Tooltip>
          );
        })}
      </div>
      <p className="summary mt-1 text-sm opacity-80" role="status">
        {items.length} items · {index.data?.mcVersion ? `MC ${index.data.mcVersion}` : ""}
      </p>
      <p className="hint only-desktop text-xs opacity-70">Click an item to choose an amount, or Shift-click it for a quick stack.</p>
      <p className="hint only-mobile text-xs opacity-70">Tap an item to choose an amount, or use its + for a quick stack.</p>

      <Card className="mt-4">
        <div className="cart-head mb-2 flex items-center gap-2">
          <h2 className="text-lg font-semibold">Cart</h2>
          <Badge>{cart.lines.length} lines</Badge>
        </div>
        <ol className="cart-steps mb-2 flex gap-2" aria-label="Checkout steps">
          {[1, 2, 3].map((n) => (
            <li key={n}>
              <button type="button" className={`step-pill mc-button small ${step === n ? "selected" : ""}`} data-step={n} aria-current={step === n ? "step" : undefined} onClick={() => setStep(n as 1 | 2 | 3)}>
                {n} · {n === 1 ? "Cart" : n === 2 ? "Delivery" : "Review"}
              </button>
            </li>
          ))}
        </ol>
        {step === 1 && (
          <div id="cart-step-1">
            <ul id="cart-lines" className="flex flex-col gap-1">
              {cart.lines.map((l, i) => (
                <li key={`${l.type}:${l.key}`} className={`cart-line flex items-center gap-2 rounded border p-1 ${cart.badLine === i ? "border-red-500" : "border-stone-700"}`}>
                  <ItemIcon id={l.type === "shulker" ? "minecraft:shulker_box" : (index.data?.items.find((x) => x.key === l.key)?.id ?? "minecraft:stone")} size={24} />
                  <span className="flex-1 text-sm">{l.type === "shulker" ? `packed ${l.key}` : l.key} ×{l.count}</span>
                  <Input
                    type="number"
                    aria-label="Quantity"
                    min={1}
                    value={l.count}
                    onChange={(e) => {
                      const next = cart.lines.map((x) => ({ ...x }));
                      next[i].count = Math.max(1, Number(e.target.value) || 1);
                      if (l.type === "item") {
                        const split = splitItemLine(next.filter((x, xi) => xi !== i || true), l.key, availableLeft(l.key), index.data?.shulkers ?? []);
                        if (split) cart.setLines(split);
                        else setStatus(`Only ${grouped(availableLeft(l.key))} loose available.`);
                      } else cart.setLines(next);
                    }}
                    className="w-20"
                  />
                  <Button className="small" onClick={() => cart.setLines(cart.lines.filter((_, xi) => xi !== i))}>Remove</Button>
                </li>
              ))}
            </ul>
            {!cart.lines.length && <p className="note text-sm opacity-70">Cart is empty. Add items from the grid.</p>}
            <div id="cart-totals" className="mt-1 text-sm">
              Packing: {cart.lines.filter((l) => l.type === "item").reduce((a, l) => a + l.count, 0) + cart.lines.filter((l) => l.type === "shulker").length} items (
              {cart.lines.filter((l) => l.type === "shulker").length} packed boxes ·{" "}
              {cart.lines.filter((l) => l.type === "item").reduce((a, l) => a + l.count, 0)} loose)
            </div>
            <div className="mt-2 flex gap-2">
              <Button id="cart-clear" className="danger" onClick={() => { if (cart.lines.length && window.confirm("Remove everything from the cart?")) cart.clear(); }}>Clear cart</Button>
              <Button id="cart-continue" disabled={!cart.lines.length} onClick={() => setStep(2)}>Continue to delivery</Button>
            </div>
            <p id="cart-status" role="status" className="note mt-1">{status}</p>
            {stashMeta?.online === false && <p id="cart-offline" className="note warn">Bot offline. The order will start when it reconnects.</p>}
          </div>
        )}
        {step === 2 && (
          <div id="cart-step-2" className="flex flex-col gap-2">
            <fieldset className="names-box rounded border p-2">
              <legend>Shulker names</legend>
              <div className="seg flex gap-1" role="group" aria-label="Shulker names" id="names-seg">
                {(["none", "all"] as const).map((m) => (
                  <button key={m} type="button" className={`mc-button small seg-btn ${namesMode === m ? "selected" : ""}`} data-mode={m} aria-pressed={namesMode === m} onClick={() => setNamesMode(m)}>
                    {m === "none" ? "No names" : "Same name for all"}
                  </button>
                ))}
              </div>
              {namesMode === "all" && <Input id="names-all" maxLength={50} placeholder="Name for every shulker" value={namesAll} onChange={(e) => setNamesAll(e.target.value)} />}
            </fieldset>
            {perms.has("guildOrders") && (
              <label>Order for <Select id="order-purpose" value={purpose} onChange={(e) => setPurpose(e.target.value)}><option value="SELF">Myself</option><option value="GUILD">The guild</option></Select></label>
            )}
            <label>Confirmed Minecraft recipient (optional) <Input id="order-recipient" maxLength={16} value={recipient} onChange={(e) => setRecipient(e.target.value)} /></label>
            <label>Comment (optional) <Input id="order-comment" maxLength={500} value={comment} onChange={(e) => setComment(e.target.value)} /></label>
            {perms.has("ordersAll") && (
              <label id="order-quota-override-row"><input type="checkbox" id="order-quota-override" checked={quotaOverride} onChange={(e) => setQuotaOverride(e.target.checked)} /> Override withdrawal quota (audited, approvers only)</label>
            )}
            <div className="flex gap-2">
              <Button id="cart-back-2" onClick={() => setStep(1)}>Back</Button>
              <Button id="cart-to-review" onClick={() => setStep(3)}>Continue to review</Button>
            </div>
          </div>
        )}
        {step === 3 && (
          <div id="cart-step-3">
            <div id="order-review" className="mb-2 text-sm">
              {cart.lines.map((l) => <p key={`${l.type}:${l.key}`}>{l.type}:{l.key} ×{l.count}</p>)}
              <p>Order for {purpose === "GUILD" ? "the guild" : "myself"}{recipient ? ` · recipient ${recipient}` : ""}{comment ? ` · ${comment}` : ""}</p>
              {stashMeta?.online === false && <p>Delivery: Bot offline — starts when it reconnects</p>}
            </div>
            <p className="note text-xs">Packing into shulker boxes is automatic. The bot may deliver less if it can't reach everything.</p>
            <div className="mt-2 flex gap-2">
              <Button id="cart-back-3" onClick={() => setStep(2)}>Back</Button>
              <Button id="cart-place" disabled={placing || !cart.lines.length} onClick={place}>Place order</Button>
            </div>
            <p id="order-status" role="status" className="note mt-1">{status}</p>
          </div>
        )}
      </Card>

      <OrderHistory server={active?.server ?? null} stash={active?.stash ?? null} />

      <Dialog open={!!buy} onClose={() => setBuy(null)} title={buy ? (buy.name ?? buy.id) : "Buy"}>
        {buy && (
          <BuyDialog
            item={buy}
            available={availableLeft(buy.key)}
            max={orderableMax("item", buy.key, 0)}
            boxed={boxedContents(buy.key, index.data?.shulkers ?? [])}
            onAdd={(n) => {
              if (addToCart("item", buy.key, n)) setBuy(null);
            }}
          />
        )}
      </Dialog>
    </div>
  );
}

export function OrderHistory({ server, stash }: { server: string | null; stash: string | null }) {
  const auth = useAuth();
  const me = auth.data?.user?.name ?? auth.data?.user?.id;
  const orders = useOrders(server, stash, !!server && !!stash, true);
  const [filter, setFilter] = useState("");
  const mine = (orders.data?.orders ?? []).filter((o) => {
    const by = (o.placedBy as any)?.name ?? (o.placedBy as any)?.id;
    return !me || !by || by === me;
  });
  const list = mine.filter((o) => !filter || o.id.includes(filter) || o.status.includes(filter));
  return (
    <Card className="mt-4">
      <h2 id="order-history-title" className="mb-2 text-lg font-semibold">Your orders</h2>
      <Input aria-label="Filter orders" placeholder="Filter by id or status" value={filter} onChange={(e) => setFilter(e.target.value)} className="mb-2" />
      <ul id="order-list" className="flex flex-col gap-1">
        {list.slice(0, 50).map((o) => (
          <li key={o.id} className="order-card flex items-center gap-2 rounded border border-stone-700 p-1 text-sm">
            <Badge className={o.status === "pending" ? "border-amber-400" : o.status === "delivered" ? "border-green-500" : ""}>{o.status}</Badge>
            <span className="font-mono">{o.id}</span>
            <span className="opacity-70">{o.lines?.map((l: OrderLine) => `${l.type}:${l.key}×${l.count}`).join(", ")}</span>
            {o.message && <span className="opacity-70">{o.message}</span>}
          </li>
        ))}
      </ul>
      {!list.length && <p className="text-sm opacity-70">No orders yet.</p>}
      {orders.isError && <p role="alert" className="text-sm text-red-400">Could not refresh orders. Showing the last list.</p>}
    </Card>
  );
}

function BuyDialog({ item, available, max, boxed, onAdd }: { item: IndexItem; available: number; max: number; boxed: number; onAdd: (n: number) => void }) {
  const [n, setN] = useState(Math.max(1, Math.min(item.maxStack, available || max)));
  const { took } = takeBoxes(item.key, n, [], () => 0);
  void took;
  return (
    <div>
      <div className="buy-head mb-2 flex items-center gap-2">
        <ItemIcon id={item.id} size={64} contents={item.contents} />
        <strong>{item.name ?? item.id}</strong>
      </div>
      <p className="note mb-2 text-sm">
        {grouped(available + boxed)} available ({grouped(available)} loose{boxed > 0 ? ` + ${grouped(boxed)} in full shulkers, taken as-is` : ""})
      </p>
      <div className="buy-row flex items-center gap-2">
        <Input type="number" className="qty-input w-24" min={1} max={Math.max(1, max)} value={n} aria-label="Amount" onChange={(e) => setN(Math.max(1, Number(e.target.value) || 1))} />
        <Button className="small" onClick={() => setN((v) => Math.min(Math.max(1, max), v + item.maxStack))}>+1 stack</Button>
        <Button className="small" onClick={() => setN(Math.max(1, max))}>Max</Button>
        <Button onClick={() => onAdd(Math.max(1, Math.min(n, Math.max(1, max))))} disabled={max < 1}>Add to cart</Button>
      </div>
      {max < 1 && <p className="note warn mt-1">None left to order.</p>}
    </div>
  );
}
