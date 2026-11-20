import { useEffect, useState } from "react";
import { api } from "../api/client";
import { useAuth, useBotActivity, useBotSettings, useBots, useGuild, usePermissions, useQuotaMe, useServer, useStashDefs, useStashes } from "../api/queries";
import { AuthUserSchema } from "../api/types";
import { OrderRow, statusVariant } from "./shop";
import { Badge } from "../components/ui/badge";
import { Button } from "../components/ui/button";
import { Card, CardContent, CardHeader } from "../components/ui/card";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "../components/ui/dialog";
import { Disclaimer } from "../components/ui/feedback";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";
import { Separator } from "../components/ui/tabs";
import { getRouteApi } from "@tanstack/react-router";
import { api as apiClient } from "../api/client";

const loginRoute = getRouteApi("/login");
const ordersRoute = getRouteApi("/orders");

export function LoginPage() {
  const auth = useAuth();
  const search = loginRoute.useSearch();
  const [name, setName] = useState("");
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState("");

  const setupNeeded = auth.data?.setupNeeded === true;
  const minLength = auth.data?.minLength ?? 12;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy("login");
    setError("");
    try {
      // Java AuthRoutes.login reads `username` (blank/absent = owner), not `name`.
      await api("/api/v1/auth/login", { method: "POST", body: name.trim() ? { username: name.trim(), password } : { password } });
      await auth.refetch();
      window.location.assign(search.redirect || "/shop");
    } catch (err: any) {
      setError(err?.message ?? "Login failed.");
    } finally {
      setBusy("");
    }
  }

  async function submitSetup(e: React.FormEvent) {
    e.preventDefault();
    setBusy("setup");
    setError("");
    try {
      await api("/api/v1/auth/setup", { method: "POST", body: { code, password } });
      await auth.refetch();
      window.location.assign(search.redirect || "/shop");
    } catch (err: any) {
      setError(err?.message ?? "Setup failed. Request a code with the local bootstrap command.");
    } finally {
      setBusy("");
    }
  }

  return (
    <div className="mx-auto mt-10 max-w-md">
      <div className="mb-5 text-center">
        <img src="/wesort-lockup.svg" alt="WeSort" className="auth-logo mx-auto mb-3 h-14" />
        <p className="-mt-1 text-sm text-muted-foreground">Guild stash &amp; order manager</p>
      </div>
      <Card>
        <div id="auth-screen" className="p-5">
          <h1 className="mb-1 text-xl font-bold">{setupNeeded ? "First-run setup" : "Welcome back"}</h1>
          <p className="mb-4 text-sm text-muted-foreground">{setupNeeded ? "Create the owner account to get started." : "Log in to shop the stash."}</p>
          {setupNeeded ? (
            <form onSubmit={submitSetup} className="flex flex-col gap-3">
              <p className="rounded-lg bg-muted p-2 text-sm text-muted-foreground">No owner password is set yet. Enter the setup code from the local bootstrap command and choose a password (min {minLength} chars).</p>
              <div className="flex flex-col gap-1">
                <Label htmlFor="auth-code">Setup code</Label>
                <Input id="auth-code" value={code} onChange={(e) => setCode(e.target.value)} autoComplete="off" required />
              </div>
              <div className="flex flex-col gap-1">
                <Label htmlFor="auth-password">Owner password</Label>
                <Input id="auth-password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" required />
              </div>
              {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
              <Button id="auth-submit" type="submit" disabled={busy !== ""}>{busy === "setup" ? "Setting up…" : "Create owner"}</Button>
            </form>
          ) : (
            <form onSubmit={submit} className="flex flex-col gap-3">
              <div className="flex flex-col gap-1">
                <Label htmlFor="auth-username">Account name</Label>
                <Input id="auth-username" value={name} onChange={(e) => setName(e.target.value)} autoComplete="username" placeholder="Blank for owner" />
              </div>
              <div className="flex flex-col gap-1">
                <Label htmlFor="auth-password">Password</Label>
                <Input id="auth-password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required />
              </div>
              {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
              <Button id="auth-submit" type="submit" disabled={busy !== ""}>{busy === "login" ? "Logging in…" : "Log in"}</Button>
            </form>
          )}
          <Disclaimer id="minecraft-disclaimer-login" />
        </div>
      </Card>
    </div>
  );
}

export function OrdersPage() {
  const auth = useAuth();
  const perms = usePermissions(auth.data);
  const search = ordersRoute.useSearch();
  const navigate = ordersRoute.useNavigate();
  const stashes = useStashes(!!auth.data?.loggedIn && perms.can("view"));
  const [detail, setDetail] = useState<any | null>(null);

  const stashList = stashes.data ?? [];
  const q = search.q ?? "";

  return (
    <div className="mx-auto max-w-4xl">
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Orders</h1>
          <p className="text-sm text-muted-foreground">All stashes{stashList.length ? ` (${stashList.length})` : ""} — members only see their own.</p>
        </div>
        <Input aria-label="Filter orders" placeholder="Filter by id, status or item" className="ml-auto sm:w-64" value={q} onChange={(e) => navigate({ search: (p) => ({ ...p, q: e.target.value }) })} />
      </div>
      {stashList.length ? (
        <OrderHistoryFull stashes={stashList} filter={q} onOpen={setDetail} ownOnly={!perms.can("ordersAll")} userId={auth.data?.user?.id} />
      ) : (
        <Card className="p-8 text-center text-sm text-muted-foreground">No stash available.</Card>
      )}
      {perms.can("ordersAll") && stashList.length > 0 && <OrderAdmins stashes={stashList} />}
      {perms.can("ordersAll") && <OrderAudit />}

      <Dialog open={detail !== null} onOpenChange={(o) => { if (!o) setDetail(null); }}>
        <DialogContent aria-label="Order details" className="max-w-lg">
          <DialogHeader>
            <DialogTitle className="font-mono text-base">{detail?.id}</DialogTitle>
          </DialogHeader>
          {detail && <OrderDetails order={detail} />}
        </DialogContent>
      </Dialog>
    </div>
  );
}

function useOrdersFull(stashes: { stash: string }[], enabled: boolean) {
  const [data, setData] = useState<{ orders: any[] } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  useEffect(() => {
    if (!enabled || !stashes.length) return;
    let cancelled = false;
    setLoading(true);
    const load = (quiet: boolean) =>
      Promise.all(
        stashes.map((s) =>
          apiClient<{ orders: any[] }>(`/api/v1/orders?stash=${encodeURIComponent(s.stash)}`)
            .then((d) => (d.orders ?? []).map((o) => ({ ...o, stashName: s.stash })))
            .catch(() => [] as any[]),
        ),
      ).then((parts) => {
        if (cancelled) return;
        const orders = parts.flat().sort((a, b) => (b.createdAt ?? 0) - (a.createdAt ?? 0));
        setData({ orders });
        if (!quiet) setError(null);
      }).catch((e: any) => {
        if (!cancelled && !quiet) setError(e?.message ?? "Could not load orders.");
      }).finally(() => {
        if (!cancelled) setLoading(false);
      });
    void load(false);
    const t = window.setInterval(() => { void load(true); }, 15000);
    return () => { cancelled = true; window.clearInterval(t); };
  }, [enabled, JSON.stringify(stashes.map((s) => s.stash))]);
  return { data, error, loading };
}

export function OrderHistoryFull({ stashes, filter, onOpen, ownOnly, userId }: { stashes: { stash: string }[]; filter: string; onOpen: (o: any) => void; ownOnly?: boolean; userId?: string }) {
  const { data, error, loading } = useOrdersFull(stashes, true);
  const mine = (data?.orders ?? []).filter((o: any) => !ownOnly || !o.requestedBy || o.requestedBy === userId);
  const list = mine.filter(
    (o) => !filter || o.id?.includes(filter) || o.status?.includes(filter) || (o.lines ?? []).some((l: any) => l.key?.includes(filter)),
  );
  if (loading && !data) return <Card className="p-8 text-center text-sm text-muted-foreground">Loading orders…</Card>;
  if (error && !data) return <Card className="p-8 text-center text-sm text-destructive" role="alert">{error}</Card>;
  if (!list.length)
    return (
      <Card className="p-8 text-center">
        <p className="font-semibold">No orders yet</p>
        <p className="mt-1 text-sm text-muted-foreground">Placed orders across all stashes will show up here, including queued offline orders.</p>
      </Card>
    );
  return (
    <div className="flex flex-col gap-2">
      <ul id="order-list" className="flex flex-col gap-2">
        {list.slice(0, 100).map((o) => (
          <li key={`${o.stashName} ${o.id}`}>
            <button type="button" onClick={() => onOpen(o)} className="block w-full text-left">
              <OrderRow
                orderId={o.id}
                status={o.status}
                lines={(o.lines ?? []).map((l: any) => `${l.type === "shulker" ? "📦" : ""}${l.key} ×${l.count}`).join(" · ")}
                message={o.message ?? progressText(o)}
                stash={`${o.stashName ?? o.stash}`}
              />
            </button>
          </li>
        ))}
      </ul>
      {error && <p role="alert" className="text-sm text-destructive">Refresh failed — showing the last list.</p>}
    </div>
  );
}

function progressText(o: any): string | null {
  if (o.status === "pending") return o.approvalState === "PENDING" ? "Waiting for approval" : "Queued — waiting for the bot";
  if (o.status === "accepted" || o.status === "in_progress") return o.phase ? `In progress: ${o.phase}` : "Bot is working on it";
  if (o.status === "packed") return "Packed — ready for pickup";
  if (o.status === "delivered") return "Delivered";
  if (o.status === "needs_reconciliation") return "Needs reconciliation";
  return null;
}

export function OrderDetails({ order: o }: { order: any }) {
  const auth = useAuth();
  const perms = usePermissions(auth.data);
  const [msg, setMsg] = useState("");
  const [busy, setBusy] = useState(false);
  async function act(path: string, body?: unknown) {
    setBusy(true);
    setMsg("");
    try {
      await api(`/api/v1/orders/${o.id}/${path}`, { method: "POST", body: body ?? {} });
      setMsg("Done.");
    } catch (e: any) {
      setMsg(e?.message ?? "Action failed.");
    } finally {
      setBusy(false);
    }
  }
  const mine = !o.requestedBy || o.requestedBy === auth.data?.user?.id;
  const canChange = perms.can("ordersAll") || (perms.can("order") && mine);
  return (
    <div className="flex flex-col gap-3 text-sm">
      <div className="flex items-center gap-2">
        <Badge variant={statusVariant(o.status ?? "pending")}>{String(o.status ?? "pending").replace(/_/g, " ")}</Badge>
        {o.purpose && <Badge variant="secondary">{o.purpose}</Badge>}
        {o.deliveryMode && <Badge variant="outline">{o.deliveryMode}</Badge>}
      </div>
      <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1">
        <dt className="text-muted-foreground">Stash</dt><dd className="font-mono text-xs">{o.stash}</dd>
        <dt className="text-muted-foreground">Placed</dt><dd>{o.createdAt ? new Date(o.createdAt).toLocaleString() : "—"}{o.requestedBy ? ` · ${o.placedBy?.name ?? o.requestedBy}` : ""}</dd>
        {o.recipient && <><dt className="text-muted-foreground">Recipient</dt><dd>{o.recipient}</dd></>}
        {o.comment && <><dt className="text-muted-foreground">Comment</dt><dd>{o.comment}</dd></>}
        {o.packageTotal ? <><dt className="text-muted-foreground">Shulkers</dt><dd>{o.packageTotal}</dd></> : null}
        {o.phase && <><dt className="text-muted-foreground">Phase</dt><dd>{o.phase}</dd></>}
        {(o.message || o.result) && <><dt className="text-muted-foreground">Message</dt><dd>{o.message ?? JSON.stringify(o.result)}</dd></>}
      </dl>
      <div>
        <p className="mb-1 font-semibold">Lines</p>
        <ul className="flex flex-col gap-1">
          {(o.lines ?? []).map((l: any, i: number) => (
            <li key={i} className="rounded-lg border border-border/70 px-2 py-1 font-mono text-xs">
              {l.type}:{l.key} ×{l.count}
            </li>
          ))}
        </ul>
      </div>
      {canChange && ["pending", "accepted", "in_progress"].includes(o.status) && (
        <div className="flex flex-wrap gap-2">
          <Button variant="secondary" size="sm" disabled={busy} onClick={() => act("cancel")}>Request cancel</Button>
          <Button variant="destructive" size="sm" disabled={busy} onClick={() => act("force-cancel")}>Force cancel</Button>
          {(o.status === "failed" || o.status === "cancelled") && (
            <Button variant="secondary" size="sm" disabled={busy} onClick={() => act("retry")}>Retry as new order</Button>
          )}
        </div>
      )}
      {msg && <p role="status" className="text-sm">{msg}</p>}
    </div>
  );
}

function OrderAdmins({ stashes }: { stashes: { stash: string }[] }) {
  const [msg, setMsg] = useState("");
  async function clear(stash: string) {
    setMsg("");
    try {
      const res = await api<{ removed: number }>("/api/v1/orders/clear", { method: "POST", body: { stash } });
      setMsg(`Cleared ${res.removed} finished orders (${stash}).`);
    } catch (e: any) {
      setMsg(e?.message ?? "Clear failed.");
    }
  }
  return (
    <Card className="mt-4">
      <CardHeader className="pb-2"><h2 className="font-bold">Order administration</h2></CardHeader>
      <CardContent className="flex flex-col gap-1.5">
        {stashes.map((s) => (
          <div key={s.stash} className="flex items-center gap-2 text-sm">
            <span className="min-w-0 flex-1 truncate font-mono text-xs">{s.stash}</span>
            <Button variant="secondary" size="sm" onClick={() => clear(s.stash)}>Clear finished</Button>
          </div>
        ))}
        {msg && <p role="status" className="text-sm">{msg}</p>}
      </CardContent>
    </Card>
  );
}

function OrderAudit() {
  const [person, setPerson] = useState("");
  const [item, setItem] = useState("");
  const [results, setResults] = useState<any[]>([]);
  const [status, setStatus] = useState("");
  async function search() {
    setStatus("Searching…");
    try {
      const res = await api<{ orders: any[] }>(
        `/api/v1/audit/orders?person=${encodeURIComponent(person)}&item=${encodeURIComponent(item)}&limit=100`,
      );
      setResults(res.orders ?? []);
      setStatus(res.orders?.length ? `${res.orders.length} orders` : "No matching orders.");
    } catch (e: any) {
      setStatus(e?.message ?? "Audit search failed.");
    }
  }
  return (
    <Card className="mt-4">
      <CardHeader className="pb-2"><h2 className="font-bold">Order audit</h2></CardHeader>
      <CardContent>
        <div id="order-audit" className="flex flex-col gap-2">
          <div className="flex flex-col gap-2 sm:flex-row">
            <div className="flex flex-1 flex-col gap-1"><Label>Person<Input aria-label="Person" value={person} onChange={(e) => setPerson(e.target.value)} /></Label></div>
            <div className="flex flex-1 flex-col gap-1"><Label>Item key<Input aria-label="Item key" value={item} onChange={(e) => setItem(e.target.value)} /></Label></div>
          </div>
          <div><Button variant="secondary" size="sm" onClick={search}>Search audit</Button></div>
          <p id="order-audit-status" role="status" className="text-sm text-muted-foreground">{status}</p>
          <div id="order-audit-results" className="flex flex-col gap-1.5">
            {results.map((o: any) => (
              <details key={o.id} className="rounded-lg border border-border p-2 text-sm">
                <summary className="cursor-pointer font-mono text-xs">{o.id} · {o.placedBy?.name ?? o.requestedBy ?? ""}</summary>
                <ul className="mt-1 flex flex-col gap-0.5">
                  {(o.events ?? o.history ?? []).slice(0, 30).map((e: any, i: number) => (
                    <li key={i} className="font-mono text-[11px] text-muted-foreground">
                      {e.at ? new Date(e.at).toLocaleString() : ""} · {e.actor ?? ""} · {e.action ?? e.state ?? JSON.stringify(e)}
                    </li>
                  ))}
                </ul>
              </details>
            ))}
          </div>
        </div>
      </CardContent>
    </Card>
  );
}

export function BotsPage() {
  const auth = useAuth();
  const perms = usePermissions(auth.data);
  const bots = useBots(!!auth.data?.loggedIn && perms.can("activity"));
  const [selected, setSelected] = useState<string | null>(null);
  const sel = bots.data?.find((b) => b.botId === selected) ?? bots.data?.[0] ?? null;

  useEffect(() => {
    if (!selected && bots.data?.length) setSelected(bots.data[0].botId);
  }, [bots.data, selected]);

  return (
    <div className="mx-auto grid max-w-5xl gap-4 md:grid-cols-[280px_1fr]">
      <Card>
        <CardHeader className="pb-2"><h1 className="text-lg font-bold">Bots</h1></CardHeader>
        <CardContent className="flex flex-col gap-1">
          {bots.isLoading && <p className="text-sm text-muted-foreground">Loading bots…</p>}
          {bots.isError && <p role="alert" className="text-sm text-destructive">Could not load bots.</p>}
          {(bots.data ?? []).filter((b) => perms.seesBot(b.botId)).map((b) => (
            <button
              key={b.botId}
              type="button"
              onClick={() => setSelected(b.botId)}
              aria-pressed={sel?.botId === b.botId}
              className={`flex items-center gap-2 rounded-lg border px-2.5 py-2 text-left text-sm transition-colors ${sel?.botId === b.botId ? "border-primary bg-primary/10" : "border-border/60 hover:bg-muted"}`}
            >
              <span className={`h-2.5 w-2.5 shrink-0 rounded-full ${b.online ? "bg-emerald-500" : "bg-red-500"}`} aria-hidden />
              <span className="min-w-0">
                <span className="block truncate font-semibold">{b.player ?? b.botId}</span>
                <span className="block truncate text-xs text-muted-foreground">{b.online ? "online" : "offline"}{b.summary ? ` · ${b.summary}` : ""}</span>
              </span>
            </button>
          ))}
          {bots.data && !bots.data.length && <p className="py-4 text-center text-sm text-muted-foreground">No bots reported yet.</p>}
        </CardContent>
      </Card>
      {sel ? <BotDetails botId={sel.botId} canPosition={perms.can("positions")} /> : (
        <Card className="p-8 text-center text-sm text-muted-foreground">Select a bot to inspect settings and activity.</Card>
      )}
    </div>
  );
}

function BotDetails({ botId, canPosition }: { botId: string; canPosition: boolean }) {
  const auth = useAuth();
  const perms = usePermissions(auth.data);
  const activity = useBotActivity(botId, !!auth.data?.loggedIn && perms.can("activity"));
  const settings = useBotSettings(botId, !!auth.data?.loggedIn && canPosition);
  const [changes, setChanges] = useState("");
  const [msg, setMsg] = useState("");
  const bot = useBots(!!auth.data?.loggedIn && perms.can("activity")).data?.find((b) => b.botId === botId);

  async function saveSettings() {
    setMsg("");
    let parsed: unknown;
    try {
      parsed = JSON.parse(changes || "[]");
    } catch {
      setMsg("Changes must be JSON: a list of {path, value, expect}.");
      return;
    }
    if (!Array.isArray(parsed) || !parsed.length) {
      setMsg("Changes must be a non-empty list of {path, value, expect}.");
      return;
    }
    try {
      await api(`/api/v1/bots/${encodeURIComponent(botId)}/settings`, { method: "POST", body: { scope: "plugin", changes: parsed } });
      setMsg("Settings sent to the bot.");
    } catch (e: any) {
      setMsg(e?.message ?? "Could not update settings.");
    }
  }

  return (
    <div className="flex min-w-0 flex-col gap-4">
      <Card>
        <CardHeader className="pb-2">
          <h2 className="text-base font-bold">{bot?.player ?? botId}</h2>
          <p className="font-mono text-xs text-muted-foreground">{botId}</p>
        </CardHeader>
        <CardContent className="flex flex-wrap gap-1.5 text-xs">
          <Badge variant={bot?.online ? "success" : "destructive"}>{bot?.online ? "online" : "offline"}</Badge>
          {(bot?.caps ?? []).map((c) => <Badge key={c} variant="secondary">{c}</Badge>)}
          {(bot?.scopes ?? []).map((s) => <Badge key={s} variant="outline">{s}</Badge>)}
        </CardContent>
      </Card>
      <Card>
        <CardHeader className="pb-2"><h2 className="font-bold">Activity</h2></CardHeader>
        <CardContent>
          {activity.isLoading && <p className="text-sm text-muted-foreground">Loading activity…</p>}
          {activity.isError && <p role="alert" className="text-sm text-destructive">Activity unavailable.</p>}
          {activity.data && <BotActivityView activity={activity.data} />}
        </CardContent>
      </Card>
      {canPosition && (
        <Card>
          <CardHeader className="pb-2"><h2 className="font-bold">Settings <span className="font-mono text-xs text-muted-foreground">scope=plugin</span></h2></CardHeader>
          <CardContent className="flex flex-col gap-2">
            {settings.isLoading && <p className="text-sm text-muted-foreground">Requesting schema from the bot…</p>}
            {settings.isError && <p className="text-sm text-muted-foreground">No settings from this bot yet (or not permitted).</p>}
            {settings.data && (
              <>
                <details className="rounded-lg border border-border p-2 text-xs">
                  <summary className="cursor-pointer">Schema &amp; current values</summary>
                  <pre className="mt-1 max-h-64 overflow-auto font-mono text-[11px]">{JSON.stringify({ schema: settings.data.schema, values: settings.data.values }, null, 1)}</pre>
                </details>
                <Label htmlFor="bot-changes">Changes (JSON list of {"{path, value, expect}"})</Label>
                <Input id="bot-changes" value={changes} onChange={(e) => setChanges(e.target.value)} placeholder='[{"path":"x","value":true}]' className="font-mono" />
                <div><Button variant="secondary" size="sm" onClick={saveSettings}>Send to bot</Button></div>
              </>
            )}
            {msg && <p role="status" className="text-sm">{msg}</p>}
          </CardContent>
        </Card>
      )}
    </div>
  );
}

export function BotActivityView({ activity }: { activity: { activity?: unknown; receivedAt?: number | null; events: unknown[]; online: boolean } }) {
  const events = activity.events ?? [];
  return (
    <div className="flex flex-col gap-2">
      {activity.activity != null && (
        <pre className="max-h-40 overflow-auto rounded-lg bg-muted p-2 font-mono text-[11px]">{JSON.stringify(activity.activity, null, 1)}</pre>
      )}
      <ul className="flex max-h-96 flex-col gap-1 overflow-auto text-sm">
        {events.slice(0, 100).map((e: any, i: number) => (
          <li key={i} className="rounded-lg border border-border/60 px-2 py-1">
            <span className="font-mono text-[11px] text-muted-foreground">{e.at ? new Date(e.at).toLocaleString() : ""} </span>
            {e.message ?? e.text ?? JSON.stringify(e)}
          </li>
        ))}
      </ul>
      {!events.length && activity.activity == null && <p className="text-sm text-muted-foreground">No activity yet.</p>}
    </div>
  );
}

export function ActivityPage() {
  const auth = useAuth();
  const perms = usePermissions(auth.data);
  const bots = useBots(!!auth.data?.loggedIn && perms.can("activity"));
  const [botId, setBotId] = useState<string>("");
  const current = botId || bots.data?.[0]?.botId || "";
  const activity = useBotActivity(current || null, !!auth.data?.loggedIn && !!current && perms.can("activity"));

  return (
    <div className="mx-auto max-w-3xl">
      <div className="mb-3 flex items-center gap-2">
        <h1 className="text-2xl font-bold tracking-tight">Activity</h1>
        <select
          aria-label="Bot"
          className="ml-auto flex h-9 rounded-lg border border-input bg-background px-2 text-sm"
          value={current}
          onChange={(e) => setBotId(e.target.value)}
        >
          {(bots.data ?? []).filter((b) => perms.seesBot(b.botId)).map((b) => (
            <option key={b.botId} value={b.botId}>{b.player ?? b.botId}{b.online ? "" : " (offline)"}</option>
          ))}
        </select>
      </div>
      <Card>
        <CardContent className="pt-5">
          {activity.isLoading && <p className="text-sm text-muted-foreground">Loading activity…</p>}
          {activity.isError && <p role="alert" className="text-sm text-destructive">Activity unavailable for this bot.</p>}
          {activity.data && <BotActivityView activity={activity.data} />}
        </CardContent>
      </Card>
    </div>
  );
}

export function SettingsPage() {
  const auth = useAuth();
  const perms = usePermissions(auth.data);
  const guild = useGuild(!!auth.data?.loggedIn);
  const quotaMe = useQuotaMe(!!auth.data?.loggedIn && perms.can("view"));
  const [name, setName] = useState("");
  const [msg, setMsg] = useState("");

  async function save(e: React.FormEvent) {
    e.preventDefault();
    try {
      await api("/api/v1/guild", { method: "POST", body: { name, expectedRevision: guild.data?.revision ?? 1 } });
      setMsg("Guild saved.");
      await guild.refetch();
    } catch (err: any) {
      setMsg(err?.message ?? "Save failed.");
    }
  }

  return (
    <div className="mx-auto flex max-w-2xl flex-col gap-4">
      <Card>
        <CardHeader className="pb-2"><h1 className="text-xl font-bold">Settings</h1></CardHeader>
        <CardContent className="flex flex-col gap-2">
          <p className="text-sm text-muted-foreground">Guild: {guild.data?.name || "—"}</p>
          {perms.owner ? (
            <form onSubmit={save} className="flex gap-2">
              <Input aria-label="Guild name" className="flex-1" value={name} onChange={(e) => setName(e.target.value)} placeholder={guild.data?.name ?? "Guild name"} />
              <Button type="submit" size="sm">Save guild</Button>
            </form>
          ) : (
            <p className="text-sm text-muted-foreground">Only the owner can change guild settings.</p>
          )}
          {msg && <p role="status" className="text-sm">{msg}</p>}
          {quotaMe.data && Object.keys(quotaMe.data.usage ?? {}).length > 0 && (
            <>
              <Separator className="my-1" />
              <h2 className="text-sm font-bold">Your withdrawal usage</h2>
              <ul className="flex flex-col gap-1 text-sm">
                {Object.entries(quotaMe.data.usage).map(([k, v]) => (
                  <li key={k} className="font-mono text-xs">{k}: {JSON.stringify(v)}</li>
                ))}
              </ul>
            </>
          )}
        </CardContent>
      </Card>
      <Card>
        <CardHeader className="pb-2"><h2 className="font-bold">Security</h2></CardHeader>
        <CardContent className="flex flex-col gap-2">
          <PasswordChange />
          <AdminUnlock />
        </CardContent>
      </Card>
      {perms.owner && (
        <Card>
          <CardHeader className="pb-2"><h2 className="font-bold">User administration</h2></CardHeader>
          <CardContent><UsersAdmin /></CardContent>
        </Card>
      )}
      {perms.can("link") && <LinkPanel />}
      <ServerPanel />
      {perms.owner && <StashDefsPanel />}
    </div>
  );
}

function ServerPanel() {
  const auth = useAuth();
  const perms = usePermissions(auth.data);
  const server = useServer(!!auth.data?.loggedIn);
  const [value, setValue] = useState("");
  const [msg, setMsg] = useState("");
  const [loaded, setLoaded] = useState(false);
  useEffect(() => {
    if (!loaded && server.data) {
      setValue(server.data.server ?? "");
      setLoaded(true);
    }
  }, [server.data, loaded]);
  async function save(e: React.FormEvent) {
    e.preventDefault();
    setMsg("");
    try {
      await api("/api/v1/server", { method: "POST", body: { server: value.trim() } });
      setMsg("Server saved. Bots on other servers are refused.");
      await server.refetch();
    } catch (err: any) {
      setMsg(err?.message ?? "Save failed.");
    }
  }
  return (
    <Card>
      <CardHeader className="pb-2"><h2 className="font-bold">Minecraft server</h2></CardHeader>
      <CardContent className="flex flex-col gap-2">
        <p className="text-sm text-muted-foreground">
          The single server this Manager serves{server.data?.server ? `: ${server.data.server}` : " (not set yet)"}. The bot only sends its stash name; the server is fixed here.
        </p>
        {perms.owner ? (
          <form onSubmit={save} className="flex gap-2">
            <Input aria-label="Minecraft server" className="flex-1 font-mono" value={value} onChange={(e) => setValue(e.target.value)} placeholder="e.g. play.example.com" />
            <Button type="submit" size="sm">Save server</Button>
          </form>
        ) : (
          <p className="text-sm text-muted-foreground">Only the owner can set the server.</p>
        )}
        {msg && <p role="status" className="text-sm">{msg}</p>}
      </CardContent>
    </Card>
  );
}

function parseVec(text: string): { x: number; y: number; z: number } | null {
  const parts = text.split(/[, ]+/).map((s) => s.trim()).filter(Boolean).map(Number);
  if (parts.length !== 3 || parts.some((n) => !Number.isInteger(n))) return null;
  return { x: parts[0], y: parts[1], z: parts[2] };
}

function StashDefsPanel() {
  const auth = useAuth();
  const defs = useStashDefs(!!auth.data?.loggedIn);
  const [name, setName] = useState("");
  const [dimension, setDimension] = useState("minecraft:overworld");
  const [pos1, setPos1] = useState("");
  const [pos2, setPos2] = useState("");
  const [delivery, setDelivery] = useState("");
  const [anvil, setAnvil] = useState("");
  const [table, setTable] = useState("");
  const [msg, setMsg] = useState("");
  async function create(e: React.FormEvent) {
    e.preventDefault();
    setMsg("");
    const p1 = parseVec(pos1);
    const p2 = parseVec(pos2);
    if (!name.trim() || !p1 || !p2) {
      setMsg("Name and Position 1/2 as 'x, y, z' are required.");
      return;
    }
    try {
      await api("/api/v1/stash-defs", {
        method: "POST",
        body: {
          action: "create",
          name: name.trim(),
          dimension: dimension.trim() || "minecraft:overworld",
          pos1: p1,
          pos2: p2,
          delivery: parseVec(delivery) ?? null,
          anvil: parseVec(anvil) ?? null,
          table: parseVec(table) ?? null,
        },
      });
      setMsg(`Stash '${name.trim()}' created. The cube between the positions is the stash.`);
      setName("");
      setPos1("");
      setPos2("");
      setDelivery("");
      setAnvil("");
      setTable("");
      await defs.refetch();
    } catch (err: any) {
      setMsg(err?.message ?? "Create failed.");
    }
  }
  async function remove(defName: string, revision: number) {
    setMsg("");
    try {
      await api("/api/v1/stash-defs", { method: "POST", body: { action: "delete", name: defName, expectedRevision: revision } });
      setMsg(`Stash '${defName}' deleted.`);
      await defs.refetch();
    } catch (err: any) {
      setMsg(err?.message ?? "Delete failed.");
    }
  }
  return (
    <Card>
      <CardHeader className="pb-2"><h2 className="font-bold">Stashes (regions)</h2></CardHeader>
      <CardContent className="flex flex-col gap-2">
        <p className="text-sm text-muted-foreground">
          Each stash is a cube between Position 1 and Position 2. Delivery point, anvil and workbench are optional placements the bot executes headlessly.
        </p>
        <form onSubmit={create} className="grid grid-cols-2 gap-2">
          <Input aria-label="Stash name" className="font-mono" value={name} onChange={(e) => setName(e.target.value)} placeholder="Stash name" />
          <Input aria-label="Dimension" className="font-mono" value={dimension} onChange={(e) => setDimension(e.target.value)} placeholder="minecraft:overworld" />
          <Input aria-label="Position 1 (x, y, z)" className="font-mono" value={pos1} onChange={(e) => setPos1(e.target.value)} placeholder="Position 1: x, y, z" />
          <Input aria-label="Position 2 (x, y, z)" className="font-mono" value={pos2} onChange={(e) => setPos2(e.target.value)} placeholder="Position 2: x, y, z" />
          <Input aria-label="Delivery (x, y, z, optional)" className="font-mono" value={delivery} onChange={(e) => setDelivery(e.target.value)} placeholder="Delivery: x, y, z (optional)" />
          <Input aria-label="Anvil (x, y, z, optional)" className="font-mono" value={anvil} onChange={(e) => setAnvil(e.target.value)} placeholder="Anvil: x, y, z (optional)" />
          <Input aria-label="Workbench (x, y, z, optional)" className="font-mono" value={table} onChange={(e) => setTable(e.target.value)} placeholder="Workbench: x, y, z (optional)" />
          <div><Button type="submit" size="sm">Create stash</Button></div>
        </form>
        {msg && <p role="status" className="text-sm">{msg}</p>}
        <ul className="flex flex-col gap-1">
          {(defs.data ?? []).map((d) => (
            <li key={d.name} className="flex items-center gap-2 rounded-lg border border-border/60 px-2.5 py-2 text-sm">
              <span className="min-w-0 flex-1">
                <span className="block truncate font-mono font-semibold">{d.name}</span>
                <span className="block truncate font-mono text-xs text-muted-foreground">
                  {d.dimension} [{d.pos1.x},{d.pos1.y},{d.pos1.z}]–[{d.pos2.x},{d.pos2.y},{d.pos2.z}]
                  {d.delivery ? ` · drop ${d.delivery.x},${d.delivery.y},${d.delivery.z}` : ""}
                  {d.anvil ? ` · anvil ${d.anvil.x},${d.anvil.y},${d.anvil.z}` : ""}
                  {d.table ? ` · bench ${d.table.x},${d.table.y},${d.table.z}` : ""}
                </span>
              </span>
              <Button variant="secondary" size="sm" onClick={() => void remove(d.name, d.revision)}>Delete</Button>
            </li>
          ))}
        </ul>
        {defs.data && !defs.data.length && <p className="text-sm text-muted-foreground">No stashes yet. Create the first cube above.</p>}
      </CardContent>
    </Card>
  );
}

function PasswordChange() {
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [msg, setMsg] = useState("");
  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setMsg("");
    try {
      await api("/api/v1/auth/password", { method: "POST", body: { current, next } });
      setMsg("Password changed.");
      setCurrent("");
      setNext("");
    } catch (e: any) {
      setMsg(e?.message ?? "Password change failed.");
    }
  }
  return (
    <form onSubmit={submit} className="flex flex-col gap-2">
      <h3 className="text-sm font-bold">Change password</h3>
      <div className="flex gap-2">
        <Input type="password" aria-label="Current password" placeholder="Current" value={current} onChange={(e) => setCurrent(e.target.value)} required />
        <Input type="password" aria-label="New password" placeholder="New" value={next} onChange={(e) => setNext(e.target.value)} required />
        <Button type="submit" variant="secondary" size="sm">Change</Button>
      </div>
      {msg && <p role="status" className="text-sm">{msg}</p>}
    </form>
  );
}

function AdminUnlock() {
  const auth = useAuth();
  const [password, setPassword] = useState("");
  const [msg, setMsg] = useState("");
  const elevated = !!auth.data?.loggedIn && (auth.data.elevatedUntil ?? 0) > Date.now();
  async function confirm() {
    setMsg("");
    try {
      await api("/api/v1/auth/confirm", { method: "POST", body: { password } });
      setMsg("Admin unlocked for 15 minutes.");
      setPassword("");
      await auth.refetch();
    } catch (e: any) {
      setMsg(e?.message ?? "Confirm failed.");
    }
  }
  async function lock() {
    await api("/api/v1/auth/lock", { method: "POST", body: {} });
    await auth.refetch();
  }
  return (
    <div className="flex flex-col gap-2">
      <h3 className="text-sm font-bold">Admin unlock {elevated && <Badge variant="warning">active</Badge>}</h3>
      {elevated ? (
        <div><Button variant="secondary" size="sm" onClick={lock}>Lock now</Button></div>
      ) : (
        <div className="flex gap-2">
          <Input type="password" aria-label="Confirm password" placeholder="Password" value={password} onChange={(e) => setPassword(e.target.value)} />
          <Button variant="secondary" size="sm" onClick={confirm}>Unlock</Button>
        </div>
      )}
      {msg && <p role="status" className="text-sm">{msg}</p>}
    </div>
  );
}

function UsersAdmin() {
  const [users, setUsers] = useState<any[]>([]);
  const [msg, setMsg] = useState("");
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState("");
  const [password, setPassword] = useState("");
  async function load() {
    setMsg("");
    try {
      const raw = await api<unknown>("/api/v1/users");
      const list = Array.isArray(raw) ? raw : (raw as any)?.users ?? [];
      setUsers(list.map((u: any) => AuthUserSchema.parse({ ...(typeof u === "object" && u !== null ? u : {}), perms: (u as any)?.perms ?? {} })));
    } catch (e: any) {
      setMsg(e?.message ?? "User list unavailable.");
    }
  }
  useEffect(() => { void load(); }, []);
  async function create(e: React.FormEvent) {
    e.preventDefault();
    try {
      await api("/api/v1/users", { method: "POST", body: { name, password } });
      setName("");
      setPassword("");
      setCreating(false);
      await load();
    } catch (e: any) {
      setMsg(e?.message ?? "Create failed.");
    }
  }
  return (
    <div>
      {msg && <p role="status" className="mb-2 text-sm">{msg}</p>}
      <ul className="flex flex-col gap-1.5 text-sm">
        {users.map((u: any) => (
          <li key={u.id} className="flex items-center gap-2 rounded-lg border border-border/60 px-2.5 py-1.5">
            <span className="font-semibold">{u.name}</span>
            {u.owner && <Badge variant="secondary">owner</Badge>}
            {u.disabled && <Badge variant="destructive">disabled</Badge>}
            {u.mustChange && <Badge variant="warning">must change password</Badge>}
            <span className="ml-auto font-mono text-[11px] text-muted-foreground">{u.id}</span>
          </li>
        ))}
      </ul>
      {!creating ? (
        <Button variant="secondary" size="sm" className="mt-2" onClick={() => setCreating(true)}>New user</Button>
      ) : (
        <form onSubmit={create} className="mt-2 flex gap-2">
          <Input aria-label="New user name" placeholder="Name" value={name} onChange={(e) => setName(e.target.value)} required />
          <Input aria-label="New user password" type="password" placeholder="Password" value={password} onChange={(e) => setPassword(e.target.value)} required />
          <Button type="submit" size="sm">Create</Button>
        </form>
      )}
    </div>
  );
}

function LinkPanel() {
  const [link, setLink] = useState<any | null>(null);
  const [msg, setMsg] = useState("");
  async function load() {
    try {
      setLink(await api("/api/v1/link"));
    } catch (e: any) {
      setMsg(e?.message ?? "Link panel unavailable.");
    }
  }
  useEffect(() => { void load(); }, []);
  async function regenerate() {
    try {
      await api("/api/v1/link/regenerate", { method: "POST", body: {} });
      await load();
    } catch (e: any) {
      setMsg(e?.message ?? "Regenerate failed.");
    }
  }
  return (
    <Card>
      <CardHeader className="pb-2"><h2 className="font-bold">Link addon</h2></CardHeader>
      <CardContent className="flex flex-col gap-2 text-sm">
        <p className="text-muted-foreground">Pair a Minecraft addon with this Manager. The API key is shown once — never share it publicly.</p>
        {msg && <p role="status">{msg}</p>}
        {link && <pre className="overflow-auto rounded-lg bg-muted p-2 font-mono text-[11px]">{JSON.stringify(link, null, 1)}</pre>}
        <div><Button variant="secondary" size="sm" onClick={regenerate}>Regenerate API key</Button></div>
      </CardContent>
    </Card>
  );
}
