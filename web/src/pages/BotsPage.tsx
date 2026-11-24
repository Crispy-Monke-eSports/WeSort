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
import {
  useAuth,
  useAllOrders,
  useBotActivity,
  useBotSettings,
  useBots,
  useOperations,
  usePermissions,
  usePhysicalOps,
  useStashes,
} from "../api/queries";
import { orderUiState } from "../lib/orderStatus";
import {
  agentServerMismatch,
  journalDisplayState,
  orderHeldBy,
  silentBots,
} from "../lib/attention";
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

  // Server-side silence watchdog (same rule as Overview): online transport but
  // no progress on open work, judged by the server clock and the order table.
  const canView = perms.can("view");
  const stashes = useStashes(loggedIn && canView);
  const stashRefs = (stashes.data ?? []).filter((s) =>
    perms.seesStash(s.stash),
  );
  const { orders } = useAllOrders(stashRefs, loggedIn && canView);
  const ops = useOperations(loggedIn);
  const serverNow =
    typeof (ops.data as any)?.at === "number"
      ? (ops.data as any).at
      : Date.now();
  const openOrders = orders.filter(
    (o) => orderUiState(String(o.status ?? "unknown")).open,
  );
  const silent = silentBots(list, openOrders, serverNow);
  const silentByBot = new Map(silent.map((s) => [s.bot.botId, s]));

  useEffect(() => {
    if (!selected && list.length) setSelected(list[0].botId);
  }, [bots.data, selected]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <Stack spacing={2}>
      <Typography variant="h5" component="h1">
        Bots
      </Typography>
      {bots.isError && <Alert severity="error">Could not load bots.</Alert>}
      <Grid container spacing={2}>
        <Grid size={{ xs: 12, md: 4, lg: 3 }}>
          <Card>
            <CardHeader
              title={`Agents (${list.length})`}
              subheader="Signed Minecraft agents"
            />
            <CardContent sx={{ pt: 0 }}>
              {bots.isLoading && <LinearProgress />}
              <List dense disablePadding>
                {list.map((b) => (
                  <ListItem key={b.botId} disablePadding>
                    <ListItemButton
                      selected={sel?.botId === b.botId}
                      onClick={() => setSelected(b.botId)}
                    >
                      <ListItemText
                        primary={b.player ?? b.botId}
                        secondary={`${b.online ? "online" : "offline"} · seen ${timeAgo(b.lastSeen ?? b.activityAt)}${b.summary ? ` · ${b.summary}` : ""}${botServerTag(b) ? ` · ${botServerTag(b)}` : ""}`}
                      />
                      {silentByBot.has(b.botId) ? (
                        <Chip size="small" color="error" label="silent" />
                      ) : (
                        <PresenceChip online={b.online} />
                      )}
                    </ListItemButton>
                  </ListItem>
                ))}
              </List>
              {!bots.isLoading && list.length === 0 && (
                <Typography color="text.secondary">
                  No bots reported yet.
                </Typography>
              )}
            </CardContent>
          </Card>
        </Grid>
        <Grid size={{ xs: 12, md: 8, lg: 9 }}>
          {sel ? (
            <BotDetails
              botId={sel.botId}
              canPosition={canPosition}
              serverNow={serverNow}
              heldOrders={openOrders.filter((o) => orderHeldBy(o, sel))}
              silentMs={silentByBot.get(sel.botId)?.silentMs ?? null}
              mismatch={
                !silentByBot.has(sel.botId) &&
                agentServerMismatch(sel, openOrders)
              }
            />
          ) : (
            <Card>
              <CardContent>
                <Typography color="text.secondary">
                  Select a bot to inspect activity and settings.
                </Typography>
              </CardContent>
            </Card>
          )}
        </Grid>
      </Grid>
    </Stack>
  );
}

/**
 * Manager-side state shown next to the agent-reported summary. The
 * joint-health branch sends per-bot managerState/inspectionState/cause;
 * older servers omit them and the tag stays empty (fallback: journal table).
 */
function botServerTag(b: {
  managerState?: string | null;
  inspectionState?: string | null;
  cause?: string | null;
}): string | null {
  const parts: string[] = [];
  if (b.managerState)
    parts.push(
      journalDisplayState({
        managerState: b.managerState,
        inspectionState: b.inspectionState,
      }),
    );
  else if (b.inspectionState) parts.push(`inspection:${b.inspectionState}`);
  if (b.cause) parts.push(b.cause);
  return parts.length ? parts.join(" · ") : null;
}

/** Short age ("3m") for a millisecond duration; "—" when unknown. */
export function ageText(ms: number | null | undefined): string {
  if (ms == null || !Number.isFinite(ms) || ms < 0) return "—";
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s}s`;
  if (s < 3600) return `${Math.floor(s / 60)}m`;
  if (s < 86400) return `${Math.floor(s / 3600)}h`;
  return `${Math.floor(s / 86400)}d`;
}

function BotDetails({
  botId,
  canPosition,
  serverNow,
  heldOrders,
  silentMs,
  mismatch,
}: {
  botId: string;
  canPosition: boolean;
  serverNow: number;
  heldOrders: {
    id: string;
    stash: string;
    status: string;
    updatedAt?: number;
  }[];
  silentMs: number | null;
  mismatch: boolean;
}) {
  const auth = useAuth();
  const perms = usePermissions(auth.data);
  const loggedIn = !!auth.data?.loggedIn && perms.can("activity");
  const owner = !!auth.data?.loggedIn && perms.owner;
  const bots = useBots(loggedIn);
  const bot = bots.data?.find((b) => b.botId === botId);
  const activity = useBotActivity(botId, loggedIn);
  const physical = usePhysicalOps(owner);
  const events: any[] = Array.isArray(activity.data?.events)
    ? activity.data.events
    : [];
  const current: any[] = Array.isArray(
    (activity.data?.activity as any)?.current,
  )
    ? (activity.data?.activity as any).current
    : [];
  // Manager-side journal/lease rows for this bot (owner-only endpoint).
  const journal: any[] = Array.isArray((physical.data as any)?.operations)
    ? (physical.data as any).operations.filter((op: any) => op?.agent === botId)
    : [];
  const botLeaseAgeMs =
    typeof bot?.leaseAgeMs === "number" && bot.leaseAgeMs >= 0
      ? bot.leaseAgeMs
      : typeof bot?.leaseSince === "number" && bot.leaseSince > 0
        ? Math.max(0, serverNow - bot.leaseSince)
        : null;
  const fingerprint =
    bot?.currentLeaseFingerprint ?? bot?.leaseFingerprint ?? null;

  return (
    <Stack spacing={2}>
      {silentMs != null && (
        <Alert severity="error">
          Silent with open work: no progress on {heldOrders.length} order
          {heldOrders.length === 1 ? "" : "s"} for {ageText(silentMs)} by the
          server clock. Transport is online — the bot looks frozen.
        </Alert>
      )}
      {silentMs == null && mismatch && (
        <Alert severity="warning">
          The agent reports “{bot?.summary}” but the server still holds{" "}
          {heldOrders.length} open order
          {heldOrders.length === 1 ? "" : "s"} against it — the summary cannot
          be trusted.
        </Alert>
      )}
      <Card>
        <CardHeader
          title={bot?.player ?? botId}
          subheader={
            <Typography variant="caption" sx={{ fontFamily: "monospace" }}>
              {botId}
            </Typography>
          }
          action={<PresenceChip online={bot?.online} />}
        />
        <CardContent sx={{ pt: 0, display: "flex", gap: 1, flexWrap: "wrap" }}>
          {(bot?.caps ?? []).map((c) => (
            <Chip key={c} size="small" label={c} />
          ))}
          {(bot?.scopes ?? []).map((s) => (
            <Chip key={s} size="small" variant="outlined" label={s} />
          ))}
          <Typography
            variant="body2"
            color="text.secondary"
            sx={{ width: "100%" }}
          >
            Last seen {timeAgo(bot?.lastSeen ?? bot?.activityAt)}
            {canPosition && bot?.lastLocation
              ? ""
              : canPosition
                ? ""
                : " · positions hidden (no positions permission)"}
          </Typography>
          <Typography
            variant="body2"
            color="text.secondary"
            sx={{ width: "100%" }}
          >
            Server view:{" "}
            {botServerTag(bot ?? {}) ?? "no Manager state reported"}
            {botLeaseAgeMs != null && ` · lease age ${ageText(botLeaseAgeMs)}`}
            {typeof fingerprint === "string" && fingerprint
              ? ` · lease ${fingerprint.slice(0, 8)}…`
              : ""}
            {heldOrders.length > 0 &&
              ` · ${heldOrders.length} open order${heldOrders.length === 1 ? "" : "s"} held`}
          </Typography>
        </CardContent>
      </Card>

      <Card>
        <CardHeader
          title="Current activity"
          subheader={
            activity.data
              ? `Received ${timeAgo(activity.data.receivedAt)}`
              : undefined
          }
        />
        <CardContent sx={{ pt: 0 }}>
          {activity.isLoading && <LinearProgress />}
          {activity.isError && (
            <Alert severity="error">Activity unavailable.</Alert>
          )}
          {current.length === 0 && !activity.isLoading && (
            <Typography color="text.secondary">
              No running tasks reported.
            </Typography>
          )}
          <TableContainer>
            <Table size="small" aria-label="Current bot tasks">
              <TableHead>
                <TableRow>
                  <TableCell>Source</TableCell>
                  <TableCell>Task</TableCell>
                  <TableCell>Detail</TableCell>
                  <TableCell>Progress</TableCell>
                  <TableCell>Level</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {current.map((c: any, i: number) => (
                  <TableRow key={i}>
                    <TableCell>{c.source}</TableCell>
                    <TableCell>{c.title}</TableCell>
                    <TableCell>{c.detail ?? c.text ?? "—"}</TableCell>
                    <TableCell>
                      {c.progress
                        ? `${c.progress.done ?? 0}/${c.progress.total ?? "?"}`
                        : "—"}
                    </TableCell>
                    <TableCell>
                      <Chip
                        size="small"
                        color={
                          c.level === "error"
                            ? "error"
                            : c.level === "warn"
                              ? "warning"
                              : "default"
                        }
                        label={c.level ?? "info"}
                      />
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        </CardContent>
      </Card>

      <Card>
        <CardHeader
          title="Server view — journal & leases"
          subheader="Manager state, not agent claims"
        />
        <CardContent sx={{ pt: 0 }}>
          {!owner && (
            <Typography color="text.secondary" sx={{ mb: 1 }}>
              Journal and lease detail needs the owner permission; the line
              under the bot name shows what the bot itself reports.
            </Typography>
          )}
          {owner && physical.isLoading && <LinearProgress />}
          {owner && physical.isError && (
            <Alert severity="error">Journal unavailable.</Alert>
          )}
          {owner && !physical.isLoading && !physical.isError && (
            <TableContainer>
              <Table size="small" aria-label="Server journal and leases">
                <TableHead>
                  <TableRow>
                    <TableCell>Journal</TableCell>
                    <TableCell>State</TableCell>
                    <TableCell>Manager</TableCell>
                    <TableCell>Inspection</TableCell>
                    <TableCell>Lease age</TableCell>
                    <TableCell>Order</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {journal.map((op: any, i: number) => (
                    <TableRow key={String(op?.id ?? i)}>
                      <TableCell>{String(op?.id ?? "—").slice(0, 8)}</TableCell>
                      <TableCell>
                        <Chip
                          size="small"
                          color={
                            journalDisplayState(op) === "READY"
                              ? "success"
                              : journalDisplayState(op) === "AUTO_RECOVERING"
                                ? "warning"
                                : "default"
                          }
                          label={journalDisplayState(op)}
                        />
                      </TableCell>
                      <TableCell>{String(op?.managerState ?? "—")}</TableCell>
                      <TableCell>
                        {String(
                          op?.inspectionState ?? op?.inspectionDetail ?? "—",
                        )}
                      </TableCell>
                      <TableCell>
                        {typeof op?.lastSeen === "number" && op.lastSeen > 0
                          ? ageText(Math.max(0, serverNow - op.lastSeen))
                          : "—"}
                      </TableCell>
                      <TableCell>
                        {String(op?.orderId ?? op?.jobId ?? "—")}
                      </TableCell>
                    </TableRow>
                  ))}
                  {journal.length === 0 && (
                    <TableRow>
                      <TableCell colSpan={6}>
                        <Typography color="text.secondary">
                          No journal entries for this bot — nothing held
                          server-side.
                        </Typography>
                      </TableCell>
                    </TableRow>
                  )}
                </TableBody>
              </Table>
            </TableContainer>
          )}
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
            {events.length === 0 && (
              <ListItem>
                <ListItemText primary="No events." />
              </ListItem>
            )}
          </List>
        </CardContent>
      </Card>

      {canPosition && <BotSettingsCard botId={botId} />}
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
  const [draft, setDraft] = useState<Record<string, number | boolean | string>>(
    {},
  );
  const [msg, setMsg] = useState("");
  const [sending, setSending] = useState(false);
  const confirm = useConfirm();

  const schema: any[] = Array.isArray((settings.data as any)?.schema)
    ? (settings.data as any).schema
    : [];
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
      intro:
        "Each value is applied on the bot with an expected-value check; mismatches are rejected.",
      changes: changesPreview(),
      confirmLabel: "Send",
      run: async () => {
        setSending(true);
        setMsg("");
        try {
          await api(`/api/v1/bots/${encodeURIComponent(botId)}/settings`, {
            method: "POST",
            body: {
              scope: "plugin",
              changes: diff.map((d) => ({
                path: d.s.path,
                value: d.to,
                expect: d.from ?? null,
              })),
            },
          });
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
    return diff.map(
      (d) =>
        `${d.s.path}: ${JSON.stringify(d.from ?? null)} → ${JSON.stringify(d.to)}`,
    );
  }

  return (
    <Card>
      <CardHeader
        title="Settings"
        subheader="scope=plugin — typed from the bot's own schema"
      />
      <CardContent>
        <Stack spacing={1.5}>
          {settings.isLoading && (
            <>
              <LinearProgress />
              <Typography color="text.secondary">
                Asking the bot for its settings…
              </Typography>
            </>
          )}
          {settings.isError && errStatus === 403 && (
            <Alert severity="warning">
              Changing bot settings needs the positions permission.
            </Alert>
          )}
          {settings.isError && errStatus !== 403 && (
            <Alert severity="info">
              No settings from this bot right now — it may be offline or never
              reported any. The panel retries automatically.
            </Alert>
          )}
          {settings.data && schema.length === 0 && (
            <Typography color="text.secondary">
              The bot reported no adjustable settings.
            </Typography>
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
                      onChange={(e) =>
                        setDraft({ ...draft, [s.path]: e.target.checked })
                      }
                    />
                  }
                  label={label}
                />
              );
            }
            if (s.type === "int" || s.type === "double") {
              const v = draft[s.path];
              const bad =
                typeof v !== "number" ||
                !Number.isFinite(v) ||
                (s.type === "int" && !Number.isInteger(v)) ||
                (typeof s.min === "number" && v < s.min) ||
                (typeof s.max === "number" && v > s.max);
              return (
                <TextField
                  key={String(s.path)}
                  label={label}
                  type="number"
                  value={v ?? ""}
                  size="small"
                  error={bad}
                  helperText={
                    bad
                      ? `${s.type === "int" ? "Integer" : "Number"}${typeof s.min === "number" ? ` ≥ ${s.min}` : ""}${typeof s.max === "number" ? ` ≤ ${s.max}` : ""} required`
                      : undefined
                  }
                  slotProps={{
                    htmlInput: {
                      step: s.type === "int" ? 1 : "any",
                      min: s.min,
                      max: s.max,
                    },
                  }}
                  onChange={(e) =>
                    setDraft({
                      ...draft,
                      [s.path]:
                        e.target.value === "" ? NaN : Number(e.target.value),
                    })
                  }
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
                  onChange={(e) =>
                    setDraft({ ...draft, [s.path]: e.target.value })
                  }
                >
                  {s.options.map((option: string) => (
                    <MenuItem key={option} value={option}>
                      {option}
                    </MenuItem>
                  ))}
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
                  onChange={(e) =>
                    setDraft({ ...draft, [s.path]: e.target.value })
                  }
                />
              );
            }
            return (
              <Typography
                key={String(s.path)}
                variant="body2"
                color="text.secondary"
              >
                {label}: {JSON.stringify(values[s.path])} (read-only type “
                {String(s.type)}”)
              </Typography>
            );
          })}
          {diff.length > 0 && (
            <Alert severity="info">
              {diff.length} change{diff.length === 1 ? "" : "s"}:{" "}
              {diff
                .map(
                  (d) =>
                    `${d.s.path} ${JSON.stringify(d.from)} → ${JSON.stringify(d.to)}`,
                )
                .join("; ")}
            </Alert>
          )}
          {msg && (
            <Alert
              severity={msg.startsWith("Settings sent") ? "success" : "info"}
              onClose={() => setMsg("")}
            >
              {msg}
            </Alert>
          )}
          <Box>
            <Button
              variant="outlined"
              disabled={!diff.length || sending}
              onClick={send}
            >
              {sending ? "Sending…" : "Send to bot"}
            </Button>
          </Box>
        </Stack>
      </CardContent>
      {confirm.dialog}
    </Card>
  );
}
