import Alert from "@mui/material/Alert";
import Autocomplete from "@mui/material/Autocomplete";
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
import MenuItem from "@mui/material/MenuItem";
import Paper from "@mui/material/Paper";
import Radio from "@mui/material/Radio";
import RadioGroup from "@mui/material/RadioGroup";
import Select from "@mui/material/Select";
import Stack from "@mui/material/Stack";
import Switch from "@mui/material/Switch";
import Tab from "@mui/material/Tab";
import Tabs from "@mui/material/Tabs";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import { useAuth, useBots, useGuild, useKitTemplates, usePermissions, useServer, useStashDefs } from "../api/queries";
import { ConfirmDialog, PasswordDialog, useConfirm } from "../components/dialogs";
import { botListOf, buildUserPerms, presetMatches, scopeDiff, stashListOf } from "../lib/userScopes";
import type { KitTemplate, StashDef } from "../api/types";

function parseVec(text: string): { x: number; y: number; z: number } | null {
  const parts = text.split(/[, ]+/).map((s) => s.trim()).filter(Boolean).map(Number);
  if (parts.length !== 3 || parts.some((n) => !Number.isInteger(n))) return null;
  return { x: parts[0], y: parts[1], z: parts[2] };
}

const vecText = (v?: { x: number; y: number; z: number } | null) => (v ? `${v.x}, ${v.y}, ${v.z}` : "");

/**
 * Settings: server, stash definitions (create/edit/delete with revision conflicts),
 * users & permissions, guild, security and connection. Owner-only writes.
 */
export function SettingsPage() {
  const auth = useAuth();
  const perms = usePermissions(auth.data);
  type Section = "server" | "stashes" | "kits" | "users" | "keys" | "guild" | "security" | "connection";
  const [tab, setTab] = useState<Section>("server");
  // Permissions can change mid-session: stable tab IDs prevent index drift.
  const visible: Section[] = ["server", "stashes",
    ...(perms.owner ? ["kits", "users", "keys"] as Section[] : []),
    "guild", "security", ...(perms.can("link") ? ["connection"] as Section[] : [])];
  const active = visible.includes(tab) ? tab : "server";
  useEffect(() => { if (active !== tab) setTab(active); }, [active, tab]);

  return (
    <Stack spacing={2}>
      <Typography variant="h5" component="h1">Settings</Typography>
      <Card>
        <Tabs value={active} onChange={(_, v: Section) => setTab(v)} variant="scrollable" scrollButtons="auto" aria-label="Settings sections">
          <Tab value="server" label="Server" />
          <Tab value="stashes" label="Stashes" />
          {perms.owner && <Tab value="kits" label="Kits" />}
          {perms.owner && <Tab value="users" label="Users" />}
          {perms.owner && <Tab value="keys" label="API keys" />}
          <Tab value="guild" label="Guild" />
          <Tab value="security" label="Security" />
          {perms.can("link") && <Tab value="connection" label="Connection" />}
        </Tabs>
        <CardContent>
          {active === "server" && <ServerSection />}
          {active === "stashes" && <StashSection />}
          {active === "kits" && perms.owner && <KitSection />}
          {active === "users" && perms.owner && <UsersSection />}
          {active === "keys" && perms.owner && <ApiKeysSection />}
          {active === "guild" && <GuildSection />}
          {active === "security" && <SecuritySection />}
          {active === "connection" && perms.can("link") && <LinkSection />}
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
  const confirm = useConfirm();
  useEffect(() => {
    if (!loaded && server.data) {
      setValue(server.data.server ?? "");
      setLoaded(true);
    }
  }, [server.data, loaded]);

  async function save() {
    setMsg("");
    confirm.ask({
      title: value.trim() ? `Set the Minecraft server to "${value.trim()}"?` : "Clear the Minecraft server?",
      intro: value.trim()
        ? "Bots on other servers are refused; change or clear only when no physical work is running."
        : "All physical work stops until a server is set again.",
      confirmLabel: "Save server",
      run: async () => {
        await api("/api/v1/server", { method: "POST", body: { server: value.trim() } });
        setMsg("Server saved. Bots on other servers are refused with 409.");
        await server.refetch();
      },
    });
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
      {confirm.dialog}
    </Stack>
  );
}

function StashSection() {
  const auth = useAuth();
  const perms = usePermissions(auth.data);
  const defs = useStashDefs(!!auth.data?.loggedIn);
  const [editing, setEditing] = useState<null | (Partial<StashDef> & { isNew: boolean })>(null);
  const [msg, setMsg] = useState("");
  const confirm = useConfirm();

  function startCreate() {
    setEditing({ isNew: true, name: "", dimension: "minecraft:overworld", revision: 0 });
  }
  function startEdit(d: StashDef) {
    setEditing({ ...d, isNew: false });
  }

  function remove(d: StashDef) {
    setMsg("");
    confirm.ask({
      title: `Delete stash "${d.name}"?`,
      intro: "Bots keep working with their last directive until they rescan; make sure no order or observation is running for it.",
      changes: [`stash: ${d.name}`, `revision: ${d.revision}`],
      confirmLabel: "Delete",
      danger: true,
      run: async () => {
        await api("/api/v1/stash-defs", { method: "POST", body: { action: "delete", name: d.name, expectedRevision: d.revision } });
        setMsg(`Stash "${d.name}" deleted.`);
        await defs.refetch();
      },
    });
  }

  return (
    <Stack spacing={2}>
      <Typography color="text.secondary">
        Each stash is a cube between Position 1 and Position 2. Drop-off chest, delivery point, anvil and workbench are optional
        placements the bot executes headlessly. The drop-off chest is where members leave items for sorting into the stash — set it here, never in the game. {perms.owner ? "" : "Only the owner can change definitions."}
      </Typography>
      {defs.isLoading && <LinearProgress />}
      {defs.isError && <Alert severity="error">Could not load stash definitions.</Alert>}
      {msg && <Alert severity="info" onClose={() => setMsg("")}>{msg}</Alert>}
      <Stack spacing={1}>
        {(defs.data ?? []).map((d) => (
          <Card key={d.name} variant="outlined">
            <CardContent sx={{ display: "flex", gap: 1, alignItems: "flex-start", flexDirection: { xs: "column", sm: "row" } }}>
              <Box sx={{ flexGrow: 1, minWidth: 0, width: "100%" }}>
                <Typography variant="subtitle1" sx={{ fontFamily: "monospace", fontWeight: 700, wordBreak: "break-all" }}>{d.name}</Typography>
                <Typography variant="body2" color="text.secondary" sx={{ fontFamily: "monospace", wordBreak: "break-all" }}>
                  {d.dimension}{d.pos1 && d.pos2 ? ` [${d.pos1.x},${d.pos1.y},${d.pos1.z}]–[${d.pos2.x},${d.pos2.y},${d.pos2.z}]` : " (coordinates hidden)"}
                </Typography>
                <Typography variant="body2" color="text.secondary" sx={{ fontFamily: "monospace", wordBreak: "break-all" }}>
                  {[d.dropoff && `drop-off ${d.dropoff.x},${d.dropoff.y},${d.dropoff.z}${d.dropoffDimension ? ` in ${d.dropoffDimension}` : ""}`,
                    d.delivery && `drop ${d.delivery.x},${d.delivery.y},${d.delivery.z}${d.deliveryDimension ? ` in ${d.deliveryDimension}` : ""}`,
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
      {confirm.dialog}
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
  const [dropoff, setDropoff] = useState(vecText(initial.dropoff));
  const [dropoffDimension, setDropoffDimension] = useState(initial.dropoffDimension ?? "");
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
      dropoff: parseVec(dropoff),
      dropoffDimension: dropoffDimension.trim() || null,
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
          <TextField label="Drop-off (x, y, z, optional)" value={dropoff} onChange={(e) => setDropoff(e.target.value)} size="small" sx={{ "& .MuiInputBase-input": { fontFamily: "monospace" } }} helperText="Members leave items here for sorting in. Never set in the game." />
          <TextField label="Drop-off dimension (optional, defaults to stash dimension)" value={dropoffDimension} onChange={(e) => setDropoffDimension(e.target.value)} size="small" sx={{ "& .MuiInputBase-input": { fontFamily: "monospace" } }} />
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

/**
 * Owner-managed kit templates: what one ordered shulker box holds, slot for
 * slot. One kit is exactly one box, packed on demand — finished boxes are
 * never stored. Orders pin the template revision at submit.
 */
function KitSection() {
  const auth = useAuth();
  const kits = useKitTemplates(!!auth.data?.loggedIn);
  const [editing, setEditing] = useState<null | (Partial<KitTemplate> & { isNew: boolean })>(null);
  const [msg, setMsg] = useState("");
  const confirm = useConfirm();

  function remove(k: KitTemplate) {
    setMsg("");
    confirm.ask({
      title: `Delete kit "${k.name}"?`,
      intro: "Placed orders keep their pinned layout, but the kit can no longer be ordered.",
      changes: [`kit: ${k.name}`, `revision: ${k.revision}`],
      confirmLabel: "Delete",
      danger: true,
      run: async () => {
        await api("/api/v1/kits", { method: "POST", body: { action: "delete", name: k.name, expectedRevision: k.revision } });
        setMsg(`Kit "${k.name}" deleted.`);
        await kits.refetch();
      },
    });
  }

  return (
    <Stack spacing={2}>
      <Typography color="text.secondary">
        Each kit is exactly one shulker box, packed on demand with the exact slot placement below.
        Orders reserve the components from loose stock; finished kit boxes are never stored.
      </Typography>
      {kits.isLoading && <LinearProgress />}
      {kits.isError && <Alert severity="error">Could not load kit templates.</Alert>}
      {msg && <Alert severity="info" onClose={() => setMsg("")}>{msg}</Alert>}
      <Stack spacing={1}>
        {(kits.data ?? []).map((k) => (
          <Card key={k.name} variant="outlined">
            <CardContent sx={{ display: "flex", gap: 1, alignItems: "flex-start", flexDirection: { xs: "column", sm: "row" } }}>
              <Box sx={{ flexGrow: 1, minWidth: 0, width: "100%" }}>
                <Typography variant="subtitle1" sx={{ fontWeight: 700, wordBreak: "break-all" }}>{k.name}</Typography>
                <Typography variant="body2" color="text.secondary" sx={{ fontFamily: "monospace", wordBreak: "break-all" }}>
                  {k.slots.length === 0 ? "empty" : k.slots.map((s) => `${s.slot}:${s.count}× ${s.key}`).join(" · ")}
                </Typography>
                <Typography variant="body2" color="text.secondary">{`rev ${k.revision}`}</Typography>
              </Box>
              <Stack direction="row" spacing={1}>
                <Button size="small" variant="outlined" onClick={() => setEditing({ ...k, isNew: false })}>Edit</Button>
                <Button size="small" color="error" onClick={() => remove(k)}>Delete</Button>
              </Stack>
            </CardContent>
          </Card>
        ))}
      </Stack>
      {(kits.data ?? []).length === 0 && !kits.isLoading && (
        <Typography color="text.secondary">No kits yet. Create the first box below.</Typography>
      )}
      <Button variant="contained" onClick={() => setEditing({ isNew: true, name: "", revision: 0, slots: [] })}>Create kit</Button>
      {editing && (
        <KitEditor
          initial={editing}
          onClose={() => setEditing(null)}
          onSaved={async (m) => { setEditing(null); setMsg(m); await kits.refetch(); }}
        />
      )}
      {confirm.dialog}
    </Stack>
  );
}

function KitEditor({ initial, onClose, onSaved }: {
  initial: Partial<KitTemplate> & { isNew: boolean };
  onClose: () => void;
  onSaved: (msg: string) => void;
}) {
  const [name, setName] = useState(initial.name ?? "");
  const [rows, setRows] = useState<{ key: string; count: string }[]>(
    Array.from({ length: 27 }, (_, slot) => {
      const s = initial.slots?.find((x) => x.slot === slot);
      return { key: s?.key ?? "", count: s ? String(s.count) : "" };
    }),
  );
  const [error, setError] = useState("");

  function setRow(slot: number, patch: Partial<{ key: string; count: string }>) {
    setRows((r) => r.map((row, i) => (i === slot ? { ...row, ...patch } : row)));
  }

  async function save() {
    setError("");
    const slots: { slot: number; key: string; count: number }[] = [];
    for (let slot = 0; slot < 27; slot++) {
      const row = rows[slot];
      const key = row.key.trim();
      if (!key) continue;
      const count = Number(row.count);
      if (!Number.isInteger(count) || count < 1 || count > 64) {
        setError(`Slot ${slot}: count must be a whole number 1-64.`);
        return;
      }
      slots.push({ slot, key, count });
    }
    if (!name.trim()) {
      setError("Name is required (letters, digits, _ . -, max 64).");
      return;
    }
    if (slots.length === 0) {
      setError("A kit needs at least one filled slot.");
      return;
    }
    const body: any = { action: initial.isNew ? "create" : "update", name: name.trim(), slots };
    if (!initial.isNew) body.expectedRevision = initial.revision;
    try {
      await api("/api/v1/kits", { method: "POST", body });
      onSaved(initial.isNew ? `Kit "${name.trim()}" created.` : `Kit "${name.trim()}" updated (rev ${(initial.revision ?? 0) + 1}).`);
    } catch (e: any) {
      setError(e?.message ?? "Save failed. On revision conflicts, close and reopen the editor to reload.");
    }
  }

  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>{initial.isNew ? "Create kit" : `Edit kit ${initial.name} (rev ${initial.revision})`}</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ mt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <TextField label="Kit name" value={name} onChange={(e) => setName(e.target.value)} size="small" disabled={!initial.isNew} helperText={initial.isNew ? "Letters, digits, _ . - (max 64). One kit is one box." : "The name is the kit identity and cannot change."} />
          <Typography variant="body2" color="text.secondary">
            One row per box slot (0-26). Leave the key empty to leave the slot empty.
          </Typography>
          <Stack spacing={0.5}>
            {rows.map((row, slot) => (
              <Stack key={slot} direction="row" spacing={1} sx={{ alignItems: "center" }}>
                <Typography variant="caption" color="text.secondary" sx={{ minWidth: 24, textAlign: "right", fontFamily: "monospace" }}>{slot}</Typography>
                <TextField label="Item key" value={row.key} onChange={(e) => setRow(slot, { key: e.target.value })} size="small" fullWidth placeholder="minecraft:diamond" sx={{ "& .MuiInputBase-input": { fontFamily: "monospace" } }} />
                <TextField label="Count" value={row.count} onChange={(e) => setRow(slot, { count: e.target.value })} size="small" type="number" sx={{ width: 110 }} slotProps={{ htmlInput: { min: 1, max: 64 } }} />
              </Stack>
            ))}
          </Stack>
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
  const [msg, setMsg] = useState<{ severity: "success" | "error" | "info"; text: string } | null>(null);
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState("");
  const [password, setPassword] = useState("");
  const [preset, setPreset] = useState("member");
  const [editId, setEditId] = useState<string | null>(null);
  const [editFlags, setEditFlags] = useState<Record<string, boolean>>({});
  const [editDisabled, setEditDisabled] = useState(false);
  const [editName, setEditName] = useState("");
  const [editPreset, setEditPreset] = useState("");
  const [editStashMode, setEditStashMode] = useState<"all" | "selected">("all");
  const [editStashes, setEditStashes] = useState<string[]>([]);
  const [editBotMode, setEditBotMode] = useState<"all" | "selected">("all");
  const [editBots, setEditBots] = useState<string[]>([]);
  const [editIgn, setEditIgn] = useState("");
  const [owner, setOwner] = useState<any | null>(null);
  const [ownerIgn, setOwnerIgn] = useState("");
  const [ownerIgnOpen, setOwnerIgnOpen] = useState(false);
  const stashDefs = useStashDefs(true);
  const botsList = useBots(true);
  const [minLength, setMinLength] = useState(12);
  const [confirm, setConfirm] = useState<null | {
    title: string; intro?: string; changes?: string[]; confirmLabel: string; danger?: boolean; run: () => Promise<void>;
  }>(null);
  const [confirmBusy, setConfirmBusy] = useState(false);
  const [confirmError, setConfirmError] = useState("");
  const [pwUser, setPwUser] = useState<any | null>(null);
  const [pwBusy, setPwBusy] = useState(false);
  const [pwError, setPwError] = useState("");

  const usersQuery = useQuery({
    queryKey: ["users"],
    queryFn: () => api<any>("/api/v1/users"),
    staleTime: 10_000,
    retry: 1,
  });
  useEffect(() => {
    const raw = usersQuery.data;
    if (!raw) return;
    setUsers(raw.users ?? []);
    setOwner(raw.owner ?? null);
    if (typeof raw.minLength === "number") setMinLength(raw.minLength);
    if (raw.presets && typeof raw.presets === "object") {
      setPresets(Object.keys(raw.presets));
      setPresetDefs(raw.presets);
    }
  }, [usersQuery.data]);
  async function load() {
    const refreshed = await usersQuery.refetch();
    if (refreshed.error) setMsg({ severity: "error", text: refreshed.error.message ?? "User list unavailable." });
  }

  async function create() {
    try {
      await api("/api/v1/users", { method: "POST", body: { name, password, preset } });
      setName("");
      setPassword("");
      setCreating(false);
      await load();
    } catch (e: any) {
      setMsg({ severity: "error", text: e?.message ?? "Create failed." });
    }
  }

  const FLAG_FIELDS = ["view", "order", "ordersAll", "guildOrders", "positions", "activity", "settings", "rescan", "link"] as const;

  function presetNameOf(perms: any): string {
    for (const [name, def] of Object.entries(presetDefs)) {
      // A scoped user is never an unlimited preset, even when the boolean
      // flags happen to match: null and [] are distinct access levels.
      if (presetMatches(def, perms, FLAG_FIELDS)) return name;
    }
    return "custom";
  }

  function scopeSummary(label: string, value: unknown, items: string[]): string {
    if (value == null) return `${label}: All`;
    if (items.length === 0) return `${label}: None (no access)`;
    return `${label}: ${items.join(", ")}`;
  }

  function startEdit(u: any) {
    const flags: Record<string, boolean> = {};
    for (const f of FLAG_FIELDS) flags[f] = !!u.perms?.[f];
    setEditFlags(flags);
    setEditDisabled(!!u.disabled);
    setEditName(u.name ?? "");
    setEditIgn(u.minecraftAccount ?? "");
    setEditStashMode(u.perms?.stashes == null ? "all" : "selected");
    setEditStashes(stashListOf(u.perms));
    setEditBotMode(u.perms?.bots == null ? "all" : "selected");
    setEditBots(botListOf(u.perms));
    setEditPreset(presetNameOf(u.perms));
    setEditId(u.id);
  }

  function fullPerms() {
    // Always explicit: absent keys would be ambiguous with unlimited.
    return buildUserPerms(editFlags, FLAG_FIELDS, editStashMode, editStashes, editBotMode, editBots);
  }

  /** Only changed fields are sent; privilege changes need explicit confirm. */
  function update(id: string, original: any) {
    const changes: string[] = [];
    const before = {
      stashes: original?.perms?.stashes == null ? null : stashListOf(original.perms),
      bots: original?.perms?.bots == null ? null : botListOf(original.perms),
    };
    const nextStashes = editStashMode === "all" ? null : [...editStashes].sort();
    const nextBots = editBotMode === "all" ? null : [...editBots].sort();
    let permsChanged = false;
    for (const f of FLAG_FIELDS) {
      if (!!editFlags[f] !== !!original?.perms?.[f]) {
        permsChanged = true;
        changes.push(`${f}: ${!!original?.perms?.[f]} → ${!!editFlags[f]}`);
      }
    }
    changes.push(...scopeDiff(before.stashes, nextStashes, before.bots, nextBots));
    if (changes.some((c) => c.startsWith("stashes:") || c.startsWith("bots:"))) permsChanged = true;
    const body: any = {};
    if (permsChanged) body.perms = fullPerms();
    if (editDisabled !== !!original?.disabled) {
      body.disabled = editDisabled;
      changes.push(`disabled: ${!!original?.disabled} → ${editDisabled}`);
    }
    if (editName.trim() !== (original?.name ?? "")) {
      body.name = editName.trim();
      changes.push(`name: ${original?.name ?? ""} → ${editName.trim()}`);
    }
    const nextIgn = editIgn.trim();
    const prevIgn = original?.minecraftAccount ?? "";
    const ignChanged = nextIgn !== prevIgn;
    if (ignChanged) changes.push(`minecraft: ${prevIgn || "(none)"} → ${nextIgn || "(none)"}`);
    if (!changes.length) {
      setMsg({ severity: "info", text: "No changes — nothing sent." });
      setEditId(null);
      return;
    }
    setConfirm({
      title: `Apply to ${original?.name ?? id}?`,
      intro: permsChanged
        ? "The new permission object replaces the old one. Scopes are shown explicitly: All means every stash/bot, None means no access."
        : undefined,
      changes,
      confirmLabel: "Apply",
      run: async () => {
        if (Object.keys(body).length) await api(`/api/v1/users/${id}`, { method: "POST", body });
        if (ignChanged) await api(`/api/v1/users/${id}/minecraft`, { method: "POST", body: { account: nextIgn } });
        setEditId(null);
        setMsg({ severity: "success", text: `User updated (${changes.length} change${changes.length === 1 ? "" : "s"}).` });
        await load();
      },
    });
  }

  function action(id: string, a: "logout" | "delete") {
    const u = users.find((x: any) => x.id === id);
    if (a === "logout") {
      setConfirm({
        title: `End sessions for ${u?.name ?? id}?`,
        intro: "All of their sessions end immediately. They must sign in again.",
        confirmLabel: "End sessions",
        run: async () => {
          await api(`/api/v1/users/${id}/logout`, { method: "POST", body: {} });
          setMsg({ severity: "success", text: "Sessions ended." });
          await load();
        },
      });
      return;
    }
    setConfirm({
      title: `Delete ${u?.name ?? id}?`,
      intro: "Their orders stay; their sessions end. This cannot be undone.",
      changes: [
        `name: ${u?.name ?? id}`,
        scopeSummary("stashes", u?.perms?.stashes, stashListOf(u?.perms)),
        scopeSummary("bots", u?.perms?.bots, botListOf(u?.perms)),
      ],
      confirmLabel: "Delete",
      danger: true,
      run: async () => {
        await api(`/api/v1/users/${id}/delete`, { method: "POST", body: {} });
        setMsg({ severity: "success", text: "User deleted." });
        await load();
      },
    });
  }

  async function runConfirm() {
    if (!confirm) return;
    setConfirmBusy(true);
    setConfirmError("");
    try {
      await confirm.run();
      setConfirm(null);
    } catch (e: any) {
      setConfirmError(e?.message ?? "Action failed.");
    } finally {
      setConfirmBusy(false);
    }
  }

  async function resetPassword(password: string) {
    if (!pwUser) return;
    setPwBusy(true);
    setPwError("");
    try {
      await api(`/api/v1/users/${pwUser.id}/password`, { method: "POST", body: { password } });
      setPwUser(null);
      setMsg({ severity: "success", text: "Password reset." });
      await load();
    } catch (e: any) {
      setPwError(e?.message ?? "Reset failed.");
    } finally {
      setPwBusy(false);
    }
  }

  const stashOptions = Array.from(new Set([
    ...((stashDefs.data ?? []) as any[]).map((d: any) => String(d?.name ?? "")).filter(Boolean),
    ...users.flatMap((u: any) => stashListOf(u.perms)),
  ])).sort();
  const botOptions = Array.from(new Set([
    ...((botsList.data ?? []) as any[]).map((b: any) => String(b?.botId ?? "")).filter(Boolean),
    ...users.flatMap((u: any) => botListOf(u.perms)),
  ])).sort();

  function scopeChips(u: any) {
    const stashes = u.perms?.stashes == null ? null : stashListOf(u.perms);
    const bots = u.perms?.bots == null ? null : botListOf(u.perms);
    return (
      <Stack direction="row" spacing={0.5} sx={{ flexWrap: "wrap", gap: 0.5, mt: 0.5 }}>
        <Chip size="small" variant="outlined" label={stashes == null ? "All stashes" : stashes.length ? `Stashes: ${stashes.join(", ")}` : "No stashes"} />
        <Chip size="small" variant="outlined" label={bots == null ? "All bots" : bots.length ? `Bots: ${bots.join(", ")}` : "No bots"} />
        <Chip size="small" variant="outlined" label={u.minecraftAccount ? `IGN: ${u.minecraftAccount}` : "No IGN"} />
      </Stack>
    );
  }

  return (
    <Stack spacing={2}>
      {msg && <Alert severity={msg.severity} onClose={() => setMsg(null)}>{msg.text}</Alert>}
      {usersQuery.isError && <Alert severity="error">Cannot refresh user permissions.</Alert>}
      {owner && (
        <Paper variant="outlined" sx={{ p: 1.5 }}>
          <Stack spacing={1}>
            <Stack direction="row" spacing={1} sx={{ alignItems: "center", flexWrap: "wrap" }}>
              <Typography sx={{ fontWeight: 600, wordBreak: "break-word" }}>{owner.name}</Typography>
              <Chip size="small" label="owner" />
              {owner.minecraftAccount
                ? <Chip size="small" variant="outlined" label={`IGN: ${owner.minecraftAccount}`} />
                : <Chip size="small" variant="outlined" label="No IGN" />}
            </Stack>
            <Typography variant="caption" color="text.secondary" sx={{ fontFamily: "monospace" }}>
              sessions: {owner.sessions ?? 0} · Minecraft IGN is used for recipient verification.
            </Typography>
            <Box><Button size="small" variant="outlined" onClick={() => { setOwnerIgn(owner.minecraftAccount ?? ""); setOwnerIgnOpen(true); }}>Set IGN</Button></Box>
          </Stack>
        </Paper>
      )}
      <List dense>
        {users.map((u: any) => (
          <ListItem key={u.id} divider sx={{ alignItems: "flex-start" }}>
            <Stack spacing={1} sx={{ width: "100%", minWidth: 0 }}>
              <Stack direction="row" spacing={1} sx={{ alignItems: "center", flexWrap: "wrap" }}>
                <Typography sx={{ fontWeight: 600, wordBreak: "break-word" }}>{u.name}</Typography>
                {u.owner && <Chip size="small" label="owner" />}
                {u.disabled && <Chip size="small" color="error" label="disabled" />}
                <Chip size="small" variant="outlined" label={presetNameOf(u.perms)} />
              </Stack>
              {scopeChips(u)}
              <Typography variant="caption" color="text.secondary" sx={{ fontFamily: "monospace", wordBreak: "break-all" }}>
                {u.id} · sessions: {u.sessions ?? 0}
              </Typography>
              <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap" }}>
                <Button size="small" onClick={() => startEdit(u)}>Edit</Button>
                <Button size="small" onClick={() => action(u.id, "logout")}>End sessions</Button>
                <Button size="small" onClick={() => setPwUser(u)}>Reset pw</Button>
                <Button size="small" color="error" onClick={() => action(u.id, "delete")}>Delete</Button>
              </Stack>
            </Stack>
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
                  if (def && e.target.value !== "custom") {
                    const next: Record<string, boolean> = {};
                    for (const f of FLAG_FIELDS) next[f] = !!def[f];
                    setEditFlags(next);
                    setEditStashMode(def.stashes == null ? "all" : "selected");
                    setEditStashes(Array.isArray(def.stashes) ? def.stashes.map((x: any) => String(x?.stash ?? x)) : []);
                    setEditBotMode(def.bots == null ? "all" : "selected");
                    setEditBots(Array.isArray(def.bots) ? def.bots.map(String) : []);
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
            <Divider>Stash scope</Divider>
            <RadioGroup
              row
              aria-label="Stash scope"
              value={editStashMode}
              onChange={(e) => setEditStashMode(e.target.value as "all" | "selected")}
            >
              <FormControlLabel value="all" control={<Radio size="small" />} label="All stashes" />
              <FormControlLabel value="selected" control={<Radio size="small" />} label="Selected stashes" />
            </RadioGroup>
            {editStashMode === "selected" && (
              <Autocomplete
                multiple
                options={stashOptions}
                value={editStashes}
                onChange={(_, v) => { setEditStashes(v); setEditPreset("custom"); }}
                renderInput={(params) => (
                  <TextField
                    {...params}
                    size="small"
                    label="Stashes"
                    helperText="Empty selection means no stash access. Missing stashes can be typed when known."
                  />
                )}
              />
            )}
            <Divider>Bot scope</Divider>
            <RadioGroup
              row
              aria-label="Bot scope"
              value={editBotMode}
              onChange={(e) => setEditBotMode(e.target.value as "all" | "selected")}
            >
              <FormControlLabel value="all" control={<Radio size="small" />} label="All bots" />
              <FormControlLabel value="selected" control={<Radio size="small" />} label="Selected bots" />
            </RadioGroup>
            {editBotMode === "selected" && (
              <Autocomplete
                multiple
                freeSolo
                options={botOptions}
                value={editBots}
                onChange={(_, v) => { setEditBots(v); setEditPreset("custom"); }}
                renderInput={(params) => (
                  <TextField
                    {...params}
                    size="small"
                    label="Bots"
                    helperText="Empty selection means no bot access. Bot IDs can be typed."
                  />
                )}
              />
            )}
            <FormControlLabel
              control={
                <Switch size="small" checked={editDisabled} onChange={(e) => setEditDisabled(e.target.checked)} />
              }
              label={<Typography variant="body2">disabled</Typography>}
            />
            <TextField label="Account name" value={editName} onChange={(e) => setEditName(e.target.value)} size="small" fullWidth />
            <TextField label="Minecraft IGN (optional, empty unlinks)" value={editIgn} onChange={(e) => setEditIgn(e.target.value)} size="small" fullWidth helperText="1–16 chars: letters, digits, _. Used for recipient verification." sx={{ "& .MuiInputBase-input": { fontFamily: "monospace" } }} />
            <Typography variant="caption" color="text.secondary">
              Only changed fields are sent. Scopes are always explicit: All means every stash/bot, an empty
              selection means no access.
            </Typography>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setEditId(null)}>Cancel</Button>
          <Button variant="contained" onClick={() => editId && update(editId, users.find((u: any) => u.id === editId))}>Save</Button>
        </DialogActions>
      </Dialog>
      {confirm && (
        <ConfirmDialog
          open
          title={confirm.title}
          intro={confirm.intro}
          changes={confirm.changes}
          confirmLabel={confirm.confirmLabel}
          danger={confirm.danger}
          busy={confirmBusy}
          error={confirmError}
          onClose={() => { if (!confirmBusy) setConfirm(null); }}
          onConfirm={runConfirm}
        />
      )}
      <PasswordDialog
        open={!!pwUser}
        title={`Reset password for ${pwUser?.name ?? ""}`}
        userName={pwUser?.name}
        minLength={minLength}
        busy={pwBusy}
        error={pwError}
        onClose={() => { if (!pwBusy) setPwUser(null); }}
        onConfirm={resetPassword}
      />
      <Dialog open={ownerIgnOpen} onClose={() => setOwnerIgnOpen(false)} maxWidth="xs" fullWidth>
        <DialogTitle>Owner Minecraft IGN</DialogTitle>
        <DialogContent>
          <Stack spacing={1.5} sx={{ mt: 1 }}>
            <TextField label="Minecraft IGN (optional, empty unlinks)" value={ownerIgn} onChange={(e) => setOwnerIgn(e.target.value)} size="small" fullWidth helperText="1–16 chars: letters, digits, _. Used for recipient verification." sx={{ "& .MuiInputBase-input": { fontFamily: "monospace" } }} />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOwnerIgnOpen(false)}>Cancel</Button>
          <Button variant="contained" onClick={async () => {
            try {
              await api("/api/v1/users/owner/minecraft", { method: "POST", body: { account: ownerIgn.trim() } });
              setMsg({ severity: "success", text: ownerIgn.trim() ? `Owner IGN set to ${ownerIgn.trim()}.` : "Owner IGN unlinked." });
              setOwnerIgnOpen(false);
              await load();
            } catch (e: any) {
              setMsg({ severity: "error", text: e?.message ?? "IGN save failed." });
            }
          }}>Save</Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}

function GuildSection() {
  const auth = useAuth();
  const perms = usePermissions(auth.data);
  const guild = useGuild(!!auth.data?.loggedIn);
  const [name, setName] = useState("");
  const [msg, setMsg] = useState<{ severity: "success" | "error"; text: string } | null>(null);

  async function save() {
    setMsg(null);
    try {
      await api("/api/v1/guild", { method: "POST", body: { name, expectedRevision: (guild.data as any)?.revision ?? 1 } });
      setMsg({ severity: "success", text: "Guild saved." });
      await guild.refetch();
    } catch (e: any) {
      setMsg({ severity: "error", text: e?.message ?? "Save failed." });
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
      {msg && <Alert severity={msg.severity} onClose={() => setMsg(null)}>{msg.text}</Alert>}
    </Stack>
  );
}

function SecuritySection() {
  const auth = useAuth();
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [msg, setMsg] = useState<{ severity: "success" | "error"; text: string } | null>(null);
  const [confirmPw, setConfirmPw] = useState("");
  const elevated = !!auth.data?.loggedIn && (auth.data.elevatedUntil ?? 0) > Date.now();

  async function submit() {
    setMsg(null);
    try {
      await api("/api/v1/auth/password", { method: "POST", body: { current, next } });
      setMsg({ severity: "success", text: "Password changed." });
      setCurrent("");
      setNext("");
    } catch (e: any) {
      setMsg({ severity: "error", text: e?.message ?? "Password change failed." });
    }
  }
  async function confirm() {
    setMsg(null);
    try {
      await api("/api/v1/auth/confirm", { method: "POST", body: { password: confirmPw } });
      setMsg({ severity: "success", text: "Admin unlocked for 15 minutes." });
      setConfirmPw("");
      await auth.refetch();
    } catch (e: any) {
      setMsg({ severity: "error", text: e?.message ?? "Confirm failed." });
    }
  }
  async function lock() {
    try {
      await api("/api/v1/auth/lock", { method: "POST", body: {} });
      await auth.refetch();
      setMsg({ severity: "success", text: "Admin locked." });
    } catch (e: any) {
      setMsg({ severity: "error", text: e?.message ?? "Lock failed." });
    }
  }

  return (
    <Stack spacing={2}>
      <Typography variant="h6" component="h2">Change password</Typography>
      <Stack direction={{ xs: "column", sm: "row" }} spacing={1}>
        <TextField label="Current" type="password" value={current} onChange={(e) => setCurrent(e.target.value)} size="small" />
        <TextField label="New" type="password" value={next} onChange={(e) => setNext(e.target.value)} size="small" />
        <Button variant="outlined" onClick={submit}>Change</Button>
      </Stack>
      <Divider />
      <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
        <Typography variant="h6" component="h2">Admin unlock</Typography>
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
      {msg && <Alert severity={msg.severity} onClose={() => setMsg(null)}>{msg.text}</Alert>}
    </Stack>
  );
}

function ApiKeysSection() {
  const agentsQuery = useQuery({
    queryKey: ["agents"],
    queryFn: () => api<{ slots: any[] }>("/api/v1/agents"),
    staleTime: 10_000,
    retry: 1,
  });
  const slots = agentsQuery.data?.slots ?? [];
  const [label, setLabel] = useState("");
  const [freshKey, setFreshKey] = useState("");
  const [msg, setMsg] = useState<{ severity: "error"; text: string } | null>(null);
  const confirm = useConfirm();
  async function load() {
    const refreshed = await agentsQuery.refetch();
    if (refreshed.error)
      setMsg({ severity: "error", text: refreshed.error.message ?? "API keys unavailable." });
  }
  function errText(e: any): string {
    if (e?.status === 403 && /confirm password/i.test(e?.body?.error ?? "")) {
      return "Confirm your password in the Security tab first, then try again.";
    }
    return e?.message ?? "Request failed.";
  }
  async function issue() {
    try {
      setMsg(null);
      const res = await api<any>("/api/v1/agents", { method: "POST", body: { label: label.trim() || "bot" } });
      setFreshKey(res?.key ?? "");
      setLabel("");
      await load();
    } catch (e: any) {
      setMsg({ severity: "error", text: errText(e) });
    }
  }
  function revoke(slot: any) {
    confirm.ask({
      title: `Revoke API key "${slot.label}"?`,
      intro: "The bot using this key is disconnected immediately and must use a new key.",
      confirmLabel: "Revoke",
      danger: true,
      run: async () => {
        // Keep the confirmation dialog open and show the error if revoke fails.
        try {
          await api(`/api/v1/agents/${slot.id}/revoke`, { method: "POST", body: {} });
        } catch (e: any) {
          throw new Error(errText(e));
        }
        await load();
      },
    });
  }
  return (
    <Stack spacing={1.5}>
      <Typography color="text.secondary">
        The API key authorizes the agent, independently of Minecraft username or UUID.
        The last reported player name is only presence information. A new key is shown once.
      </Typography>
      {msg && <Alert severity={msg.severity} onClose={() => setMsg(null)}>{msg.text}</Alert>}
      {agentsQuery.isError && <Alert severity="error">Cannot refresh agent credentials.</Alert>}
      {freshKey && (
        <Paper variant="outlined" sx={{ p: 1.5 }}>
          <Typography variant="subtitle2">New API key (copy it now — it is never shown again):</Typography>
          <Typography variant="caption" component="pre" sx={{ fontFamily: "monospace", whiteSpace: "pre-wrap", m: 0 }}>
            {freshKey}
          </Typography>
          <Box><Button variant="outlined" onClick={() => setFreshKey("")}>I saved it</Button></Box>
        </Paper>
      )}
      <Stack direction="row" spacing={1}>
        <TextField size="small" label="Label" value={label} onChange={(e) => setLabel(e.target.value)} placeholder="bot" />
        <Button variant="contained" onClick={issue}>Issue key</Button>
      </Stack>
      <List>
        {slots.map((s) => (
          <ListItem key={s.id} divider>
            <Stack spacing={0.5} sx={{ flex: 1 }}>
              <Typography>
                {s.label} <Typography variant="caption" component="span" color="text.secondary">{s.id}</Typography>
                {!s.active && <Chip size="small" label="revoked" sx={{ ml: 1 }} />}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                {s.bound ? `player: ${s.bound.name} (seen ${new Date(s.bound.seen).toLocaleString()})` : "no active player reported"}
              </Typography>
            </Stack>
            {s.active && <Button variant="outlined" color="error" onClick={() => revoke(s)}>Revoke</Button>}
          </ListItem>
        ))}
      </List>
      {confirm.dialog}
    </Stack>
  );
}

function LinkSection() {
  const [link, setLink] = useState<any | null>(null);
  const [msg, setMsg] = useState("");
  const confirm = useConfirm();
  async function load() {
    try {
      setLink(await api("/api/v1/link"));
    } catch (e: any) {
      setMsg(e?.message ?? "Link panel unavailable.");
    }
  }
  useEffect(() => { void load(); }, []);
  function regenerate() {
    confirm.ask({
      title: "Regenerate the addon API key?",
      intro: "Connected bots must reconnect with the new key. The current key stops working immediately.",
      confirmLabel: "Regenerate",
      danger: true,
      run: async () => {
        await api("/api/v1/link/regenerate", { method: "POST", body: {} });
        await load();
      },
    });
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
      {confirm.dialog}
    </Stack>
  );
}
