import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import CardHeader from "@mui/material/CardHeader";
import Chip from "@mui/material/Chip";
import Grid from "@mui/material/Grid";
import LinearProgress from "@mui/material/LinearProgress";
import List from "@mui/material/List";
import ListItem from "@mui/material/ListItem";
import ListItemButton from "@mui/material/ListItemButton";
import ListItemText from "@mui/material/ListItemText";
import Paper from "@mui/material/Paper";
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
      <Typography variant="h1">Bots</Typography>
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
  const settings = useBotSettings(botId, loggedIn && canPosition);
  const [changes, setChanges] = useState("");
  const [msg, setMsg] = useState("");

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
        <Card>
          <CardHeader title="Settings" subheader="scope=plugin — JSON changes are sent to the bot" />
          <CardContent>
            <Stack spacing={1.5}>
              {settings.isLoading && <LinearProgress />}
              {settings.isError && <Typography color="text.secondary">No settings from this bot yet (or not permitted).</Typography>}
              {settings.data && (
                <Paper variant="outlined" sx={{ p: 1.5, maxHeight: 240, overflow: "auto" }}>
                  <Typography variant="caption" component="pre" sx={{ fontFamily: "monospace", whiteSpace: "pre-wrap" }}>
                    {JSON.stringify({ schema: settings.data.schema, values: settings.data.values }, null, 1)}
                  </Typography>
                </Paper>
              )}
              <TextField
                label='Changes (JSON list of {path, value, expect})' value={changes}
                onChange={(e) => setChanges(e.target.value)} size="small" multiline rows={2}
                sx={{ "& .MuiInputBase-input": { fontFamily: "monospace" } }}
              />
              {msg && <Alert severity={msg.startsWith("Settings sent") ? "success" : "info"}>{msg}</Alert>}
              <Box><Button variant="outlined" onClick={saveSettings}>Send to bot</Button></Box>
            </Stack>
          </CardContent>
        </Card>
      )}
    </Stack>
  );
}
