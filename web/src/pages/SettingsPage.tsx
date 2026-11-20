import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import Chip from "@mui/material/Chip";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import Divider from "@mui/material/Divider";
import FormControl from "@mui/material/FormControl";
import FormControlLabel from "@mui/material/FormControlLabel";
import InputLabel from "@mui/material/InputLabel";
import LinearProgress from "@mui/material/LinearProgress";
import List from "@mui/material/List";
import ListItem from "@mui/material/ListItem";
import ListItemText from "@mui/material/ListItemText";
import MenuItem from "@mui/material/MenuItem";
import Paper from "@mui/material/Paper";
import Select from "@mui/material/Select";
import Stack from "@mui/material/Stack";
import Switch from "@mui/material/Switch";
import Tab from "@mui/material/Tab";
import Tabs from "@mui/material/Tabs";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { useEffect, useState } from "react";
import { api } from "../api/client";
import { useAuth, useGuild, usePermissions, useQuotaMe, useServer, useStashDefs } from "../api/queries";
import type { StashDef } from "../api/types";

function parseVec(text: string): { x: number; y: number; z: number } | null {
  const parts = text.split(/[, ]+/).map((s) => s.trim()).filter(Boolean).map(Number);
  if (parts.length !== 3 || parts.some((n) => !Number.isInteger(n))) return null;
  return { x: parts[0], y: parts[1], z: parts[2] };
}

const vecText = (v?: { x: number; y: number; z: number } | null) => (v ? `${v.x}, ${v.y}, ${v.z}` : "");

/**
 * Settings: server, stash definitions (create/edit/delete with revision conflicts),
 * users & permissions, guild, security, connection and quotas. Owner-only writes.
 */
export function SettingsPage() {
  const auth = useAuth();
  const perms = usePermissions(auth.data);
  const [tab, setTab] = useState(0);

  return (
    <Stack spacing={2}>
      <Typography variant="h1">Settings</Typography>
      <Card>
        <Tabs value={tab} onChange={(_, v) => setTab(v)} variant="scrollable" scrollButtons="auto" aria-label="Settings sections">
          <Tab label="Server" />
          <Tab label="Stashes" />
          {perms.owner && <Tab label="Users" />}
          <Tab label="Guild" />
          <Tab label="Security" />
          {perms.can("link") && <Tab label="Connection" />}
        </Tabs>
        <CardContent>
          {tab === 0 && <ServerSection />}
          {tab === 1 && <StashSection />}
          {tab === 2 && perms.owner && <UsersSection />}
          {tab === (perms.owner ? 3 : 2) && <GuildSection />}
          {tab === (perms.owner ? 4 : 3) && <SecuritySection />}
          {tab === (perms.owner ? 5 : 4) && perms.can("link") && <LinkSection />}
        </CardContent>
      </Card>
    </Stack>
  );
}

function ServerSection() {
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

  async function save() {
    setMsg("");
    if (!window.confirm(
      value.trim()
        ? `Set the Minecraft server to "${value.trim()}"? Bots on other servers are refused; change or clear only when no physical work is running.`
        : "Clear the Minecraft server? All physical work stops until a server is set again.",
    )) return;
    try {
      await api("/api/v1/server", { method: "POST", body: { server: value.trim() } });
      setMsg("Server saved. Bots on other servers are refused with 409.");
      await server.refetch();
    } catch (e: any) {
      setMsg(e?.message ?? "Save failed.");
    }
  }

  return (
    <Stack spacing={2}>
      <Typography color="text.secondary">
        The single server this Manager serves. The bot only sends its stash name; the server is fixed here.
      </Typography>
      <Typography>
        Configured: <Typography component="span" sx={{ fontFamily: "monospace" }}>{server.data?.server || "(not set yet)"}</Typography>
      </Typography>
      {perms.owner ? (
        <Stack direction={{ xs: "column", sm: "row" }} spacing={1}>
          <TextField label="Minecraft server" value={value} onChange={(e) => setValue(e.target.value)} size="small" fullWidth sx={{ "& .MuiInputBase-input": { fontFamily: "monospace" } }} placeholder="e.g. play.example.com" />
          <Button variant="contained" onClick={save} sx={{ flexShrink: 0 }}>Save server</Button>
        </Stack>
      ) : (
        <Typography color="text.secondary">Only the owner can set the server.</Typography>
      )}
      {msg && <Alert severity="info" onClose={() => setMsg("")}>{msg}</Alert>}
    </Stack>
  );
}

function StashSection() {
  const auth = useAuth();
  const perms = usePermissions(auth.data);
  const defs = useStashDefs(!!auth.data?.loggedIn);
  const [editing, setEditing] = useState<null | (Partial<StashDef> & { isNew: boolean })>(null);
  const [msg, setMsg] = useState("");

  function startCreate() {
    setEditing({ isNew: true, name: "", dimension: "minecraft:overworld", revision: 0 });
  }
  function startEdit(d: StashDef) {
    setEditing({ ...d, isNew: false });
  }

  async function remove(d: StashDef) {
    setMsg("");
    if (!window.confirm(`Delete stash "${d.name}"? Bots keep working with their last directive until they rescan; make sure no order or observation is running for it.`)) return;
    try {
      await api("/api/v1/stash-defs", { method: "POST", body: { action: "delete", name: d.name, expectedRevision: d.revision } });
      setMsg(`Stash "${d.name}" deleted.`);
      await defs.refetch();
    } catch (e: any) {
      setMsg(e?.message ?? "Delete failed. Reload and retry on revision conflicts.");
    }
  }

  return (
    <Stack spacing={2}>
      <Typography color="text.secondary">
        Each stash is a cube between Position 1 and Position 2. Delivery point, anvil and workbench are optional
        placements the bot executes headlessly. {perms.owner ? "" : "Only the owner can change definitions."}
      </Typography>
      {defs.isLoading && <LinearProgress />}
      {defs.isError && <Alert severity="error">Could not load stash definitions.</Alert>}
      {msg && <Alert severity="info" onClose={() => setMsg("")}>{msg}</Alert>}
      <Stack spacing={1}>
        {(defs.data ?? []).map((d) => (
          <Card key={d.name} variant="outlined">
            <CardContent sx={{ display: "flex", gap: 1, alignItems: "flex-start" }}>
              <Box sx={{ flexGrow: 1 }}>
                <Typography variant="subtitle1" sx={{ fontFamily: "monospace", fontWeight: 700 }}>{d.name}</Typography>
                <Typography variant="body2" color="text.secondary" sx={{ fontFamily: "monospace" }}>
                  {d.dimension}{d.pos1 && d.pos2 ? ` [${d.pos1.x},${d.pos1.y},${d.pos1.z}]–[${d.pos2.x},${d.pos2.y},${d.pos2.z}]` : " (coordinates hidden)"}
                </Typography>
                <Typography variant="body2" color="text.secondary" sx={{ fontFamily: "monospace" }}>
                  {[d.delivery && `drop ${d.delivery.x},${d.delivery.y},${d.delivery.z}${d.deliveryDimension ? ` in ${d.deliveryDimension}` : ""}`,
                    d.anvil && `anvil ${d.anvil.x},${d.anvil.y},${d.anvil.z}`,
                    d.table && `bench ${d.table.x},${d.table.y},${d.table.z}`].filter(Boolean).join(" · ") || "no placements"}
                  {` · rev ${d.revision}`}
                </Typography>
              </Box>
              {perms.owner && d.pos1 && (
                <Stack direction="row" spacing={1}>
                  <Button size="small" variant="outlined" onClick={() => startEdit(d)}>Edit</Button>
                  <Button size="small" color="error" onClick={() => remove(d)}>Delete</Button>
                </Stack>
              )}
            </CardContent>
          </Card>
        ))}
      </Stack>
      {(defs.data ?? []).length === 0 && !defs.isLoading && (
        <Typography color="text.secondary">No stashes yet. Create the first cube below.</Typography>
      )}
      {perms.owner && <Button variant="contained" onClick={startCreate}>Create stash</Button>}
      {editing && (
        <StashEditor
          initial={editing}
          onClose={() => setEditing(null)}
          onSaved={async (m) => { setEditing(null); setMsg(m); await defs.refetch(); }}
        />
      )}
    </Stack>
  );
}

function StashEditor({ initial, onClose, onSaved }: {
  initial: Partial<StashDef> & { isNew: boolean };
  onClose: () => void;
  onSaved: (msg: string) => void;
}) {
  const [name, setName] = useState(initial.name ?? "");
  const [dimension, setDimension] = useState(initial.dimension ?? "minecraft:overworld");
  const [pos1, setPos1] = useState(vecText(initial.pos1));
  const [pos2, setPos2] = useState(vecText(initial.pos2));
  const [delivery, setDelivery] = useState(vecText(initial.delivery));
  const [deliveryDimension, setDeliveryDimension] = useState(initial.deliveryDimension ?? "");
  const [anvil, setAnvil] = useState(vecText(initial.anvil));
  const [table, setTable] = useState(vecText(initial.table));
  const [error, setError] = useState("");

  async function save() {
    setError("");
    const p1 = parseVec(pos1);
    const p2 = parseVec(pos2);
    if (!name.trim() || !p1 || !p2) {
      setError("Name and Position 1/2 as 'x, y, z' are required.");
      return;
    }
    const body: any = {
      action: initial.isNew ? "create" : "update",
      name: name.trim(),
      dimension: dimension.trim() || "minecraft:overworld",
      pos1: p1,
      pos2: p2,
      delivery: parseVec(delivery),
      deliveryDimension: deliveryDimension.trim() || null,
      anvil: parseVec(anvil),
      table: parseVec(table),
    };
    if (!initial.isNew) body.expectedRevision = initial.revision;
    try {
      await api("/api/v1/stash-defs", { method: "POST", body });
      onSaved(initial.isNew ? `Stash "${name.trim()}" created.` : `Stash "${name.trim()}" updated (rev ${(initial.revision ?? 0) + 1}).`);
    } catch (e: any) {
      setError(e?.message ?? "Save failed. On revision conflicts, close and reopen the editor to reload.");
    }
  }

  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{initial.isNew ? "Create stash" : `Edit stash ${initial.name} (rev ${initial.revision})`}</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ mt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <TextField label="Stash name" value={name} onChange={(e) => setName(e.target.value)} size="small" disabled={!initial.isNew} helperText={initial.isNew ? "Letters, digits, _ . - (max 64)" : "The name is the stash identity and cannot change."} />
          <TextField label="Dimension" value={dimension} onChange={(e) => setDimension(e.target.value)} size="small" sx={{ "& .MuiInputBase-input": { fontFamily: "monospace" } }} />
          <TextField label="Position 1 (x, y, z)" value={pos1} onChange={(e) => setPos1(e.target.value)} size="small" sx={{ "& .MuiInputBase-input": { fontFamily: "monospace" } }} />
          <TextField label="Position 2 (x, y, z)" value={pos2} onChange={(e) => setPos2(e.target.value)} size="small" sx={{ "& .MuiInputBase-input": { fontFamily: "monospace" } }} />
          <TextField label="Delivery (x, y, z, optional)" value={delivery} onChange={(e) => setDelivery(e.target.value)} size="small" sx={{ "& .MuiInputBase-input": { fontFamily: "monospace" } }} />
          <TextField label="Delivery dimension (optional, defaults to stash dimension)" value={deliveryDimension} onChange={(e) => setDeliveryDimension(e.target.value)} size="small" sx={{ "& .MuiInputBase-input": { fontFamily: "monospace" } }} />
          <TextField label="Anvil (x, y, z, optional)" value={anvil} onChange={(e) => setAnvil(e.target.value)} size="small" sx={{ "& .MuiInputBase-input": { fontFamily: "monospace" } }} />
          <TextField label="Workbench (x, y, z, optional)" value={table} onChange={(e) => setTable(e.target.value)} size="small" sx={{ "& .MuiInputBase-input": { fontFamily: "monospace" } }} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" onClick={save}>{initial.isNew ? "Create" : "Save"}</Button>
      </DialogActions>
    </Dialog>
  );
}

function UsersSection() {
  const [users, setUsers] = useState<any[]>([]);
  const [presets, setPresets] = useState<string[]>(["viewer", "orderer", "member", "officer", "operator"]);
  const [presetDefs, setPresetDefs] = useState<Record<string, any>>({});
  const [msg, setMsg] = useState("");
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState("");
  const [password, setPassword] = useState("");
  const [preset, setPreset] = useState("member");
  const [editId, setEditId] = useState<string | null>(null);
  const [editFlags, setEditFlags] = useState<Record<string, boolean>>({});
  const [editDisabled, setEditDisabled] = useState(false);
  const [editName, setEditName] = useState("");
  const [editPreset, setEditPreset] = useState("");

  async function load() {
    setMsg("");
    try {
      const raw = await api<any>("/api/v1/users");
      setUsers(raw?.users ?? []);
      const p = raw?.presets;
      if (p && typeof p === "object") {
        setPresets(Object.keys(p));
        setPresetDefs(p);
      }
    } catch (e: any) {
      setMsg(e?.message ?? "User list unavailable.");
    }
  }
  useEffect(() => { void load(); }, []);

  async function create() {
    try {
      await api("/api/v1/users", { method: "POST", body: { name, password, preset } });
      setName("");
      setPassword("");
      setCreating(false);
      await load();
    } catch (e: any) {
      setMsg(e?.message ?? "Create failed.");
    }
  }

  const FLAG_FIELDS = ["view", "order", "ordersAll", "guildOrders", "positions", "activity", "settings", "rescan", "link"] as const;

  function presetNameOf(perms: any): string {
    for (const [name, def] of Object.entries(presetDefs)) {
      const d: any = def;
      if (FLAG_FIELDS.every((f) => !!d?.[f] === !!perms?.[f])) return name;
    }
    return "custom";
  }

  function startEdit(u: any) {
    const flags: Record<string, boolean> = {};
    for (const f of FLAG_FIELDS) flags[f] = !!u.perms?.[f];
    setEditFlags(flags);
    setEditDisabled(!!u.disabled);
    setEditName(u.name ?? "");
    setEditPreset(presetNameOf(u.perms));
    setEditId(u.id);
  }

  /** Only changed fields are sent; privilege changes need explicit confirm. */
  async function update(id: string, original: any) {
    const changes: string[] = [];
    const perms: Record<string, boolean> = {};
    let permsChanged = false;
    for (const f of FLAG_FIELDS) {
      perms[f] = !!editFlags[f];
      if (!!editFlags[f] !== !!original?.perms?.[f]) {
        permsChanged = true;
        changes.push(`${f}: ${!!original?.perms?.[f]} → ${!!editFlags[f]}`);
      }
    }
    const body: any = {};
    if (permsChanged) body.perms = perms;
    if (editDisabled !== !!original?.disabled) {
      body.disabled = editDisabled;
      changes.push(`disabled: ${!!original?.disabled} → ${editDisabled}`);
    }
    if (editName.trim() !== (original?.name ?? "")) {
      body.name = editName.trim();
      changes.push(`name: ${original?.name ?? ""} → ${editName.trim()}`);
    }
    if (!changes.length) {
      setMsg("No changes — nothing sent.");
      setEditId(null);
      return;
    }
    if (!window.confirm(`Apply to ${original?.name ?? id}?\n${changes.join("\n")}`)) return;
    try {
      await api(`/api/v1/users/${id}`, { method: "POST", body });
      setEditId(null);
      setMsg(`User updated (${changes.length} change${changes.length === 1 ? "" : "s"}).`);
      await load();
    } catch (e: any) {
      setMsg(e?.message ?? "Update failed.");
    }
  }

  async function action(id: string, a: "password" | "logout" | "delete", extra?: any) {
    if (a === "delete" && !window.confirm("Delete this user? Their orders stay; their sessions end.")) return;
    const secret = a === "password" ? window.prompt("New temporary password:") : null;
    if (a === "password" && !secret) return;
    try {
      await api(`/api/v1/users/${id}/${a}`, { method: "POST", body: a === "password" ? { password: secret } : {} });
      setMsg(a === "delete" ? "User deleted." : a === "logout" ? "Sessions ended." : "Password reset.");
      await load();
    } catch (e: any) {
      setMsg(e?.message ?? `${a} failed.`);
    }
    void extra;
  }

  return (
    <Stack spacing={2}>
      {msg && <Alert severity="info" onClose={() => setMsg("")}>{msg}</Alert>}
      <List dense>
        {users.map((u: any) => (
          <ListItem key={u.id} divider alignItems="flex-start" secondaryAction={
            <Stack direction="row" spacing={1}>
              <Button size="small" onClick={() => startEdit(u)}>Edit</Button>
              <Button size="small" onClick={() => action(u.id, "logout")}>End sessions</Button>
              <Button size="small" onClick={() => action(u.id, "password")}>Reset pw</Button>
              <Button size="small" color="error" onClick={() => action(u.id, "delete")}>Delete</Button>
            </Stack>
          }>
            <ListItemText
              primary={<>{u.name} {u.owner && <Chip size="small" label="owner" />} {u.disabled && <Chip size="small" color="error" label="disabled" />}</>}
              secondary={<Typography variant="caption" sx={{ fontFamily: "monospace" }}>{u.id} · perms: {JSON.stringify(u.perms ?? {})} · sessions: {u.sessions ?? 0}</Typography>}
            />
          </ListItem>
        ))}
      </List>
      {users.length === 0 && <Typography color="text.secondary">No sub-users yet.</Typography>}
      {!creating ? (
        <Box><Button variant="outlined" onClick={() => setCreating(true)}>New user</Button></Box>
      ) : (
        <Stack direction={{ xs: "column", sm: "row" }} spacing={1}>
          <TextField label="Name" value={name} onChange={(e) => setName(e.target.value)} size="small" />
          <TextField label="Password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} size="small" />
          <FormControl size="small" sx={{ minWidth: 140 }}>
            <InputLabel id="user-role-label">Role</InputLabel>
            <Select value={preset} labelId="user-role-label" id="user-role" label="Role" onChange={(e) => setPreset(e.target.value)}>
              {presets.map((p) => <MenuItem key={p} value={p}>{p}</MenuItem>)}
            </Select>
          </FormControl>
          <Button variant="contained" onClick={create}>Create</Button>
        </Stack>
      )}
      <Dialog open={!!editId} onClose={() => setEditId(null)} maxWidth="sm" fullWidth>
        <DialogTitle>Edit user {users.find((u: any) => u.id === editId)?.name ?? editId}</DialogTitle>
        <DialogContent>
          <Stack spacing={1.5} sx={{ mt: 1 }}>
            <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
              <Typography variant="body2" color="text.secondary">Current role:</Typography>
              <Chip size="small" label={presetNameOf(users.find((u: any) => u.id === editId)?.perms)} />
            </Stack>
            <FormControl size="small" fullWidth>
              <InputLabel id="edit-preset-label">Apply role preset</InputLabel>
              <Select
                labelId="edit-preset-label" id="edit-preset" label="Apply role preset" value={editPreset}
                onChange={(e) => {
                  setEditPreset(e.target.value);
                  const def: any = presetDefs[e.target.value];
                  if (def) {
                    const next: Record<string, boolean> = {};
                    for (const f of FLAG_FIELDS) next[f] = !!def[f];
                    setEditFlags(next);
                  }
                }}
              >
                {presets.map((p) => <MenuItem key={p} value={p}>{p}</MenuItem>)}
                <MenuItem value="custom">custom (edited below)</MenuItem>
              </Select>
            </FormControl>
            {FLAG_FIELDS.map((f) => (
              <FormControlLabel
                key={f}
                control={
                  <Switch
                    size="small"
                    checked={!!editFlags[f]}
                    onChange={(e) => {
                      setEditFlags({ ...editFlags, [f]: e.target.checked });
                      setEditPreset("custom");
                    }}
                  />
                }
                label={<Typography variant="body2" sx={{ fontFamily: "monospace" }}>{f}</Typography>}
              />
            ))}
            <FormControlLabel
              control={
                <Switch size="small" checked={editDisabled} onChange={(e) => setEditDisabled(e.target.checked)} />
              }
              label={<Typography variant="body2">disabled</Typography>}
            />
            <TextField label="Account name" value={editName} onChange={(e) => setEditName(e.target.value)} size="small" fullWidth />
            <Typography variant="caption" color="text.secondary">
              Only changed fields are sent. Stash/bot scopes are managed server-side and shown read-only in the list.
            </Typography>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setEditId(null)}>Cancel</Button>
          <Button variant="contained" onClick={() => editId && update(editId, users.find((u: any) => u.id === editId))}>Save</Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}

function GuildSection() {
  const auth = useAuth();
  const perms = usePermissions(auth.data);
  const guild = useGuild(!!auth.data?.loggedIn);
  const quotaMe = useQuotaMe(!!auth.data?.loggedIn && perms.can("view"));
  const [name, setName] = useState("");
  const [msg, setMsg] = useState("");

  async function save() {
    setMsg("");
    try {
      await api("/api/v1/guild", { method: "POST", body: { name, expectedRevision: (guild.data as any)?.revision ?? 1 } });
      setMsg("Guild saved.");
      await guild.refetch();
    } catch (e: any) {
      setMsg(e?.message ?? "Save failed.");
    }
  }

  return (
    <Stack spacing={2}>
      <Typography>Guild: {(guild.data as any)?.name || "—"}</Typography>
      {perms.owner ? (
        <Stack direction="row" spacing={1}>
          <TextField label="Guild name" value={name} onChange={(e) => setName(e.target.value)} size="small" placeholder={(guild.data as any)?.name ?? "Guild name"} />
          <Button variant="contained" onClick={save}>Save guild</Button>
        </Stack>
      ) : (
        <Typography color="text.secondary">Only the owner can change guild settings.</Typography>
      )}
      {msg && <Alert severity="info" onClose={() => setMsg("")}>{msg}</Alert>}
      {quotaMe.data && Object.keys((quotaMe.data as any).usage ?? {}).length > 0 && (
        <>
          <Divider />
          <Typography variant="h4">Your withdrawal usage</Typography>
          <Stack spacing={0.5}>
            {Object.entries((quotaMe.data as any).usage).map(([k, v]) => (
              <Typography key={k} variant="caption" sx={{ fontFamily: "monospace" }}>{k}: {JSON.stringify(v)}</Typography>
            ))}
          </Stack>
        </>
      )}
    </Stack>
  );
}

function SecuritySection() {
  const auth = useAuth();
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [msg, setMsg] = useState("");
  const [confirmPw, setConfirmPw] = useState("");
  const elevated = !!auth.data?.loggedIn && (auth.data.elevatedUntil ?? 0) > Date.now();

  async function submit() {
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
  async function confirm() {
    setMsg("");
    try {
      await api("/api/v1/auth/confirm", { method: "POST", body: { password: confirmPw } });
      setMsg("Admin unlocked for 15 minutes.");
      setConfirmPw("");
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
    <Stack spacing={2}>
      <Typography variant="h4">Change password</Typography>
      <Stack direction={{ xs: "column", sm: "row" }} spacing={1}>
        <TextField label="Current" type="password" value={current} onChange={(e) => setCurrent(e.target.value)} size="small" />
        <TextField label="New" type="password" value={next} onChange={(e) => setNext(e.target.value)} size="small" />
        <Button variant="outlined" onClick={submit}>Change</Button>
      </Stack>
      <Divider />
      <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
        <Typography variant="h4">Admin unlock</Typography>
        {elevated && <Chip size="small" color="warning" label="active" />}
      </Stack>
      {elevated ? (
        <Box><Button variant="outlined" onClick={lock}>Lock now</Button></Box>
      ) : (
        <Stack direction="row" spacing={1}>
          <TextField label="Password" type="password" value={confirmPw} onChange={(e) => setConfirmPw(e.target.value)} size="small" />
          <Button variant="outlined" onClick={confirm}>Unlock</Button>
        </Stack>
      )}
      {msg && <Alert severity="info" onClose={() => setMsg("")}>{msg}</Alert>}
    </Stack>
  );
}

function LinkSection() {
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
    if (!window.confirm("Regenerate the addon API key? Connected bots must reconnect with the new key.")) return;
    try {
      await api("/api/v1/link/regenerate", { method: "POST", body: {} });
      await load();
    } catch (e: any) {
      setMsg(e?.message ?? "Regenerate failed.");
    }
  }
  return (
    <Stack spacing={1.5}>
      <Typography color="text.secondary">Pair a Minecraft addon with this Manager. The API key is shown once — never share it publicly.</Typography>
      {msg && <Alert severity="info" onClose={() => setMsg("")}>{msg}</Alert>}
      {link && (
        <Paper variant="outlined" sx={{ p: 1.5 }}>
          <Typography variant="caption" component="pre" sx={{ fontFamily: "monospace", whiteSpace: "pre-wrap", m: 0 }}>
            {JSON.stringify(link, null, 1)}
          </Typography>
        </Paper>
      )}
      <Box><Button variant="outlined" onClick={regenerate}>Regenerate API key</Button></Box>
    </Stack>
  );
}
