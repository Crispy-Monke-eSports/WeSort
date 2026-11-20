import AddShoppingCartIcon from "@mui/icons-material/AddShoppingCart";
import CheckCircleIcon from "@mui/icons-material/CheckCircle";
import DeleteIcon from "@mui/icons-material/Delete";
import ErrorIcon from "@mui/icons-material/Error";
import FilterListIcon from "@mui/icons-material/FilterList";
import GridViewIcon from "@mui/icons-material/GridView";
import HelpCenterIcon from "@mui/icons-material/HelpCenter";
import ShoppingCartIcon from "@mui/icons-material/ShoppingCart";
import TableRowsIcon from "@mui/icons-material/TableRows";
import Alert from "@mui/material/Alert";
import Badge from "@mui/material/Badge";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Card from "@mui/material/Card";
import CardActions from "@mui/material/CardActions";
import CardContent from "@mui/material/CardContent";
import Chip from "@mui/material/Chip";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import Divider from "@mui/material/Divider";
import Drawer from "@mui/material/Drawer";
import FormControl from "@mui/material/FormControl";
import Grid from "@mui/material/Grid";
import IconButton from "@mui/material/IconButton";
import InputLabel from "@mui/material/InputLabel";
import MenuItem from "@mui/material/MenuItem";
import Paper from "@mui/material/Paper";
import Select from "@mui/material/Select";
import Skeleton from "@mui/material/Skeleton";
import Stack from "@mui/material/Stack";
import Step from "@mui/material/Step";
import StepLabel from "@mui/material/StepLabel";
import Stepper from "@mui/material/Stepper";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TablePagination from "@mui/material/TablePagination";
import TableRow from "@mui/material/TableRow";
import TextField from "@mui/material/TextField";
import ToggleButton from "@mui/material/ToggleButton";
import ToggleButtonGroup from "@mui/material/ToggleButtonGroup";
import Typography from "@mui/material/Typography";
import useMediaQuery from "@mui/material/useMediaQuery";
import { useTheme } from "@mui/material/styles";
import { useQueries, useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useState } from "react";
import { api, idempotencyKey } from "../api/client";
import { keys, ordersQueryOptions, useAuth, usePermissions, useStashes } from "../api/queries";
import { IndexResponseSchema, type IndexResponse } from "../api/types";
import {
  boxedContents,
  grouped,
  isShulkerBoxId,
  isSingleContent,
  labelTextError,
  LABEL_MAX_TEXT,
  mixedBoxedContents,
  netBoxedContents,
  netBoxes,
  payloadHash,
  shulkersNeeded,
} from "../lib/shop";
import { setCartScope, useCart, useCheckout, type CartLine, type CheckoutGroup, type OrderBody } from "../stores/shop";
import { ItemIcon } from "../components/minecraft/ItemIcon";
import { PresenceChip } from "../components/status";

const TAB_ORDER = [
  "building_blocks", "colored_blocks", "natural_blocks", "functional_blocks",
  "redstone_blocks", "tools_and_utilities", "combat", "food_and_drinks",
  "ingredients", "spawn_eggs", "op_blocks", "other",
];

interface MergedItem {
  key: string;
  id: string;
  name?: string | null;
  englishName?: string | null;
  maxStack: number;
  tab?: string | null;
  count: number;
  boxed: number;
  mixed: number;
}

function tabLabel(tab: string): string {
  return tab.split("_").map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(" ");
}

interface SliceState {
  stash: string;
  online: boolean | null;
  lastSeen?: number | null;
  indexOk: boolean;
  indexError: boolean;
  ordersOk: boolean;
  items: IndexResponse["items"];
  shulkers: IndexResponse["shulkers"];
  availableItems: Record<string, number>;
  availableShulkers: Record<string, number>;
}

function statusOf(group: CheckoutGroup): { label: string; color: "default" | "info" | "success" | "warning" | "error" } {
  switch (group.status) {
    case "confirmed": return { label: `Confirmed ${group.orderId ?? ""}`.trim(), color: "success" };
    case "submitting": return { label: "Submitting…", color: "info" };
    case "uncertain": return { label: "Uncertain — retry safely", color: "warning" };
    case "rejected": return { label: `Rejected: ${group.error ?? "failed"}`, color: "error" };
    default: return { label: "Not submitted", color: "default" };
  }
}

export function InventoryPage() {
  const auth = useAuth();
  const perms = usePermissions(auth.data);
  const cart = useCart();
  const queryClient = useQueryClient();
  const theme = useTheme();
  const isMobile = useMediaQuery(theme.breakpoints.down("md"));
  const userId = auth.data?.user?.id ?? "anon";
  const getPending = useCheckout((s) => s.getPending);
  const setPendingStore = useCheckout((s) => s.setPending);

  useEffect(() => {
    setCartScope(userId);
  }, [userId]);

  const canView = !!auth.data?.loggedIn && perms.can("view");
  const canOrder = perms.can("order");
  const stashes = useStashes(canView);
  const stashList = useMemo(() => stashes.data ?? [], [stashes.data]);

  // ---- data ----
  const indexQueries = useQueries({
    queries: stashList.map((s) => ({
      queryKey: keys.index(s.stash),
      queryFn: async () =>
        IndexResponseSchema.parse(await api<unknown>(`/api/v1/index?stash=${encodeURIComponent(s.stash)}`)) as IndexResponse,
      enabled: canView,
      staleTime: 5_000,
      retry: 1 as const,
    })),
  });
  const orderQueries = useQueries({
    // Same canonical entries as useAllOrders: OrdersResponse per stash.
    queries: stashList.map((s) => ordersQueryOptions(s.stash, canView)),
  });

  const slices: SliceState[] = useMemo(
    () =>
      stashList.map((s, i) => ({
        stash: s.stash,
        online: s.online ?? null,
        lastSeen: s.lastSeen ?? null,
        indexOk: !!indexQueries[i]?.data,
        indexError: !!indexQueries[i]?.isError,
        ordersOk: !!orderQueries[i]?.data,
        items: indexQueries[i]?.data?.items ?? [],
        shulkers: indexQueries[i]?.data?.shulkers ?? [],
        availableItems: orderQueries[i]?.data?.available.items ?? {},
        availableShulkers: orderQueries[i]?.data?.available.shulkers ?? {},
      })),
    [stashList, indexQueries, orderQueries],
  );
  const mcVersion = indexQueries.find((q) => q.data?.mcVersion)?.data?.mcVersion ?? null;
  const loading = canView && stashList.length > 0 && indexQueries.some((q) => q.isLoading);
  const indexFailed = canView && stashList.length > 0 && indexQueries.some((q) => q.isError) && slices.every((s) => !s.indexOk);

  // ---- catalog ----
  const [q, setQ] = useState("");
  const [sort, setSort] = useState<"count" | "name" | "id">("count");
  const [cat, setCat] = useState("all");
  const [avail, setAvail] = useState<"all" | "orderable" | "empty" | "unverified">("all");
  const [view, setView] = useState<"grid" | "table">("grid");
  const [filterOpen, setFilterOpen] = useState(false);

  const merged = useMemo(() => {
    const byKey = new Map<string, MergedItem>();
    for (const s of slices) {
      for (const it of s.items) {
        if (isShulkerBoxId(it.id)) continue;
        const m = byKey.get(it.key);
        if (!m) {
          byKey.set(it.key, {
            key: it.key, id: it.id, name: it.name, englishName: it.englishName,
            maxStack: it.maxStack ?? 64, tab: it.tab ?? "other", count: it.count,
            boxed: boxedContents(it.key, s.shulkers), mixed: mixedBoxedContents(it.key, s.shulkers),
          });
        } else {
          m.count += it.count;
          m.boxed += boxedContents(it.key, s.shulkers);
          m.mixed += mixedBoxedContents(it.key, s.shulkers);
          if (!m.name && it.name) { m.name = it.name; m.id = it.id; }
        }
      }
    }
    return [...byKey.values()];
  }, [slices]);

  /**
   * Guild-wide orderable units, or null when reservation state is UNKNOWN
   * (orders API failed: never present uncertain stock as orderable or as empty).
   */
  const orderableOf = (key: string): number | null => {
    let unknown = false;
    let looseTotal = 0;
    let exact = 0;
    for (const s of slices) {
      if (!s.ordersOk) { unknown = true; continue; }
      looseTotal += Math.max(0, (s.availableItems[key] ?? 0) - looseInCart(s.stash, key));
      for (const b of s.shulkers) {
        if (!isSingleContent(b, key)) continue;
        let perBox = 0;
        for (const c of b.contents ?? []) perBox += Math.max(0, c?.count ?? 0);
        // Server reservations first: a box held by another open order is not orderable.
        exact += perBox * Math.max(0, netBoxes(b.key, b.count, s.availableShulkers) - packedInCart(s.stash, b.key));
      }
    }
    if (unknown && looseTotal + exact <= 0) return null;
    return looseTotal + exact;
  };

  const items = useMemo(() => {
    const tokens = q.toLowerCase().split(/\s+/).filter(Boolean);
    let shown = merged.filter((e) => {
      if (!tokens.length) return true;
      const hay = `${e.name ?? ""} ${e.englishName ?? ""} ${e.id} ${e.key}`.toLowerCase();
      return tokens.every((t) => hay.includes(t));
    });
    if (cat !== "all") shown = shown.filter((e) => (e.tab ?? "other") === cat);
    if (avail === "orderable") shown = shown.filter((e) => (orderableOf(e.key) ?? 0) > 0);
    else if (avail === "empty") shown = shown.filter((e) => orderableOf(e.key) !== null && (orderableOf(e.key) ?? 0) <= 0);
    else if (avail === "unverified") shown = shown.filter((e) => slices.some((s) => !s.ordersOk || !s.indexOk));
    shown.sort((a, b) => sort === "name"
      ? (a.name ?? a.id).localeCompare(b.name ?? b.id)
      : sort === "id" ? a.id.localeCompare(b.id) : (b.count + b.boxed) - (a.count + a.boxed));
    return shown;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [merged, q, cat, avail, sort, slices, cart]);

  const categories = useMemo(() => {
    const counts = new Map<string, number>();
    for (const e of merged) counts.set(e.tab ?? "other", (counts.get(e.tab ?? "other") ?? 0) + 1);
    return [...counts.entries()].sort((a, b) => TAB_ORDER.indexOf(a[0]) - TAB_ORDER.indexOf(b[0]));
  }, [merged]);

  // ---- cart ----
  const cartLines = cart.lines();
  const packedInCart = (stash: string, boxKey: string) =>
    cartLines.filter((l) => l.type === "shulker" && l.stash === stash && l.key === boxKey).reduce((a, l) => a + l.count, 0);
  const inCartTotal = (key: string) =>
    cartLines.filter((l) => l.key === key).reduce((a, l) => a + l.count, 0);
  const looseInCart = (stash: string, key: string) =>
    cartLines.filter((l) => l.type === "item" && l.stash === stash && l.key === key).reduce((a, l) => a + l.count, 0);

  function addToCart(key: string, n: number): boolean {
    if (n < 1 || !canOrder) return false;
    // Guild-wide split: loose units first (most-loose stash first), then whole
    // single-content boxes (biggest first). Mixed boxes are never packed whole.
    // Anything left over fails instead of over-promising. Committed to the
    // draft in one step; each stash still becomes its OWN backend order
    // (no cross-stash transaction — partial success keeps failed groups
    // retryable with the same idempotency key).
    const next: CartLine[] = cartLines.map((l) => ({ ...l }));
    let remaining = n;
    const looseInNext = (stash: string) =>
      next.filter((l) => l.type === "item" && l.key === key && l.stash === stash).reduce((a, l) => a + l.count, 0);
    const packedInNext = (stash: string, boxKey: string) =>
      next.filter((l) => l.type === "shulker" && l.key === boxKey && l.stash === stash).reduce((a, l) => a + l.count, 0);
    const byLoose = slices
      .map((s) => ({ s, loose: Math.max(0, (s.availableItems[key] ?? 0) - looseInNext(s.stash)) }))
      .filter((x) => x.loose > 0)
      .sort((a, b) => b.loose - a.loose);
    for (const { s } of byLoose) {
      if (remaining <= 0) break;
      const take = Math.min(remaining, Math.max(0, (s.availableItems[key] ?? 0) - looseInNext(s.stash)));
      if (take <= 0) continue;
      const found = next.find((l) => l.type === "item" && l.key === key && l.stash === s.stash);
      if (found) found.count += take;
      else next.push({ type: "item", key, count: take, stash: s.stash });
      remaining -= take;
    }
    if (remaining > 0) {
      const cands: { stash: string; boxKey: string; perBox: number; left: number }[] = [];
      for (const s of slices) {
        for (const b of s.shulkers) {
          if (!isSingleContent(b, key)) continue;
          let perBox = 0;
          for (const c of b.contents ?? []) perBox += Math.max(0, c?.count ?? 0);
          const left = Math.max(0, netBoxes(b.key, b.count, s.availableShulkers) - packedInNext(s.stash, b.key));
          if (perBox > 0 && left > 0) cands.push({ stash: s.stash, boxKey: b.key, perBox, left });
        }
      }
      cands.sort((a, b) => b.perBox - a.perBox);
      for (const c of cands) {
        while (c.left > 0 && c.perBox <= remaining) {
          const found = next.find((l) => l.type === "shulker" && l.key === c.boxKey && l.stash === c.stash);
          if (found) found.count += 1;
          else next.push({ type: "shulker", key: c.boxKey, count: 1, stash: c.stash });
          c.left -= 1;
          remaining -= c.perBox;
        }
        if (remaining <= 0) break;
      }
    }
    if (remaining > 0) return false;
    cart.setLines(next);
    return true;
  }

  const [buy, setBuy] = useState<MergedItem | null>(null);
  const [cartOpen, setCartOpen] = useState(false);

  // ---- checkout state machine (P0 2.3) ----
  const [checkoutOpen, setCheckoutOpen] = useState(false);
  const [step, setStep] = useState(0);
  const [placing, setPlacing] = useState(false);
  const [groups, setGroups] = useState<CheckoutGroup[]>(() => getPending(userId).filter((g) => g.status !== "confirmed"));
  // Re-hydrate when the authenticated identity resolves (userId is "anon"
  // while auth loads): never strand uncertain outcomes under the wrong key.
  // Re-hydrate when the authenticated identity resolves (userId is "anon" while
  // auth loads): only identity changes re-seed the view, so in-session
  // confirmations are never stripped by a store update.
  useEffect(() => {
    setGroups(getPending(userId).filter((g) => g.status !== "confirmed"));
  }, [userId, getPending]);
  const [purpose, setPurpose] = useState<"SELF" | "GUILD">("SELF");
  const [recipient, setRecipient] = useState("");
  const [comment, setComment] = useState("");
  const [labelMode, setLabelMode] = useState<"none" | "all" | "each">("none");
  const [labelText, setLabelText] = useState("");
  const [labelTexts, setLabelTexts] = useState<string[]>([]);

  function buildBodies(): OrderBody[] {
    const byStash = new Map<string, CartLine[]>();
    for (const l of cartLines) {
      if (!byStash.has(l.stash)) byStash.set(l.stash, []);
      byStash.get(l.stash)!.push(l);
    }
    // W-064: the user picks only the text part; the server generates `WeSort (X/Y) - text`.
    const labelTextClean = labelText.trim().slice(0, 33);
    const packageLabel =
      labelMode === "all" && labelTextClean
        ? { mode: "all" as const, text: labelTextClean }
        : labelMode === "each" && labelTexts.some((s) => (s ?? "").trim())
          ? { mode: "each" as const, texts: labelTexts.map((s) => (s ?? "").trim().slice(0, 33)).slice(0, 54) }
          : null;
    return [...byStash.entries()].map(([stash, lines]) => ({
      stash,
      lines: lines.map((l) => ({ type: l.type, key: l.key, count: l.count })),
      names: null,
      packageLabel,
      purpose,
      recipient: recipient.trim() ? recipient.trim().slice(0, 16) : null,
      comment: comment.trim() ? comment.trim().slice(0, 500) : null,
    }));
  }

  function persist(next: CheckoutGroup[]) {
    setPendingStore(userId, next);
    setGroups(next);
  }

  async function submitGroup(g: CheckoutGroup): Promise<CheckoutGroup> {
    const working: CheckoutGroup = { ...g, status: "submitting", error: undefined };
    persist(getPending(userId).map((p) => (p.hash === g.hash && p.body.stash === g.body.stash ? working : p)));
    try {
      const res = await api<{ ok: boolean; order: { id: string } }>("/api/v1/orders", {
        method: "POST",
        headers: { "Idempotency-Key": g.key },
        body: g.body,
      });
      const done: CheckoutGroup = { ...working, status: "confirmed", orderId: res.order.id, updatedAt: Date.now() };
      persist(getPending(userId).map((p) => (p.hash === g.hash && p.body.stash === g.body.stash ? done : p)));
      return done;
    } catch (e: any) {
      const status: number | undefined = e?.status;
      const line = e?.body?.line;
      if (typeof line === "number") {
        const idx = cart.lines().findIndex((l) => l.stash === g.body.stash);
        if (idx >= 0) cart.setBadLine(idx);
      }
      const failed: CheckoutGroup =
        status === 0 || status === undefined
          ? { ...working, status: "uncertain", error: "uncertain outcome", updatedAt: Date.now() }
          : { ...working, status: "rejected", error: e?.message ?? "failed", updatedAt: Date.now() };
      persist(getPending(userId).map((p) => (p.hash === g.hash && p.body.stash === g.body.stash ? failed : p)));
      return failed;
    }
  }

  /** Place the whole cart: one order per stash, confirmed lines removed, failures kept. */
  async function place(): Promise<void> {
    if (!cartLines.length || placing || !canOrder) return;
    setPlacing(true);
    const bodies = buildBodies();
    const persisted = getPending(userId);
    // Reuse the stored key AND exact body on hash match (recipient/purpose/comment/names included).
    const merged: CheckoutGroup[] = bodies.map((body) => {
      const hash = payloadHash(body);
      const prior = persisted.find((p) => p.body.stash === body.stash && p.hash === hash && p.status !== "confirmed");
      if (prior) return { ...prior, status: "not_submitted" as const };
      return { body, hash, key: idempotencyKey(), status: "not_submitted" as const, updatedAt: Date.now() };
    });
    // Keep older uncertain entries for other payloads so they can be retried exactly or discarded.
    const stale = persisted.filter(
      (p) => p.status === "uncertain" && !merged.some((m) => m.body.stash === p.body.stash && m.hash === p.hash),
    ).slice(0, 10);
    persist([...merged, ...stale]);
    const settled: CheckoutGroup[] = [];
    for (const g of merged) {
      const out = await submitGroup(g);
      settled.push(out);
      if (out.status === "confirmed") removeConfirmedLines(out.body);
    }
    for (const s of new Set(merged.map((m) => m.body.stash))) queryClient.invalidateQueries({ queryKey: keys.orders(s) });
    setPlacing(false);
    setStep(3);
  }

  function removeConfirmedLines(body: OrderBody) {
    const current = cart.lines();
    const wanted = new Map<string, number>();
    for (const l of body.lines) wanted.set(`${l.type}:${l.key}`, (wanted.get(`${l.type}:${l.key}`) ?? 0) + l.count);
    const have = new Map<string, number>();
    for (const l of current) {
      if (l.stash !== body.stash) continue;
      have.set(`${l.type}:${l.key}`, (have.get(`${l.type}:${l.key}`) ?? 0) + l.count);
    }
    let same = wanted.size === have.size;
    if (same) for (const [k, v] of wanted) if (have.get(k) !== v) { same = false; break; }
    // Only auto-remove when the cart still holds exactly the confirmed lines (user didn't edit after).
    if (same) cart.setLines(current.filter((l) => l.stash !== body.stash));
  }

  async function retryExact(g: CheckoutGroup) {
    // Same confirmation/cart reconciliation as the normal submit path, so a
    // successful retry never leaves already-ordered lines in the draft.
    const out = await submitGroup(g);
    if (out.status === "confirmed") removeConfirmedLines(out.body);
    queryClient.invalidateQueries({ queryKey: keys.orders(g.body.stash) });
  }

  function discard(hash: string) {
    persist(getPending(userId).filter((p) => p.hash !== hash));
  }

  const uncertainCount = groups.filter((g) => g.status === "uncertain").length;
  const confirmedCount = groups.filter((g) => g.status === "confirmed").length;

  // ---- pagination ----
  const [page, setPage] = useState(0);
  const [rowsPerPage, setRowsPerPage] = useState(25);

  const filters = (
    <Stack spacing={2}>
      <TextField label="Search items" value={q} onChange={(e) => setQ(e.target.value)} size="small" fullWidth placeholder="diamond, redstone…" />
      <FormControl size="small" fullWidth>
        <InputLabel id="inv-cat-label">Category</InputLabel>
        <Select value={cat} labelId="inv-cat-label" id="inv-cat" label="Category" onChange={(e) => setCat(e.target.value)}>
          <MenuItem value="all">All categories</MenuItem>
          {categories.map(([c, n]) => <MenuItem key={c} value={c}>{tabLabel(c)} ({n})</MenuItem>)}
        </Select>
      </FormControl>
      <FormControl size="small" fullWidth>
        <InputLabel id="inv-avail-label">Availability</InputLabel>
        <Select value={avail} labelId="inv-avail-label" id="inv-avail" label="Availability" onChange={(e) => setAvail(e.target.value as typeof avail)}>
          <MenuItem value="all">All</MenuItem>
          <MenuItem value="orderable">Orderable</MenuItem>
          <MenuItem value="empty">Empty</MenuItem>
          <MenuItem value="unverified">Unverified</MenuItem>
        </Select>
      </FormControl>
      <FormControl size="small" fullWidth>
        <InputLabel id="inv-sort-label">Sort</InputLabel>
        <Select value={sort} labelId="inv-sort-label" id="inv-sort" label="Sort" onChange={(e) => setSort(e.target.value as typeof sort)}>
          <MenuItem value="count">Most stock</MenuItem>
          <MenuItem value="name">Name</MenuItem>
          <MenuItem value="id">Item ID</MenuItem>
        </Select>
      </FormControl>
    </Stack>
  );

  return (
    <Stack spacing={2}>
      <Stack direction="row" spacing={2}>
        <Typography variant="h1" sx={{ flexGrow: 1 }}>Inventory</Typography>
        <ToggleButtonGroup value={view} exclusive onChange={(_, v) => v && setView(v)} size="small" aria-label="Inventory view">
          <ToggleButton value="grid" aria-label="Grid view"><GridViewIcon /></ToggleButton>
          <ToggleButton value="table" aria-label="Table view"><TableRowsIcon /></ToggleButton>
        </ToggleButtonGroup>
        {isMobile && (
          <IconButton onClick={() => setFilterOpen(true)} aria-label="Open filters"><FilterListIcon /></IconButton>
        )}
        <Button
          variant="contained"
          startIcon={<Badge badgeContent={cartLines.length} color="secondary"><ShoppingCartIcon /></Badge>}
          onClick={() => setCartOpen(true)}
          disabled={!cartLines.length}
        >
          Cart
        </Button>
      </Stack>

      {!serverOk(slices) && canView && (
        <Alert severity="warning">Some stash data is unavailable — availability below may be partial. Failed stashes are marked per row.</Alert>
      )}
      {stashes.isError && canView && (
        <Alert severity="error">Could not load the stash list. Check the Manager connection and reload.</Alert>
      )}
      {uncertainCount > 0 && !checkoutOpen && (
        <Alert
          severity="warning"
          action={<Button color="inherit" size="small" onClick={() => { setStep(3); setCheckoutOpen(true); }}>Review and retry</Button>}
        >
          {uncertainCount} stash order{uncertainCount === 1 ? "" : "s"} may or may not have reached the server (network failure or reload). Retry reuses the exact same request and idempotency key — no duplicates.
        </Alert>
      )}

      <Grid container spacing={2}>
        {!isMobile && (
          <Grid size={{ md: 3, lg: 2 }}>
            <Paper sx={{ p: 2, position: "sticky", top: 88 }}>
              <Typography variant="h4" gutterBottom>Filters</Typography>
              {filters}
            </Paper>
          </Grid>
        )}
        <Grid size={{ xs: 12, md: 9, lg: 10 }}>
          <TextField
            label="Search items" value={q} onChange={(e) => setQ(e.target.value)} size="small" fullWidth
            sx={{ mb: 2, maxWidth: 420 }}
            placeholder="diamond, redstone…"
          />
          {loading ? (
            <Grid container spacing={2}>{[0, 1, 2, 3, 4, 5].map((i) => <Grid key={i} size={{ xs: 12, sm: 6, lg: 4 }}><Skeleton variant="rounded" height={180} /></Grid>)}</Grid>
          ) : indexFailed ? (
            <Alert severity="error">Could not load the guild stock. Check that a bot is connected and rescanning.</Alert>
          ) : view === "grid" ? (
            <Grid container spacing={2} data-testid="item-grid">
              {items.slice(0, 120).map((e) => (
                <Grid key={e.key} size={{ xs: 12, sm: 6, lg: 4, xl: 3 }}>
                  <ItemCard item={e} slices={slices} mcVersion={mcVersion} onBuy={() => setBuy(e)} canOrder={canOrder} orderable={orderableOf(e.key)} inCart={inCartTotal(e.key)} />
                </Grid>
              ))}
            </Grid>
          ) : (
            <TableContainer component={Paper}>
              <Table size="small" aria-label="Guild stock table">
                <TableHead>
                  <TableRow>
                    <TableCell>Item</TableCell>
                    <TableCell align="right">Loose</TableCell>
                    <TableCell align="right">Whole boxes</TableCell>
                    <TableCell align="right">Mixed boxes</TableCell>
                    <TableCell align="right">Orderable</TableCell>
                    <TableCell>Status</TableCell>
                    <TableCell align="right">Order</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {items.slice(page * rowsPerPage, page * rowsPerPage + rowsPerPage).map((e) => (
                  <InventoryRow key={e.key} item={e} slices={slices} mcVersion={mcVersion} onBuy={() => setBuy(e)} canOrder={canOrder} orderable={orderableOf(e.key)} inCart={inCartTotal(e.key)} />
                  ))}
                </TableBody>
              </Table>
              <TablePagination
                component="div"
                count={items.length}
                page={page}
                rowsPerPage={rowsPerPage}
                rowsPerPageOptions={[25, 50, 100]}
                onPageChange={(_, p) => setPage(p)}
                onRowsPerPageChange={(e) => { setRowsPerPage(Number(e.target.value)); setPage(0); }}
              />
            </TableContainer>
          )}
          {view === "grid" && items.length > 120 && (
            <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>Showing 120 of {grouped(items.length)} items — refine search or use table view with pagination.</Typography>
          )}
        </Grid>
      </Grid>

      <Drawer anchor={isMobile ? "bottom" : "right"} open={filterOpen} onClose={() => setFilterOpen(false)}>
        <Box sx={{ p: 2, width: isMobile ? "auto" : 320 }}>
          <Typography variant="h4" gutterBottom>Filters</Typography>
          {filters}
          <Button sx={{ mt: 2 }} fullWidth variant="contained" onClick={() => setFilterOpen(false)}>Show results</Button>
        </Box>
      </Drawer>

      <BuyDialog item={buy} slices={slices} mcVersion={mcVersion} canOrder={canOrder} remaining={buy ? (orderableOf(buy.key) ?? 0) : 0} onClose={() => setBuy(null)} onAdd={(n) => { if (buy && addToCart(buy.key, n)) setBuy(null); }} />
      <CartDrawer
        open={cartOpen} onClose={() => setCartOpen(false)} lines={cartLines} slices={slices}
        onCheckout={() => { setCartOpen(false); setStep(0); setCheckoutOpen(true); }}
      />
      <CheckoutDialog
        open={checkoutOpen} onClose={() => setCheckoutOpen(false)} step={step} setStep={setStep}
        lines={cartLines} groups={groups} placing={placing} onPlace={place}
        purpose={purpose} setPurpose={setPurpose} recipient={recipient} setRecipient={setRecipient}
        comment={comment} setComment={setComment} labelMode={labelMode} setLabelMode={setLabelMode}
        labelText={labelText} setLabelText={setLabelText} labelTexts={labelTexts} setLabelTexts={setLabelTexts}
        onRetryExact={retryExact} onDiscard={discard} uncertainCount={uncertainCount} confirmedCount={confirmedCount}
        canOrder={canOrder} slices={slices}
      />
    </Stack>
  );
}

function serverOk(slices: SliceState[]): boolean {
  return slices.every((s) => s.indexOk && s.ordersOk);
}

/**
 * W-064 box-label text field: 33-unit counter, server-mirroring validation and
 * a live `WeSort (1/Y) - text` preview (Y estimated; the server locks it).
 */
function LabelTextField({ label, value, onChange, previewY }: {
  label: string; value: string; onChange: (v: string) => void; previewY?: number;
}) {
  const error = labelTextError(value);
  const units = (value ?? "").length; // UTF-16 units, like the server counts
  return (
    <TextField
      label={label}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      size="small"
      fullWidth
      error={!!error}
      helperText={
        error ?? `${units}/${LABEL_MAX_TEXT}${previewY && value.trim() ? ` · preview: WeSort (1/${previewY}) - ${value.trim()}` : ""}`
      }
      slotProps={{ htmlInput: { maxLength: LABEL_MAX_TEXT + 8 } }}
    />
  );
}

function AvailabilityLine({ item, slices, orderable, inCart }: { item: MergedItem; slices: SliceState[]; orderable: number | null; inCart: number }) {
  const av = useMemo(() => {
    let loose = 0;
    let boxed = 0;
    let mixed = 0;
    let verified = true;
    for (const s of slices) {
      const it = s.items.find((x) => x.key === item.key);
      if (it) loose += Math.max(0, (s.availableItems[item.key] ?? it.count));
      if (!s.ordersOk) verified = false;
      boxed += netBoxedContents(item.key, s.shulkers, s.availableShulkers);
      mixed += mixedBoxedContents(item.key, s.shulkers);
    }
    return { loose, boxed, mixed, verified };
  }, [item, slices]);
  // Headline is cart-adjusted (what you can still add); breakdown is observed stock.
  const headline = orderable ?? av.loose + av.boxed;
  return (
    <Typography variant="body2" color="text.secondary">
      <Typography component="span" sx={{ fontWeight: 700, color: "text.primary" }}>{orderable === null ? "?" : grouped(headline)}</Typography> orderable
      <Typography component="span" variant="caption" sx={{ display: "block" }}>
        {grouped(av.loose)} loose{av.boxed > 0 ? ` · ${grouped(av.boxed)} in whole boxes` : ""}{av.mixed > 0 ? ` · ${grouped(av.mixed)} in mixed boxes` : ""}
        {!av.verified ? " · unverified" : ""}
        {inCart > 0 ? ` · ${grouped(inCart)} in draft` : ""}
      </Typography>
    </Typography>
  );
}

function ItemCard({ item, slices, mcVersion, onBuy, canOrder, orderable, inCart }: {
  item: MergedItem; slices: SliceState[]; mcVersion: string | null; onBuy: () => void; canOrder: boolean; orderable: number | null; inCart: number;
}) {
  const stale = slices.some((s) => s.online === false);
  const unknown = orderable === null;
  return (
    <Card sx={{ height: "100%", display: "flex", flexDirection: "column" }}>
      <CardContent sx={{ flexGrow: 1 }}>
        <Stack direction="row" spacing={1.5} sx={{ alignItems: "flex-start" }}>
          <ItemIcon id={item.id} size={48} label={item.name ?? undefined} mcVersion={mcVersion} />
          <Box sx={{ minWidth: 0 }}>
            <Typography variant="h4" noWrap title={item.name ?? item.id}>{item.name ?? item.id}</Typography>
            <Typography variant="caption" color="text.secondary" sx={{ fontFamily: "monospace", wordBreak: "break-all" }}>{item.id}</Typography>
          </Box>
        </Stack>
        <Box sx={{ mt: 1 }}>
          <AvailabilityLine item={item} slices={slices} orderable={orderable} inCart={inCart} />
        </Box>
        <Stack direction="row" spacing={1} sx={{ mt: 1, alignItems: "center" }}>
          {unknown && <Chip size="small" variant="outlined" label="Unverified" />}
          {!unknown && (orderable ?? 0) > 0 && !stale && inCart <= 0 && <Chip size="small" color="success" label="Orderable" />}
          {!unknown && (orderable ?? 0) > 0 && !stale && inCart > 0 && <Chip size="small" color="info" label="In draft" />}
          {stale && <Chip size="small" color="warning" variant="outlined" label="Stale — bot offline" />}
          {slices.some((s) => !s.ordersOk || !s.indexOk) && <Chip size="small" variant="outlined" label="Unverified" />}
          {!unknown && (orderable ?? 0) <= 0 && <Chip size="small" label="Empty" />}
        </Stack>
      </CardContent>
      <CardActions>
        <Button size="small" variant="contained" startIcon={<AddShoppingCartIcon />} onClick={onBuy} disabled={!canOrder || (orderable ?? 0) <= 0}>
          Add
        </Button>
      </CardActions>
    </Card>
  );
}

function InventoryRow({ item, slices, mcVersion, onBuy, canOrder, orderable, inCart }: {
  item: MergedItem; slices: SliceState[]; mcVersion: string | null; onBuy: () => void; canOrder: boolean; orderable: number | null; inCart: number;
}) {
  const av = useMemo(() => {
    let loose = 0;
    let boxed = 0;
    let mixed = 0;
    let verified = true;
    for (const s of slices) {
      const it = s.items.find((x) => x.key === item.key);
      if (it) loose += Math.max(0, (s.availableItems[item.key] ?? it.count));
      if (!s.ordersOk) verified = false;
      boxed += netBoxedContents(item.key, s.shulkers, s.availableShulkers);
      mixed += mixedBoxedContents(item.key, s.shulkers);
    }
    return { loose, boxed, mixed, verified };
  }, [item, slices]);
  const stale = slices.some((s) => s.online === false);
  return (
    <TableRow hover>
      <TableCell>
        <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
          <ItemIcon id={item.id} size={32} label={item.name ?? undefined} mcVersion={mcVersion} />
          <Box>
            <Typography variant="body2" sx={{ fontWeight: 600 }}>{item.name ?? item.id}</Typography>
            <Typography variant="caption" color="text.secondary" sx={{ fontFamily: "monospace" }}>{item.id}</Typography>
          </Box>
        </Stack>
      </TableCell>
      <TableCell align="right">{grouped(av.loose)}</TableCell>
      <TableCell align="right">{grouped(av.boxed)}</TableCell>
      <TableCell align="right">{grouped(av.mixed)}</TableCell>
      <TableCell align="right"><strong>{orderable === null ? "?" : grouped(orderable)}</strong></TableCell>
      <TableCell>
        <Stack direction="row" spacing={0.5} sx={{ alignItems: "center" }}>
          {stale && <Chip size="small" color="warning" variant="outlined" label="Stale" />}
          {!av.verified && <Chip size="small" variant="outlined" label="Unverified" />}
          {orderable !== null && orderable <= 0 && <Chip size="small" label="Empty" />}
          {orderable !== null && orderable > 0 && !stale && av.verified && inCart <= 0 && <Chip size="small" color="success" label="OK" />}
          {orderable !== null && orderable > 0 && !stale && av.verified && inCart > 0 && <Chip size="small" color="info" label="In draft" />}
        </Stack>
      </TableCell>
      <TableCell align="right">
        <Button size="small" variant="outlined" onClick={onBuy} disabled={!canOrder || (orderable ?? 0) <= 0}>Add</Button>
      </TableCell>
    </TableRow>
  );
}

function BuyDialog({ item, slices, mcVersion, canOrder, remaining, onClose, onAdd }: {
  item: MergedItem | null; slices: SliceState[]; mcVersion: string | null; canOrder: boolean;
  remaining: number;
  onClose: () => void; onAdd: (n: number) => void;
}) {
  // Max is cart-adjusted: never offer more than the draft can still absorb.
  const max = Math.max(1, remaining);
  const [n, setN] = useState(max);
  useEffect(() => { setN(max); }, [item?.key]); // eslint-disable-line react-hooks/exhaustive-deps
  if (!item) return null;
  return (
    <Dialog open={!!item} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{item.name ?? item.id}</DialogTitle>
      <DialogContent>
        <Stack direction="row" spacing={2} sx={{ mb: 2, alignItems: "center" }}>
          <ItemIcon id={item.id} size={64} label={item.name ?? undefined} mcVersion={mcVersion} />
          <Box>
            <Typography variant="body1"><strong>{grouped(remaining)}</strong> orderable across all stashes</Typography>
            <Typography variant="body2" color="text.secondary">
              {grouped(item.count)} observed loose · {grouped(item.boxed)} in whole boxes · {grouped(item.mixed)} in mixed boxes (never packed whole)
            </Typography>
            <Typography variant="caption" color="text.secondary">Stack size {item.maxStack} · one order is placed per stash.</Typography>
          </Box>
        </Stack>
        <TableContainer component={Paper} variant="outlined">
          <Table size="small" aria-label="Per-stash availability">
            <TableHead>
              <TableRow><TableCell>Stash</TableCell><TableCell align="right">Loose</TableCell><TableCell align="right">Boxes</TableCell><TableCell>Status</TableCell></TableRow>
            </TableHead>
            <TableBody>
              {slices.map((s) => (
                <TableRow key={s.stash}>
                  <TableCell sx={{ fontFamily: "monospace" }}>{s.stash}</TableCell>
                  <TableCell align="right">{grouped(Math.max(0, s.availableItems[item.key] ?? 0))}</TableCell>
                  <TableCell align="right">{grouped(netBoxedContents(item.key, s.shulkers, s.availableShulkers))}</TableCell>
                  <TableCell><PresenceChip online={s.online} label={s.online ? "bot online" : s.online === false ? "bot offline" : "unknown"} /></TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
        <TextField
          label="Quantity" type="number" value={n} size="small" sx={{ mt: 2, width: 160 }}
          slotProps={{ htmlInput: { min: 1, max } }}
          onChange={(e) => setN(Math.max(1, Math.min(max, Number(e.target.value) || 1)))}
        />
        {!canOrder && <Alert severity="warning" sx={{ mt: 1 }}>Your account can't place orders.</Alert>}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" onClick={() => onAdd(n)} disabled={!canOrder || remaining < 1}>Add to cart</Button>
      </DialogActions>
    </Dialog>
  );
}

function CartDrawer({ open, onClose, lines, slices, onCheckout }: {
  open: boolean; onClose: () => void; lines: CartLine[]; slices: SliceState[]; onCheckout: () => void;
}) {
  const cart = useCart();
  return (
    <Drawer anchor="right" open={open} onClose={onClose} sx={{ "& .MuiDrawer-paper": { width: { xs: "100%", sm: 420 } } }}>
      <Box sx={{ p: 2, display: "flex", flexDirection: "column", height: "100%" }}>
        <Typography variant="h3" gutterBottom>Order draft</Typography>
        {lines.length === 0 ? (
          <Typography color="text.secondary">The draft is empty. Add items from Inventory.</Typography>
        ) : (
          <>
            <Stack spacing={1} sx={{ flexGrow: 1, overflowY: "auto" }} id="cart-lines">
              {lines.map((l, i) => (
                <Paper key={`${l.type}:${l.key}:${l.stash}`} variant="outlined" sx={{ p: 1.5 }} className="cart-line">
                  <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
                    <Box sx={{ flexGrow: 1, minWidth: 0 }}>
                      <Typography variant="body2" noWrap sx={{ fontWeight: 600 }}>{l.type === "shulker" ? `Packed: ${l.key}` : l.key}</Typography>
                      <Typography variant="caption" color="text.secondary">{l.type === "shulker" ? "whole box" : "loose"} · {l.stash} · ×{grouped(l.count)}</Typography>
                    </Box>
                    <IconButton size="small" aria-label={`Remove ${l.key}`} onClick={() => cart.setLines(lines.filter((_, x) => x !== i))}>
                      <DeleteIcon fontSize="small" />
                    </IconButton>
                  </Stack>
                </Paper>
              ))}
            </Stack>
            <Divider sx={{ my: 1 }} />
            <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
              {lines.length} lines · {grouped(lines.reduce((a, l) => a + l.count, 0))} units · {new Set(lines.map((l) => l.stash)).size} stash(es) → one order each.
            </Typography>
            <Stack direction="row" spacing={1}>
              <Button variant="outlined" onClick={() => cart.clear()}>Clear</Button>
              <Button id="cart-place" variant="contained" fullWidth onClick={onCheckout}>Checkout</Button>
            </Stack>
          </>
        )}
      </Box>
    </Drawer>
  );
}

function CheckoutDialog(props: {
  open: boolean; onClose: () => void; step: number; setStep: (n: number) => void;
  lines: CartLine[]; groups: CheckoutGroup[]; placing: boolean; onPlace: () => void;
  purpose: "SELF" | "GUILD"; setPurpose: (v: "SELF" | "GUILD") => void;
  recipient: string; setRecipient: (v: string) => void;
  comment: string; setComment: (v: string) => void;
  labelMode: "none" | "all" | "each"; setLabelMode: (v: "none" | "all" | "each") => void;
  labelText: string; setLabelText: (v: string) => void;
  labelTexts: string[]; setLabelTexts: (v: string[]) => void;
  onRetryExact: (g: CheckoutGroup) => void; onDiscard: (hash: string) => void;
  uncertainCount: number; confirmedCount: number; canOrder: boolean; slices: SliceState[];
}) {
  const { step, setStep } = props;
  const perStash = useMemo(() => {
    const m = new Map<string, CartLine[]>();
    for (const l of props.lines) {
      if (!m.has(l.stash)) m.set(l.stash, []);
      m.get(l.stash)!.push(l);
    }
    return [...m.entries()];
  }, [props.lines]);
  // Estimated boxes for the W-064 preview: item lines via 27-slot bins (64-stack
  // estimate), packed lines 1:1. The server locks the final Y at commit.
  const labelInvalid =
    (props.labelMode === "all" && !!labelTextError(props.labelText)) ||
    (props.labelMode === "each" && props.labelTexts.some((t) => t.trim() !== "" && !!labelTextError(t)));
  const estimatedBoxes = Math.max(
    1,
    ...perStash.map(([, ls]) =>
      shulkersNeeded(
        ls.map((l) => ({ type: l.type, key: l.key, count: l.count })),
        () => 64,
        (k) => ls.some((l) => l.type === "shulker" && l.key === k),
      ),
    ),
  );

  return (
    <Dialog open={props.open} onClose={props.onClose} maxWidth="md" fullWidth>
      <DialogTitle>Checkout — {perStash.length} stash order{perStash.length === 1 ? "" : "s"}</DialogTitle>
      <DialogContent>
        <Stepper activeStep={step} sx={{ mb: 3 }}>
          {["Cart", "Details", "Review", "Confirmation"].map((l) => (
            <Step key={l}><StepLabel>{l}</StepLabel></Step>
          ))}
        </Stepper>

        {step === 0 && (
          <Stack spacing={1}>
            {perStash.map(([stash, ls]) => (
              <Paper key={stash} variant="outlined" sx={{ p: 1.5 }}>
                <Typography variant="subtitle2" sx={{ fontFamily: "monospace" }}>{stash}</Typography>
                {ls.map((l) => (
                  <Typography key={`${l.type}:${l.key}`} variant="body2" color="text.secondary">
                    ×{grouped(l.count)} {l.type === "shulker" ? `packed ${l.key}` : l.key}
                  </Typography>
                ))}
              </Paper>
            ))}
          </Stack>
        )}

        {step === 1 && (
          <Stack spacing={2}>
            <FormControl size="small" fullWidth>
              <InputLabel id="order-for-label">Order for</InputLabel>
              <Select labelId="order-for-label" id="order-for" value={props.purpose} label="Order for" onChange={(e) => props.setPurpose(e.target.value as "SELF" | "GUILD")}>
                <MenuItem value="SELF">Myself</MenuItem>
                <MenuItem value="GUILD">The guild</MenuItem>
              </Select>
            </FormControl>
            <TextField label="Recipient (optional)" value={props.recipient} onChange={(e) => props.setRecipient(e.target.value)} size="small" slotProps={{ htmlInput: { maxLength: 16 } }} helperText="Letters, digits and underscore, at most 16 characters" />
            <TextField label="Comment (optional)" value={props.comment} onChange={(e) => props.setComment(e.target.value)} size="small" multiline rows={2} slotProps={{ htmlInput: { maxLength: 500 } }} />
            <FormControl size="small" fullWidth>
              <InputLabel id="box-labels-label">Box labels</InputLabel>
              <Select labelId="box-labels-label" id="box-labels" value={props.labelMode} label="Box labels" onChange={(e) => props.setLabelMode(e.target.value as typeof props.labelMode)}>
                <MenuItem value="none">Default (“Beställning”)</MenuItem>
                <MenuItem value="all">Same text on every box</MenuItem>
                <MenuItem value="each">Different text per box</MenuItem>
              </Select>
            </FormControl>
            {props.labelMode !== "none" && (
              <Typography variant="body2" color="text.secondary">
                The server names every box <Typography component="span" sx={{ fontFamily: "monospace" }}>WeSort (X/Y) - text</Typography> from
                the locked packing plan (preview Y ≈ {estimatedBoxes} below; final numbering locked server-side).
              </Typography>
            )}
            {props.labelMode === "all" && (
              <LabelTextField
                label="Text for every box"
                value={props.labelText}
                onChange={props.setLabelText}
                previewY={estimatedBoxes}
              />
            )}
            {props.labelMode === "each" && (
              <Stack spacing={1}>
                <Typography variant="body2" color="text.secondary">One text per planned box (about {estimatedBoxes} box{estimatedBoxes === 1 ? "" : "es"}); blanks fall back to “Beställning”.</Typography>
                {props.labelTexts.map((nm, i) => (
                  <LabelTextField
                    key={i}
                    label={`Box ${i + 1} text`}
                    previewY={estimatedBoxes}
                    value={nm}
                    onChange={(v) => {
                      const next = [...props.labelTexts];
                      next[i] = v;
                      props.setLabelTexts(next);
                    }}
                  />
                ))}
                {props.labelTexts.length < Math.min(54, Math.max(estimatedBoxes, 1)) && (
                  <Button variant="outlined" size="small" onClick={() => props.setLabelTexts([...props.labelTexts, ""])}>Add box row</Button>
                )}
              </Stack>
            )}
            {!props.canOrder && <Alert severity="warning">Your account can't place orders.</Alert>}
          </Stack>
        )}

        {step === 2 && (
          <Stack spacing={1} id="order-review">
            {perStash.map(([stash, ls]) => (
              <Paper key={stash} variant="outlined" sx={{ p: 1.5 }}>
                <Typography variant="subtitle2" sx={{ fontFamily: "monospace" }}>{stash}</Typography>
                {ls.map((l) => (
                  <Typography key={`${l.type}:${l.key}`} variant="body2" color="text.secondary">
                    ×{grouped(l.count)} {l.type === "shulker" ? `packed ${l.key}` : l.key}
                  </Typography>
                ))}
              </Paper>
            ))}
            <Typography variant="body2" color="text.secondary">
              For {props.purpose === "GUILD" ? "the guild" : "myself"}
              {props.recipient.trim() ? ` · recipient ${props.recipient.trim()}` : ""}
              {props.comment.trim() ? ` · “${props.comment.trim()}”` : ""}
            </Typography>
            {perStash.length > 1 && (
              <Alert severity="info">
                {perStash.length} separate backend orders (one per stash) — not one transaction.
                If a stash fails, the others still confirm; retry the failed group with its same key.
              </Alert>
            )}
          </Stack>
        )}

        {step === 3 && (
          <Stack spacing={1}>
            {props.groups.length === 0 && <Alert severity="info">Nothing submitted yet.</Alert>}
            {props.groups.map((g) => {
              const st = statusOf(g);
              return (
                <Paper key={`${g.body.stash}:${g.hash}`} variant="outlined" sx={{ p: 1.5 }}>
                  <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
                    <Box sx={{ flexGrow: 1 }}>
                      <Typography variant="subtitle2" sx={{ fontFamily: "monospace" }}>{g.body.stash}</Typography>
                      <Typography variant="body2" color="text.secondary">
                        {g.body.lines.map((l) => `×${grouped(l.count)} ${l.type === "shulker" ? `packed ${l.key}` : l.key}`).join(" · ")}
                      </Typography>
                      {g.status === "rejected" && g.error && (
                        <Typography variant="body2" color="error">{g.error}</Typography>
                      )}
                      {g.status === "uncertain" && (
                        <Typography variant="body2" color="warning.main">
                          The server may or may not have created this order. Retry reuses the exact same request and idempotency key.
                        </Typography>
                      )}
                    </Box>
                    <Chip size="small" color={st.color} label={st.label} icon={g.status === "confirmed" ? <CheckCircleIcon /> : g.status === "rejected" ? <ErrorIcon /> : g.status === "uncertain" ? <HelpCenterIcon /> : undefined} />
                  </Stack>
                  {(g.status === "uncertain" || g.status === "rejected") && (
                    <Stack direction="row" spacing={1} sx={{ mt: 1 }}>
                      <Button size="small" variant="outlined" onClick={() => props.onRetryExact(g)}>
                        {g.status === "uncertain" ? "Retry safely" : "Retry"}
                      </Button>
                      <Button size="small" onClick={() => props.onDiscard(g.hash)}>Discard</Button>
                    </Stack>
                  )}
                </Paper>
              );
            })}
          </Stack>
        )}
      </DialogContent>
      <DialogActions>
        {step === 0 && <><Button onClick={props.onClose}>Close</Button><Button variant="contained" onClick={() => setStep(1)} disabled={!props.lines.length}>Continue</Button></>}
        {step === 1 && <><Button onClick={() => setStep(0)}>Back</Button><Button variant="contained" onClick={() => setStep(2)} disabled={!props.lines.length}>Review</Button></>}
        {step === 2 && (
          <>
            <Button onClick={() => setStep(1)}>Back</Button>
            <Button variant="contained" onClick={props.onPlace} disabled={props.placing || !props.lines.length || !props.canOrder || labelInvalid}>
              {props.placing ? "Placing…" : "Place orders"}
            </Button>
          </>
        )}
        {step === 3 && <><Button onClick={props.onClose}>Done</Button><Button onClick={() => setStep(0)} disabled={!props.lines.length}>Back to draft</Button></>}
      </DialogActions>
    </Dialog>
  );
}
