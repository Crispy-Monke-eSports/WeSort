import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import CardHeader from "@mui/material/CardHeader";
import Chip from "@mui/material/Chip";
import Grid from "@mui/material/Grid";
import LinearProgress from "@mui/material/LinearProgress";
import MenuItem from "@mui/material/MenuItem";
import List from "@mui/material/List";
import ListItem from "@mui/material/ListItem";
import ListItemButton from "@mui/material/ListItemButton";
import ListItemText from "@mui/material/ListItemText";
import FormControlLabel from "@mui/material/FormControlLabel";
import Switch from "@mui/material/Switch";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { useEffect, useState } from "react";
import { useConfirm } from "../components/dialogs";
import { api } from "../api/client";
import { useAuth, useBotActivity, useBotSettings, useBots, usePermissions } from "../api/queries";
import { PresenceChip, timeAgo } from "../components/status";

/**
 * Bots: monitoring and control. Identity, online state, heartbeat, activity,
 * capabilities, recent events and owner actions. Coordinates are hidden without
 * the positions permission (enforced server-side; the UI additionally gates).
 */
export function BotsPage() {
  const auth = useAuth();
  const perms = usePermissions(auth.data);
  const loggedIn = !!auth.data?.loggedIn && perms.can("activity");
  const bots = useBots(loggedIn);
  const canPosition = perms.can("positions");
  const [selected, setSelected] = useState<string | null>(null);

  const list = (bots.data ?? []).filter((b) => perms.seesBot(b.botId));
  const sel = list.find((b) => b.botId === selected) ?? list[0] ?? null;

  useEffect(() => {
    if (!selected && list.length) setSelected(list[0].botId);
  }, [bots.data, selected]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <Stack spacing={2}>
      <Typography variant="h5" component="h1">Bots</Typography>
      {bots.isError && <Alert severity="error">Could not load bots.</Alert>}
      <Grid container spacing={2}>
        <Grid size={{ xs: 12, md: 4, lg: 3 }}>
          <Card>
            <CardHeader title={`Agents (${list.length})`} subheader="Signed Minecraft agents" />
            <CardContent sx={{ pt: 0 }}>
              {bots.isLoading && <LinearProgress />}
              <List dense disablePadding>
                {list.map((b) => (
                  <ListItem key={b.botId} disablePadding>
                    <ListItemButton selected={sel?.botId === b.botId} onClick={() => setSelected(b.botId)}>
                      <ListItemText
                        primary={b.player ?? b.botId}
                        secondary={`${b.online ? "online" : "offline"} · seen ${timeAgo(b.lastSeen ?? b.activityAt)}${b.summary ? ` · ${b.summary}` : ""}`}
                      />
                      <PresenceChip online={b.online} />
                    </ListItemButton>
                  </ListItem>
                ))}
              </List>
              {!bots.isLoading && list.length === 0 && (
                <Typography color="text.secondary">No bots reported yet.</Typography>
              )}
            </CardContent>
          </Card>
        </Grid>
        <Grid size={{ xs: 12, md: 8, lg: 9 }}>
          {sel ? (
            <BotDetails botId={sel.botId} canPosition={canPosition} />
          ) : (
            <Card><CardContent><Typography color="text.secondary">Select a bot to inspect activity and settings.</Typography></CardContent></Card>
          )}
        </Grid>
      </Grid>
    </Stack>
  );
}

function BotDetails({ botId, canPosition }: { botId: string; canPosition: boolean }) {
  const auth = useAuth();
  const perms = usePermissions(auth.data);
  const loggedIn = !!auth.data?.loggedIn && perms.can("activity");
  const bots = useBots(loggedIn);
  const bot = bots.data?.find((b) => b.botId === botId);
  const activity = useBotActivity(botId, loggedIn);
  const events: any[] = Array.isArray(activity.data?.events) ? activity.data.events : [];
  const current: any[] = Array.isArray((activity.data?.activity as any)?.current) ? (activity.data?.activity as any).current : [];

  return (
    <Stack spacing={2}>
      <Card>
        <CardHeader
          title={bot?.player ?? botId}
          subheader={<Typography variant="caption" sx={{ fontFamily: "monospace" }}>{botId}</Typography>}
          action={<PresenceChip online={bot?.online} />}
        />
        <CardContent sx={{ pt: 0, display: "flex", gap: 1, flexWrap: "wrap" }}>
          {(bot?.caps ?? []).map((c) => <Chip key={c} size="small" label={c} />)}
          {(bot?.scopes ?? []).map((s) => <Chip key={s} size="small" variant="outlined" label={s} />)}
          <Typography variant="body2" color="text.secondary" sx={{ width: "100%" }}>
            Last seen {timeAgo(bot?.lastSeen ?? bot?.activityAt)}
            {canPosition && bot?.lastLocation ? "" : canPosition ? "" : " · positions hidden (no positions permission)"}
          </Typography>
        </CardContent>
      </Card>

      <Card>
        <CardHeader title="Current activity" subheader={activity.data ? `Received ${timeAgo(activity.data.receivedAt)}` : undefined} />
        <CardContent sx={{ pt: 0 }}>
          {activity.isLoading && <LinearProgress />}
          {activity.isError && <Alert severity="error">Activity unavailable.</Alert>}
          {current.length === 0 && !activity.isLoading && (
            <Typography color="text.secondary">No running tasks reported.</Typography>
          )}
          <TableContainer>
            <Table size="small" aria-label="Current bot tasks">
              <TableHead>
                <TableRow><TableCell>Source</TableCell><TableCell>Task</TableCell><TableCell>Detail</TableCell><TableCell>Progress</TableCell><TableCell>Level</TableCell></TableRow>
              </TableHead>
              <TableBody>
                {current.map((c: any, i: number) => (
                  <TableRow key={i}>
                    <TableCell>{c.source}</TableCell>
                    <TableCell>{c.title}</TableCell>
                    <TableCell>{c.detail ?? c.text ?? "—"}</TableCell>
                    <TableCell>{c.progress ? `${c.progress.done ?? 0}/${c.progress.total ?? "?"}` : "—"}</TableCell>
                    <TableCell>
                      <Chip size="small" color={c.level === "error" ? "error" : c.level === "warn" ? "warning" : "default"} label={c.level ?? "info"} />
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        </CardContent>
      </Card>

      <Card>
        <CardHeader title="Recent events" />
        <CardContent sx={{ pt: 0 }}>
          <List dense>
            {events.slice(0, 20).map((e: any, i: number) => (
              <ListItem key={i} disablePadding>
                <ListItemText
                  primary={e.text ?? JSON.stringify(e)}
                  secondary={`${e.source ?? ""} · ${e.level ?? "info"} · ${e.at ? new Date(e.at).toLocaleString() : ""}`}
                />
              </ListItem>
            ))}
            {events.length === 0 && <ListItem><ListItemText primary="No events." /></ListItem>}
          </List>
        </CardContent>
      </Card>

      {canPosition && (
        <BotSettingsCard botId={botId} />
      )}
    </Stack>
  );
}

/**
 * Typed bot settings form generated from the bot's own schema
 * ([{path, label, type: int|bool, min?, max?}] + values). Loading, offline,
 * forbidden and empty-schema states are separated; changes show a diff and
 * need explicit confirm before they are sent with expected values.
 */
function BotSettingsCard({ botId }: { botId: string }) {
  const auth = useAuth();
  const perms = usePermissions(auth.data);
  const loggedIn = !!auth.data?.loggedIn && perms.can("activity");
  const settings = useBotSettings(botId, loggedIn && perms.can("positions"));
  const [draft, setDraft] = useState<Record<string, number | boolean | string>>({});
  const [msg, setMsg] = useState("");
  const [sending, setSending] = useState(false);
  const confirm = useConfirm();

  const schema: any[] = Array.isArray((settings.data as any)?.schema) ? (settings.data as any).schema : [];
  const values: Record<string, any> = (settings.data as any)?.values ?? {};
  const errStatus = (settings.error as any)?.status as number | undefined;

  useEffect(() => {
    const next: Record<string, number | boolean | string> = {};
    for (const s of schema) {
      const v = values[s.path];
      if (s.type === "bool") next[s.path] = !!v;
      else if (typeof v === "number" || typeof v === "string") next[s.path] = v;
    }
    setDraft(next);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [botId, (settings.data as any)?.valuesHash]);

  const diff = schema
    .map((s) => ({ s, from: values[s.path], to: draft[s.path] }))
    .filter((d) => d.to !== undefined && d.from !== d.to);

  function send() {
    if (!diff.length || sending) return;
    confirm.ask({
      title: `Send ${diff.length} setting${diff.length === 1 ? "" : "s"} to ${botId}?`,
      intro: "Each value is applied on the bot with an expected-value check; mismatches are rejected.",
      changes: changesPreview(),
      confirmLabel: "Send",
      run: async () => {
        setSending(true);
        setMsg("");
        try {
          await api(`/api/v1/bots/${encodeURIComponent(botId)}/settings`, { method: "POST", body: { scope: "plugin", changes: diff.map((d) => ({ path: d.s.path, value: d.to, expect: d.from ?? null })) } });
          setMsg("Settings sent to the bot.");
          settings.refetch();
        } catch (e: any) {
          setMsg(e?.message ?? "Could not update settings.");
          throw e;
        } finally {
          setSending(false);
        }
      },
    });
  }

  function changesPreview(): string[] {
    return diff.map((d) => `${d.s.path}: ${JSON.stringify(d.from ?? null)} → ${JSON.stringify(d.to)}`);
  }

  return (
    <Card>
      <CardHeader title="Settings" subheader="scope=plugin — typed from the bot's own schema" />
      <CardContent>
        <Stack spacing={1.5}>
          {settings.isLoading && (
            <>
              <LinearProgress />
              <Typography color="text.secondary">Asking the bot for its settings…</Typography>
            </>
          )}
          {settings.isError && errStatus === 403 && (
            <Alert severity="warning">Changing bot settings needs the positions permission.</Alert>
          )}
          {settings.isError && errStatus !== 403 && (
            <Alert severity="info">
              No settings from this bot right now — it may be offline or never reported any.
              The panel retries automatically.
            </Alert>
          )}
          {settings.data && schema.length === 0 && (
            <Typography color="text.secondary">The bot reported no adjustable settings.</Typography>
          )}
          {schema.map((s: any) => {
            const label = String(s.label ?? s.path);
            if (s.type === "bool") {
              return (
                <FormControlLabel
                  key={String(s.path)}
                  control={
                    <Switch
                      checked={!!draft[s.path]}
                      onChange={(e) => setDraft({ ...draft, [s.path]: e.target.checked })}
                    />
                  }
                  label={label}
                />
              );
            }
            if (s.type === "int" || s.type === "double") {
              const v = draft[s.path];
              const bad = typeof v !== "number" || !Number.isFinite(v) || (s.type === "int" && !Number.isInteger(v))
                || (typeof s.min === "number" && v < s.min)
                || (typeof s.max === "number" && v > s.max);
              return (
                <TextField
                  key={String(s.path)}
                  label={label}
                  type="number"
                  value={v ?? ""}
                  size="small"
                  error={bad}
                  helperText={bad ? `${s.type === "int" ? "Integer" : "Number"}${typeof s.min === "number" ? ` ≥ ${s.min}` : ""}${typeof s.max === "number" ? ` ≤ ${s.max}` : ""} required` : undefined}
                  slotProps={{ htmlInput: { step: s.type === "int" ? 1 : "any", min: s.min, max: s.max } }}
                  onChange={(e) => setDraft({ ...draft, [s.path]: e.target.value === "" ? NaN : Number(e.target.value) })}
                />
              );
            }
            if (s.type === "enum" && Array.isArray(s.options)) {
              return (
                <TextField
                  key={String(s.path)}
                  select
                  size="small"
                  label={label}
                  value={draft[s.path] ?? ""}
                  onChange={(e) => setDraft({ ...draft, [s.path]: e.target.value })}
                >
                  {s.options.map((option: string) => <MenuItem key={option} value={option}>{option}</MenuItem>)}
                </TextField>
              );
            }
            if (s.type === "string" && !s.secret) {
              return (
                <TextField
                  key={String(s.path)}
                  size="small"
                  label={label}
                  value={draft[s.path] ?? ""}
                  onChange={(e) => setDraft({ ...draft, [s.path]: e.target.value })}
                />
              );
            }
            return (
              <Typography key={String(s.path)} variant="body2" color="text.secondary">
                {label}: {JSON.stringify(values[s.path])} (read-only type “{String(s.type)}”)
              </Typography>
            );
          })}
          {diff.length > 0 && (
            <Alert severity="info">
              {diff.length} change{diff.length === 1 ? "" : "s"}: {diff.map((d) => `${d.s.path} ${JSON.stringify(d.from)} → ${JSON.stringify(d.to)}`).join("; ")}
            </Alert>
          )}
          {msg && <Alert severity={msg.startsWith("Settings sent") ? "success" : "info"} onClose={() => setMsg("")}>{msg}</Alert>}
          <Box>
            <Button variant="outlined" disabled={!diff.length || sending} onClick={send}>
              {sending ? "Sending…" : "Send to bot"}
            </Button>
          </Box>
        </Stack>
      </CardContent>
      {confirm.dialog}
    </Card>
  );
}
