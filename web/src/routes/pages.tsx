import { useEffect, useState } from "react";
import { api } from "../api/client";
import { permissionsOf, useAuth, useGuild, useStashes } from "../api/queries";
import { Button, Card, Disclaimer, Input } from "../components/ui";
import { OrderHistory } from "./shop";

export function LoginPage() {
  const auth = useAuth();
  const [name, setName] = useState("");
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState("");

  const setupNeeded = (auth.data as any)?.setupNeeded === true;
  const minLength = (auth.data as any)?.minLength ?? 12;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy("login");
    setError("");
    try {
      await api("/api/v1/auth/login", { method: "POST", body: name ? { name, password } : { password } });
      await auth.refetch();
      window.location.assign("/shop");
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
      window.location.assign("/shop");
    } catch (err: any) {
      setError(err?.message ?? "Setup failed. Request a code with the local bootstrap command.");
    } finally {
      setBusy("");
    }
  }

  return (
    <Card className="mx-auto max-w-md" >
      <div id="auth-screen">
      <img src="/wesort-lockup.svg" alt="WeSort" className="auth-logo mb-2 h-10" />
      <h1 className="mb-2 text-xl font-semibold">{setupNeeded ? "First-run setup" : "Log in"}</h1>
      {setupNeeded ? (
      <form onSubmit={submitSetup} className="flex flex-col gap-2">
        <p className="text-sm opacity-80">No owner password is set yet. Enter the setup code from the local bootstrap command and choose a password (min {minLength} chars).</p>
        <label>Setup code <Input id="auth-code" value={code} onChange={(e) => setCode(e.target.value)} autoComplete="off" required /></label>
        <label>Owner password <Input id="auth-password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" required /></label>
        {error && <p role="alert" className="text-sm text-red-400">{error}</p>}
        <Button id="auth-submit" type="submit" disabled={busy !== ""}>{busy === "setup" ? "Setting up…" : "Create owner"}</Button>
      </form>
      ) : (
      <form onSubmit={submit} className="flex flex-col gap-2">
        <label>Account name (blank for owner) <Input id="auth-username" value={name} onChange={(e) => setName(e.target.value)} autoComplete="username" /></label>
        <label>Password <Input id="auth-password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required /></label>
        {error && <p role="alert" className="text-sm text-red-400">{error}</p>}
        <Button id="auth-submit" type="submit" disabled={busy !== ""}>{busy === "login" ? "Logging in…" : "Log in"}</Button>
      </form>
      )}
      <Disclaimer id="minecraft-disclaimer-login" />
      </div>
    </Card>
  );
}

export function OrdersPage() {
  const auth = useAuth();
  const perms = permissionsOf(auth.data);
  const stashes = useStashes(!!auth.data?.loggedIn && perms.has("view"));
  const first = stashes.data?.[0];
  const [clearMsg, setClearMsg] = useState("");
  async function clearFinished() {
    if (!first) return;
    try {
      const res = await api<{ removed: number }>("/api/v1/orders/clear", { method: "POST", body: { server: first.server, stash: first.stash } });
      setClearMsg(`Cleared ${res.removed} finished orders.`);
    } catch (e: any) {
      setClearMsg(e?.message ?? "Clear failed.");
    }
  }
  return (
    <div>
      {first && <OrderHistory server={first.server} stash={first.stash} />}
      {perms.has("ordersAll") && first && (
        <Card className="mt-4">
          <h2 className="mb-1 font-semibold">Order administration</h2>
          <Button id="orders-clear" className="small" onClick={clearFinished}>Clear finished orders</Button>
          {clearMsg && <p role="status" className="text-sm">{clearMsg}</p>}
        </Card>
      )}
      {perms.has("ordersAll") && <OrderAudit />}
    </div>
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
      <h2 className="mb-1 font-semibold">Order audit</h2>
      <div id="order-audit" className="flex flex-col gap-2">
        <label>Person <Input aria-label="Person" value={person} onChange={(e) => setPerson(e.target.value)} /></label>
        <label>Item key <Input aria-label="Item key" value={item} onChange={(e) => setItem(e.target.value)} /></label>
        <div><Button className="small" onClick={search}>Search audit</Button></div>
        <p id="order-audit-status" role="status" className="text-sm">{status}</p>
        <div id="order-audit-results" className="flex flex-col gap-1">
          {results.map((o: any) => (
            <details key={o.id}>
              <summary>{o.id} · {o.requestedBy ?? o.placedBy?.name ?? ""}</summary>
              <pre className="text-xs">{JSON.stringify(o, null, 1)}</pre>
            </details>
          ))}
        </div>
      </div>
    </Card>
  );
}

export function BotsPage() {
  const auth = useAuth();
  const perms = permissionsOf(auth.data);
  const [bots, setBots] = useState<any[]>([]);
  const [error, setError] = useState("");
  async function load() {
    try {
      setBots(await api<any[]>("/api/v1/bots"));
    } catch (e: any) {
      setError(e?.message ?? "Could not load bots.");
    }
  }
  useEffect(() => {
    if (perms.has("activity")) void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  return (
    <Card>
      <h1 className="mb-2 text-xl font-semibold">Bots</h1>
      {error && <p role="alert" className="text-sm text-red-400">{error}</p>}
      <ul className="flex flex-col gap-1">
        {bots.map((b: any) => (
          <li key={b.id ?? b.botId} className="rounded border border-stone-700 p-2 text-sm">
            <strong>{b.player ?? b.id}</strong> · {b.online === false ? "offline" : "online"} · {b.server ?? ""} {b.stash ?? ""}
          </li>
        ))}
      </ul>
      {!bots.length && !error && <p className="text-sm opacity-70">No bots reported yet.</p>}
      <Button className="small mt-2" onClick={load}>Refresh</Button>
    </Card>
  );
}

export function ActivityPage() {
  const [items, setItems] = useState<any[]>([]);
  const [error, setError] = useState("");
  async function load() {
    try {
      setItems(await api<any[]>("/api/v1/bot/activity"));
    } catch (e: any) {
      setError(e?.message ?? "Activity unavailable.");
    }
  }
  useEffect(() => {
    void load();
  }, []);
  return (
    <Card>
      <h1 className="mb-2 text-xl font-semibold">Activity</h1>
      {error && <p role="alert" className="text-sm text-red-400">{error}</p>}
      <ul className="flex flex-col gap-1 text-sm">
        {items.slice(0, 100).map((a: any, i: number) => (
          <li key={i} className="rounded border border-stone-700 p-1">{a.message ?? JSON.stringify(a)}</li>
        ))}
      </ul>
      {!items.length && !error && <p className="text-sm opacity-70">No activity yet.</p>}
      <Button className="small mt-2" onClick={load}>Refresh</Button>
    </Card>
  );
}

export function SettingsPage() {
  const auth = useAuth();
  const guild = useGuild(!!auth.data?.loggedIn);
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
    <div className="flex flex-col gap-4">
      <Card>
        <h1 className="mb-2 text-xl font-semibold">Settings</h1>
        <p className="text-sm opacity-80">Guild: {guild.data?.name ?? "—"}</p>
        <form onSubmit={save} className="mt-2 flex gap-2">
          <Input aria-label="Guild name" value={name} onChange={(e) => setName(e.target.value)} placeholder={guild.data?.name ?? "Guild name"} />
          <Button type="submit" className="small">Save guild</Button>
        </form>
        {msg && <p role="status" className="text-sm">{msg}</p>}
      </Card>
      <Card>
        <h2 className="mb-1 font-semibold">User administration</h2>
        <UsersAdmin />
      </Card>
    </div>
  );
}

function UsersAdmin() {
  const [users, setUsers] = useState<any[]>([]);
  const [msg, setMsg] = useState("");
  async function load() {
    try {
      setUsers(await api<any[]>("/api/v1/users"));
    } catch (e: any) {
      setMsg(e?.message ?? "User list unavailable.");
    }
  }
  return (
    <div>
      <Button className="small" onClick={load}>Load users</Button>
      {msg && <p role="status" className="text-sm">{msg}</p>}
      <ul className="mt-1 flex flex-col gap-1 text-sm">
        {users.map((u: any) => (
          <li key={u.id ?? u.name} className="rounded border border-stone-700 p-1">{u.name ?? u.id}{u.owner ? " (owner)" : ""}</li>
        ))}
      </ul>
    </div>
  );
}
