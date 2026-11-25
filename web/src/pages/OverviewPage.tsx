import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import CardHeader from "@mui/material/CardHeader";
import Chip from "@mui/material/Chip";
import CircularProgress from "@mui/material/CircularProgress";
import ErrorIcon from "@mui/icons-material/Error";
import FormControlLabel from "@mui/material/FormControlLabel";
import Grid from "@mui/material/Grid";
import LinearProgress from "@mui/material/LinearProgress";
import Link from "@mui/material/Link";
import List from "@mui/material/List";
import ListItem from "@mui/material/ListItem";
import ListItemIcon from "@mui/material/ListItemIcon";
import ListItemText from "@mui/material/ListItemText";
import Stack from "@mui/material/Stack";
import Switch from "@mui/material/Switch";
import Typography from "@mui/material/Typography";
import { useTheme } from "@mui/material/styles";
import WarningAmberIcon from "@mui/icons-material/WarningAmber";
import { Link as RouterLink } from "@tanstack/react-router";
import { useState } from "react";
import {
  useAuth,
  useBots,
  useHealth,
  useOperations,
  usePermissions,
  useServer,
  useStashes,
  useAllOrders,
} from "../api/queries";
import {
  isSuperseded,
  needsAttentionNow,
  orderUiState,
} from "../lib/orderStatus";
import {
  agentServerMismatch,
  alertCount,
  classifyStaleAlert,
  failedStashItems,
  healthAttention,
  isHealthFresh,
  silentBots,
  visibleWithOverflow,
  withAlertCount,
  type AttentionItem,
} from "../lib/attention";
import { OrderStatusChip, PresenceChip, timeAgo } from "../components/status";

/** One actionable problem, backend-derived, with a direct link to resolve it. */
type Attention = AttentionItem;

/**
 * Attention rows link to a page, optionally with a router `search` filter
 * (e.g. Orders `?q=`). MUI's Link overloads reject the extra search prop, so
 * search-bearing rows render the router link directly in the theme's link
 * color while plain rows use the MUI wrapper.
 */
function AttentionLink({ item }: { item: Attention }) {
  const theme = useTheme();
  if (item.search) {
    return (
      <RouterLink
        to={item.to}
        search={item.search}
        style={{ color: theme.palette.primary.main }}
      >
        {item.text}
      </RouterLink>
    );
  }
  return (
    <Link component={RouterLink} to={item.to} underline="hover">
      {item.text}
    </Link>
  );
}

/**
 * Overview: the system's control center. Answers "does the system work, what are the
 * bots doing, what is happening with orders, is anything blocked" from real backend data.
 */
export function OverviewPage() {
  const auth = useAuth();
  const perms = usePermissions(auth.data);
  const loggedIn = !!auth.data?.loggedIn && perms.can("view");
  const canActivity = loggedIn && (perms.can("activity") || perms.owner);
  const bots = useBots(canActivity);
  const stashes = useStashes(loggedIn);
  const server = useServer(loggedIn);
  const health = useHealth(loggedIn);
  const ops = useOperations(canActivity);
  const {
    orders,
    failedStashes,
    loading: ordersLoading,
  } = useAllOrders(
    (stashes.data ?? []).filter((s) => perms.seesStash(s.stash)),
    loggedIn,
  );
  // Hooks must run in the same order, including the initial unauthenticated render.
  const [showResolved, setShowResolved] = useState(false);
  const [ordersExpanded, setOrdersExpanded] = useState(false);
  const [attentionExpanded, setAttentionExpanded] = useState(false);

  if (!loggedIn) {
    return (
      <Alert severity="info">
        Sign in to see the system status.{" "}
        <Link component={RouterLink} to="/login">
          Go to login
        </Link>
      </Alert>
    );
  }

  const botList = (bots.data ?? []).filter((b) => perms.seesBot(b.botId));
  const onlineBots = botList.filter((b) => b.online);
  const openOrders = orders.filter(
    (o) => orderUiState(String(o.status ?? "unknown")).open,
  );
  // Server clock for age judgments (operations `at`); the client clock lies
  // about server-side silence. Falls back to Date.now() when ops is down.
  const serverNow =
    typeof (ops.data as any)?.at === "number"
      ? (ops.data as any).at
      : Date.now();
  // Attention membership is structural (orderStatus.needsAttentionNow) plus the
  // time-based recovering rule — never a hand-rolled status list here.
  const needAttention = orders.filter((o) => {
    const s = String(o.status ?? "unknown");
    if (needsAttentionNow(s, o, serverNow)) return true;
    if (!showResolved) return false;
    // Resolved history: superseded orders that needed attention in their own
    // right, revealed by the toggle.
    if (!isSuperseded(o)) return false;
    const lower = s.toLowerCase();
    return orderUiState(lower).needsAttention || lower === "uncertain";
  });

  // Backend-derived health contract: /health stuck-work signals, silent
  // agents, offline agents, failed orders, operator alerts and stale stock
  // rank by severity. Nothing blocked is only claimed when every source is
  // verified quiet (see healthUnknown below).
  //
  // Counter semantics (P1-13): the figure counts attention SIGNALS after
  // aggregation, not raw backend rows. Corroborating alerts are aggregated
  // onto the row they describe ("+ CODE ×N") instead of folded away, so one
  // incident is counted once but no count is silently dropped.
  const attention: Attention[] = [];
  const mutedNotes: string[] = [];
  const healthFresh = isHealthFresh(health.data, Date.now());
  const healthLoading =
    server.isLoading ||
    stashes.isLoading ||
    ordersLoading ||
    health.isLoading ||
    (canActivity && (bots.isLoading || ops.isLoading));
  const healthUnknown =
    !canActivity ||
    server.isError ||
    stashes.isError ||
    failedStashes.length > 0 ||
    (!health.isLoading && !healthFresh) ||
    (canActivity && (bots.isError || ops.isError));
  // /health 60s signals: stuck orders and a stale index are errors even when
  // every other source looks quiet (frozen-bot case).
  attention.push(...healthAttention(health.data ?? null));
  // Silence watchdog: online transport, no progress on open work. Uses the
  // server clock and the order table, never the agent-reported summary.
  const silent = canActivity ? silentBots(botList, openOrders, serverNow) : [];
  const silentIds = new Set(silent.map((s) => s.bot.botId));
  for (const s of silent) {
    const oldest = s.orders
      .map((o) => String(o.id))
      .sort()
      .slice(0, 3)
      .join(", ");
    attention.push({
      severity: "error",
      text: `Bot ${s.bot.player ?? s.bot.botId} silent with open work (${s.orders.length} order${s.orders.length === 1 ? "" : "s"}: ${oldest}) — no progress for 90s+ by the server clock.`,
      to: "/bots",
    });
  }
  // Agent-vs-server mismatch: an "idle" summary that disagrees with the
  // server's open-work table gets its own warning (bots already flagged
  // silent are not repeated).
  for (const b of botList) {
    if (silentIds.has(b.botId)) continue;
    if (agentServerMismatch(b, openOrders)) {
      attention.push({
        severity: "warning",
        text: `Bot ${b.player ?? b.botId} reports "${b.summary}" but the server still holds open work against it.`,
        to: "/bots",
      });
    }
  }
  const offlineBots = botList.filter((b) => !b.online);
  const opsAlerts: { code: string; count: number }[] = Array.isArray(
    (ops.data as any)?.alerts,
  )
    ? (ops.data as any).alerts
    : [];
  const disconnected = alertCount(opsAlerts, "DISCONNECTED");
  for (const b of offlineBots) {
    attention.push({
      severity: "warning",
      text: withAlertCount(
        `Bot ${b.player ?? b.botId} offline — seen ${timeAgo(b.lastSeen ?? b.activityAt)}. Orders queue until it returns.`,
        "DISCONNECTED",
        disconnected,
      ),
      to: "/bots",
    });
  }
  if (disconnected > 0 && offlineBots.length === 0) {
    attention.push({
      severity: "warning",
      text: `Operations alert: DISCONNECTED (×${disconnected}) with no offline bot listed — check bot visibility permissions.`,
      to: "/operations",
    });
  }
  const listedReconciliation = needAttention.some(
    (o) => (o.status ?? "").toLowerCase() === "needs_reconciliation",
  );
  const reconciliationAlerts = alertCount(opsAlerts, "NEEDS_RECONCILIATION");
  const orderLimit = 5;
  const { visible: visibleOrders, hidden: hiddenOrders } = visibleWithOverflow(
    needAttention,
    orderLimit,
    ordersExpanded,
  );
  for (const o of visibleOrders) {
    const base =
      `Order ${String(o.id)} (${o.stash}) needs attention: ${String(o.status ?? "unknown")}` +
      `${o.message ? ` — ${String(o.message)}` : ""}`;
    attention.push({
      severity: "error",
      text:
        String(o.status ?? "").toLowerCase() === "needs_reconciliation"
          ? withAlertCount(base, "NEEDS_RECONCILIATION", reconciliationAlerts)
          : base,
      to: "/orders",
      search: { q: String(o.id) },
    });
  }
  if (hiddenOrders > 0 && !ordersExpanded) {
    attention.push({
      severity: "error",
      text: `${hiddenOrders} more order${hiddenOrders === 1 ? "" : "s"} need${hiddenOrders === 1 ? "s" : ""} attention — expand below or open Orders.`,
      to: "/orders",
    });
  }
  if (reconciliationAlerts > 0 && !listedReconciliation) {
    attention.push({
      severity: "warning",
      text: `Operations alert: NEEDS_RECONCILIATION (×${reconciliationAlerts}) with no listed order — inspect the physical box before recovery.`,
      to: "/operations",
    });
  }
  // Any visible stash with a rescan pending: bare STALE counts are expected
  // fallout (older servers only emit the bare code), not actionable.
  const rescanPending = (stashes.data ?? []).some(
    (s) => perms.seesStash(s.stash) && s.rescanPending === true,
  );
  for (const a of opsAlerts) {
    if (a.code === "DISCONNECTED" || a.code === "NEEDS_RECONCILIATION") {
      continue;
    }
    // #203: ORDER_BLOCKED was only an anonymous counter. Surface it with a link to
    // the stash chips above (which now render the verbatim orderBlocker per stash).
    if (a.code === "ORDER_BLOCKED") {
      attention.push({
        severity: "error",
        text: `Operations alert: ORDER_BLOCKED (×${a.count}) — a stash refuses orders; see the stash chips for the blocker reason.`,
        to: "/operations",
      });
      continue;
    }
    if (
      a.code === "STALE" ||
      a.code.startsWith("STALE_") ||
      a.code === "MISSING" ||
      a.code === "UNREACHABLE" ||
      a.code === "UNKNOWN"
    ) {
      const kind = classifyStaleAlert(a.code, rescanPending);
      if (kind.muted) {
        mutedNotes.push(
          `Stock ${a.code} ×${a.count} during rescan (expected) — availability settles when the rescan lands.`,
        );
        continue;
      }
      attention.push({
        severity: "warning",
        text: `Operations alert: ${a.code} (×${a.count}) — stock may be wrong, verify before ordering.`,
        to: "/operations",
      });
      continue;
    }
    attention.push({
      severity: "warning",
      text: `Operations alert: ${a.code} (×${a.count})`,
      to: "/operations",
    });
  }
  // One signal per stash without data (each links at the stash's orders).
  attention.push(...failedStashItems(failedStashes));
  // Ranked by severity; stable otherwise.
  const rank: Record<Attention["severity"], number> = {
    error: 0,
    warning: 1,
    info: 2,
  };
  attention.sort((a, b) => rank[a.severity] - rank[b.severity]);

  return (
    <Stack spacing={2}>
      <Typography variant="h5" component="h1">
        Overview
      </Typography>

      {server.isSuccess && !server.data?.configured && (
        <Alert severity="warning">
          No Minecraft server configured yet. Physical work is disabled until
          the owner sets one in{" "}
          <Link component={RouterLink} to="/settings">
            Settings
          </Link>
          .
        </Alert>
      )}
      {failedStashes.length > 0 && (
        <Alert severity="error">
          Could not load orders for {failedStashes.join(", ")}. The list below
          is partial.
        </Alert>
      )}
      {stashes.isError && (
        <Alert severity="error">
          Could not load the stash list. Check the Manager connection and
          reload.
        </Alert>
      )}
      {canActivity && (bots.isError || ops.isError) && (
        <Alert severity="error">
          Cannot verify agent or operations health. The dashboard is incomplete.
        </Alert>
      )}
      {!health.isLoading && !healthFresh && (
        <Alert severity="warning">
          Manager health is not fresh — stuck orders or a stale index cannot be
          ruled out. The list below may be partial.
        </Alert>
      )}
      {mutedNotes.length > 0 && (
        <Alert severity="info">{mutedNotes.join(" ")}</Alert>
      )}
      {!canActivity && (
        <Alert severity="info">
          Agent and operations health cannot be verified without activity
          permission.
        </Alert>
      )}

      <Grid container spacing={2}>
        <Grid size={{ xs: 12, sm: 6, md: 3 }}>
          <Card>
            <CardHeader
              title="Bots"
              subheader={
                canActivity
                  ? `${onlineBots.length} of ${botList.length} online`
                  : "Activity permission required"
              }
            />
            <CardContent>
              {!canActivity || bots.isError ? (
                <Typography variant="h4" component="p">
                  —
                </Typography>
              ) : bots.isLoading ? (
                <CircularProgress size={24} />
              ) : (
                <Typography variant="h4" component="p">
                  {onlineBots.length}/{botList.length}
                </Typography>
              )}
              <Typography variant="body2" color="text.secondary">
                {botList.length === 0
                  ? "No bots reported yet."
                  : "Signed agents reporting presence."}
              </Typography>
            </CardContent>
          </Card>
        </Grid>
        <Grid size={{ xs: 12, sm: 6, md: 3 }}>
          <Card>
            <CardHeader title="Stashes" subheader="Defined regions" />
            <CardContent>
              {stashes.isLoading ? (
                <CircularProgress size={24} />
              ) : (
                <Typography variant="h4" component="p">
                  {stashes.data?.length ?? 0}
                </Typography>
              )}
              <Typography variant="body2" color="text.secondary">
                Server:{" "}
                <Typography component="span" sx={{ fontFamily: "monospace" }}>
                  {server.data?.server || "not set"}
                </Typography>
              </Typography>
            </CardContent>
          </Card>
        </Grid>
        <Grid size={{ xs: 12, sm: 6, md: 3 }}>
          <Card>
            <CardHeader
              title="Open orders"
              subheader="Across visible stashes"
            />
            <CardContent>
              {ordersLoading ? (
                <CircularProgress size={24} />
              ) : (
                <Typography variant="h4" component="p">
                  {openOrders.length}
                </Typography>
              )}
              <Typography variant="body2" color="text.secondary">
                {orders.length} total tracked.
              </Typography>
            </CardContent>
          </Card>
        </Grid>
        <Grid size={{ xs: 12, sm: 6, md: 3 }}>
          <Card>
            <CardHeader
              title="Needs attention"
              subheader="Bots, orders, alerts"
            />
            <CardContent>
              {healthLoading ? (
                <CircularProgress size={24} />
              ) : healthUnknown && attention.length === 0 ? (
                <Typography variant="h4" component="p">
                  —
                </Typography>
              ) : (
                <Typography
                  variant="h4"
                  component="p"
                  color={attention.length ? "error" : undefined}
                >
                  {attention.length}
                </Typography>
              )}
              <Typography variant="body2" color="text.secondary">
                {attention.length
                  ? healthUnknown
                    ? "Resolve below — each item links straight there. Some sources could not be verified, so the list may be partial."
                    : "Resolve below — each item links straight there."
                  : healthLoading
                    ? "Checking system health."
                    : healthUnknown
                      ? "Health could not be fully verified."
                      : "Nothing blocked."}
              </Typography>
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      {attention.length > 0 && (
        <Card>
          <CardHeader
            title="Needs attention"
            subheader="Ranked by severity; each item links straight there"
            action={
              <FormControlLabel
                control={
                  <Switch
                    size="small"
                    checked={showResolved}
                    onChange={(e) => setShowResolved(e.target.checked)}
                  />
                }
                label="Show resolved"
              />
            }
          />
          <CardContent sx={{ pt: 0 }}>
            <List dense>
              {visibleWithOverflow(attention, 8, attentionExpanded).visible.map(
                (a, i) => (
                  <ListItem key={i} disableGutters>
                    <ListItemIcon sx={{ minWidth: 32 }}>
                      {a.severity === "error" ? (
                        <ErrorIcon fontSize="small" color="error" />
                      ) : (
                        <WarningAmberIcon fontSize="small" color="warning" />
                      )}
                    </ListItemIcon>
                    <ListItemText primary={<AttentionLink item={a} />} />
                    <Chip
                      size="small"
                      color={a.severity === "error" ? "error" : "warning"}
                      label={a.severity}
                    />
                  </ListItem>
                ),
              )}
            </List>
            {(() => {
              const { hidden } = visibleWithOverflow(
                attention,
                8,
                attentionExpanded,
              );
              if (hidden === 0 && !attentionExpanded) return null;
              return (
                <Button
                  size="small"
                  onClick={() => setAttentionExpanded((v) => !v)}
                >
                  {attentionExpanded ? "Show less" : `+${hidden} more`}
                </Button>
              );
            })()}
            {ordersExpanded &&
              hiddenOrders === 0 &&
              needAttention.length > orderLimit && (
                <Box sx={{ mt: 1 }}>
                  <Button size="small" onClick={() => setOrdersExpanded(false)}>
                    Collapse order list
                  </Button>
                </Box>
              )}
            {!ordersExpanded && hiddenOrders > 0 && (
              <Box sx={{ mt: 1 }}>
                <Button size="small" onClick={() => setOrdersExpanded(true)}>
                  {`Show ${hiddenOrders} more order${hiddenOrders === 1 ? "" : "s"}`}
                </Button>
              </Box>
            )}
          </CardContent>
        </Card>
      )}

      <Grid container spacing={2}>
        <Grid size={{ xs: 12, md: 6 }}>
          <Card>
            <CardHeader
              title="Bots"
              action={
                <Link component={RouterLink} to="/bots">
                  All bots
                </Link>
              }
            />
            <CardContent sx={{ pt: 0 }}>
              {bots.isLoading && <LinearProgress />}
              <List dense>
                {botList.slice(0, 6).map((b) => (
                  <ListItem
                    key={b.botId}
                    disableGutters
                    secondaryAction={
                      silentIds.has(b.botId) ? (
                        <Chip size="small" color="error" label="silent" />
                      ) : (
                        <PresenceChip online={b.online} />
                      )
                    }
                  >
                    <ListItemText
                      primary={b.player ?? b.botId}
                      secondary={`${b.summary ?? "no status"}${b.cause ? ` · ${b.cause}` : ""} · seen ${timeAgo(b.lastSeen ?? b.activityAt)}`}
                    />
                  </ListItem>
                ))}
                {botList.length > 6 && (
                  <ListItem disableGutters>
                    <ListItemText
                      primary={
                        <Link
                          component={RouterLink}
                          to="/bots"
                          underline="hover"
                        >
                          +{botList.length - 6} more bot
                          {botList.length - 6 === 1 ? "" : "s"}
                        </Link>
                      }
                    />
                  </ListItem>
                )}
                {!bots.isLoading && botList.length === 0 && (
                  <ListItem>
                    <ListItemText primary="No bots reported yet." />
                  </ListItem>
                )}
              </List>
            </CardContent>
          </Card>
        </Grid>
        <Grid size={{ xs: 12, md: 6 }}>
          <Card>
            <CardHeader
              title="Latest orders"
              action={
                <Link component={RouterLink} to="/orders">
                  All orders
                </Link>
              }
            />
            <CardContent sx={{ pt: 0 }}>
              {ordersLoading && <LinearProgress />}
              <List dense>
                {orders.slice(0, 6).map((o) => (
                  <ListItem
                    key={`${o.stash}:${o.id}`}
                    disableGutters
                    secondaryAction={
                      <OrderStatusChip status={String(o.status ?? "unknown")} />
                    }
                  >
                    <ListItemText
                      primary={
                        <Typography
                          variant="body2"
                          sx={{ fontFamily: "monospace" }}
                        >
                          {String(o.id)}
                        </Typography>
                      }
                      secondary={`${o.stash} · ${(o.lines ?? []).map((l: any) => `${l.key} ×${l.count}`).join(", ")}`}
                    />
                  </ListItem>
                ))}
                {orders.length > 6 && (
                  <ListItem disableGutters>
                    <ListItemText
                      primary={
                        <Link
                          component={RouterLink}
                          to="/orders"
                          underline="hover"
                        >
                          +{orders.length - 6} more order
                          {orders.length - 6 === 1 ? "" : "s"}
                        </Link>
                      }
                    />
                  </ListItem>
                )}
                {!ordersLoading && orders.length === 0 && (
                  <ListItem>
                    <ListItemText primary="No orders yet." />
                  </ListItem>
                )}
              </List>
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      <Card>
        <CardHeader
          title="Stashes"
          subheader="Regions the Manager owns; bots execute headlessly"
        />
        <CardContent sx={{ pt: 0 }}>
          <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 1 }}>
            {(stashes.data ?? [])
              .filter((s) => perms.seesStash(s.stash))
              .map((s) => (
                <Chip
                  key={s.stash}
                  label={[
                    s.stash,
                    s.online === false && "offline",
                    s.orderBlocker && `blocked: ${s.orderBlocker}`,
                    s.rescanPending && "rescan pending",
                  ]
                    .filter(Boolean)
                    .join(" · ")}
                  variant={
                    s.online === false || s.orderBlocker ? "outlined" : "filled"
                  }
                  color={
                    s.orderBlocker
                      ? "error"
                      : s.online === false
                        ? "warning"
                        : "default"
                  }
                  title={
                    s.orderBlocker
                      ? `Order blocker: ${s.orderBlocker}`
                      : undefined
                  }
                />
              ))}
            {(stashes.data ?? []).length === 0 && !stashes.isLoading && (
              <Typography variant="body2" color="text.secondary">
                No stashes visible. The owner defines regions in{" "}
                <Link component={RouterLink} to="/settings">
                  Settings
                </Link>
                .
              </Typography>
            )}
          </Stack>
          <Box sx={{ mt: 1 }}>
            <Typography variant="body2" color="text.secondary">
              Order from the guild stock in{" "}
              <Link component={RouterLink} to="/inventory">
                Inventory
              </Link>
              .
            </Typography>
          </Box>
        </CardContent>
      </Card>
    </Stack>
  );
}
