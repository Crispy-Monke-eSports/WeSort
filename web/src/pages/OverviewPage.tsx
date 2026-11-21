import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
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
import WarningAmberIcon from "@mui/icons-material/WarningAmber";
import { Link as RouterLink } from "@tanstack/react-router";
import { useState } from "react";
import { useAuth, useBots, useOperations, usePermissions, useServer, useStashes, useAllOrders } from "../api/queries";
import { isSuperseded, orderUiState } from "../lib/orderStatus";
import { OrderStatusChip, PresenceChip, timeAgo } from "../components/status";

/** One actionable problem, backend-derived, with a direct link to resolve it. */
interface Attention {
  severity: "error" | "warning";
  text: string;
  to: string;
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
  const bots = useBots(loggedIn && perms.can("activity"));
  const stashes = useStashes(loggedIn);
  const server = useServer(loggedIn);
  const ops = useOperations(canActivity);
  const { orders, failedStashes, loading: ordersLoading } = useAllOrders(
    (stashes.data ?? []).filter((s) => perms.seesStash(s.stash)),
    loggedIn,
  );

  if (!loggedIn) {
    return (
      <Alert severity="info">
        Sign in to see the system status. <Link component={RouterLink} to="/login">Go to login</Link>
      </Alert>
    );
  }

  const botList = (bots.data ?? []).filter((b) => perms.seesBot(b.botId));
  const onlineBots = botList.filter((b) => b.online);
  const openOrders = orders.filter((o) => orderUiState(String(o.status ?? "unknown")).open);
  // Retried orders stand for their successor and no longer require anything: hidden by default
  // so the list shows current errors only. The toggle reveals the resolved history.
  const [showResolved, setShowResolved] = useState(false);
  const needAttention = orders.filter((o) => {
    const s = String(o.status ?? "unknown").toLowerCase();
    if (!orderUiState(s).needsAttention && s !== "uncertain") return false;
    return showResolved || !isSuperseded(o);
  });

  // Backend-derived health contract: offline agents, failed orders, operator
  // alerts and stale stock rank by severity. Nothing blocked is only claimed
  // when every source is quiet.
  //
  // Counter semantics (P1-13): the figure counts attention SIGNALS after
  // same-incident folding, not raw backend rows. One offline bot yields both
  // agents.OFFLINE and alerts.DISCONNECTED server-side; the operations alert
  // is folded into the per-bot entries so the same incident is not counted
  // twice. Likewise NEEDS_RECONCILIATION alerts fold into listed orders.
  const attention: Attention[] = [];
  const offlineBots = botList.filter((b) => !b.online);
  for (const b of offlineBots) {
    attention.push({
      severity: "warning",
      text: `Bot ${b.player ?? b.botId} offline — seen ${timeAgo(b.lastSeen ?? b.activityAt)}. Orders queue until it returns.`,
      to: "/bots",
    });
  }
  const listedReconciliation = needAttention.some((o) => (o.status ?? "").toLowerCase() === "needs_reconciliation");
  for (const o of needAttention.slice(0, 5)) {
    attention.push({
      severity: "error",
      text: `Order ${String(o.id)} (${o.stash}) needs attention: ${String(o.status ?? "unknown")}${o.message ? ` — ${String(o.message)}` : ""}`,
      to: "/orders",
    });
  }
  const opsAlerts: { code: string; count: number }[] = Array.isArray((ops.data as any)?.alerts) ? (ops.data as any).alerts : [];
  for (const a of opsAlerts.slice(0, 5)) {
    // Same-incident folding: the DISCONNECTED alert counts the same offline
    // bots listed above; the reconciliation alert counts orders listed above.
    if (a.code === "DISCONNECTED" && offlineBots.length > 0) continue;
    if (a.code === "NEEDS_RECONCILIATION" && listedReconciliation) continue;
    attention.push({ severity: "warning", text: `Operations alert: ${a.code} (${a.count})`, to: "/operations" });
  }
  if (failedStashes.length > 0) {
    attention.push({
      severity: "error",
      text: `Order data unavailable for ${failedStashes.join(", ")} — the lists below are partial.`,
      to: "/orders",
    });
  }

  return (
    <Stack spacing={2}>
      <Typography variant="h5" component="h1">Overview</Typography>

      {!server.data?.configured && (
        <Alert severity="warning">
          No Minecraft server configured yet. Physical work is disabled until the owner sets one in{" "}
          <Link component={RouterLink} to="/settings">Settings</Link>.
        </Alert>
      )}
      {failedStashes.length > 0 && (
        <Alert severity="error">
          Could not load orders for {failedStashes.join(", ")}. The list below is partial.
        </Alert>
      )}
      {stashes.isError && (
        <Alert severity="error">Could not load the stash list. Check the Manager connection and reload.</Alert>
      )}

      <Grid container spacing={2}>
        <Grid size={{ xs: 12, sm: 6, md: 3 }}>
          <Card>
            <CardHeader title="Bots" subheader={`${onlineBots.length} of ${botList.length} online`} />
            <CardContent>
              {bots.isLoading ? <CircularProgress size={24} /> : (
                <Typography variant="h4" component="p">{onlineBots.length}/{botList.length}</Typography>
              )}
              <Typography variant="body2" color="text.secondary">
                {botList.length === 0 ? "No bots reported yet." : "Signed agents reporting presence."}
              </Typography>
            </CardContent>
          </Card>
        </Grid>
        <Grid size={{ xs: 12, sm: 6, md: 3 }}>
          <Card>
            <CardHeader title="Stashes" subheader="Defined regions" />
            <CardContent>
              {stashes.isLoading ? <CircularProgress size={24} /> : (
                <Typography variant="h4" component="p">{stashes.data?.length ?? 0}</Typography>
              )}
              <Typography variant="body2" color="text.secondary">
                Server: <Typography component="span" sx={{ fontFamily: "monospace" }}>{server.data?.server || "not set"}</Typography>
              </Typography>
            </CardContent>
          </Card>
        </Grid>
        <Grid size={{ xs: 12, sm: 6, md: 3 }}>
          <Card>
            <CardHeader title="Open orders" subheader="Across visible stashes" />
            <CardContent>
              {ordersLoading ? <CircularProgress size={24} /> : (
                <Typography variant="h4" component="p">{openOrders.length}</Typography>
              )}
              <Typography variant="body2" color="text.secondary">
                {orders.length} total tracked.
              </Typography>
            </CardContent>
          </Card>
        </Grid>
        <Grid size={{ xs: 12, sm: 6, md: 3 }}>
          <Card>
            <CardHeader title="Needs attention" subheader="Bots, orders, alerts" />
            <CardContent>
              {ordersLoading ? <CircularProgress size={24} /> : (
                <Typography variant="h4" component="p" color={attention.length ? "error" : undefined}>{attention.length}</Typography>
              )}
              <Typography variant="body2" color="text.secondary">
                {attention.length ? "Resolve below — each item links straight there." : "Nothing blocked."}
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
                control={<Switch size="small" checked={showResolved} onChange={(e) => setShowResolved(e.target.checked)} />}
                label="Show resolved"
              />
            }
          />
          <CardContent sx={{ pt: 0 }}>
            <List dense>
              {attention.slice(0, 8).map((a, i) => (
                <ListItem key={i} disableGutters>
                  <ListItemIcon sx={{ minWidth: 32 }}>
                    {a.severity === "error"
                      ? <ErrorIcon fontSize="small" color="error" />
                      : <WarningAmberIcon fontSize="small" color="warning" />}
                  </ListItemIcon>
                  <ListItemText
                    primary={
                      <Link component={RouterLink} to={a.to} underline="hover">
                        {a.text}
                      </Link>
                    }
                  />
                  <Chip size="small" color={a.severity === "error" ? "error" : "warning"} label={a.severity} />
                </ListItem>
              ))}
            </List>
          </CardContent>
        </Card>
      )}

      <Grid container spacing={2}>
        <Grid size={{ xs: 12, md: 6 }}>
          <Card>
            <CardHeader title="Bots" action={<Link component={RouterLink} to="/bots">All bots</Link>} />
            <CardContent sx={{ pt: 0 }}>
              {bots.isLoading && <LinearProgress />}
              <List dense>
                {botList.slice(0, 6).map((b) => (
                  <ListItem key={b.botId} disableGutters secondaryAction={<PresenceChip online={b.online} />}>
                    <ListItemText
                      primary={b.player ?? b.botId}
                      secondary={`${b.summary ?? "no status"} · seen ${timeAgo(b.lastSeen ?? b.activityAt)}`}
                    />
                  </ListItem>
                ))}
                {!bots.isLoading && botList.length === 0 && (
                  <ListItem><ListItemText primary="No bots reported yet." /></ListItem>
                )}
              </List>
            </CardContent>
          </Card>
        </Grid>
        <Grid size={{ xs: 12, md: 6 }}>
          <Card>
            <CardHeader title="Latest orders" action={<Link component={RouterLink} to="/orders">All orders</Link>} />
            <CardContent sx={{ pt: 0 }}>
              {ordersLoading && <LinearProgress />}
              <List dense>
                {orders.slice(0, 6).map((o) => (
                  <ListItem
                    key={`${o.stash}:${o.id}`}
                    disableGutters
                    secondaryAction={<OrderStatusChip status={String(o.status ?? "unknown")} />}
                  >
                    <ListItemText
                      primary={<Typography variant="body2" sx={{ fontFamily: "monospace" }}>{String(o.id)}</Typography>}
                      secondary={`${o.stash} · ${(o.lines ?? []).map((l: any) => `${l.key} ×${l.count}`).join(", ")}`}
                    />
                  </ListItem>
                ))}
                {!ordersLoading && orders.length === 0 && (
                  <ListItem><ListItemText primary="No orders yet." /></ListItem>
                )}
              </List>
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      <Card>
        <CardHeader title="Stashes" subheader="Regions the Manager owns; bots execute headlessly" />
        <CardContent sx={{ pt: 0 }}>
          <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 1 }}>
            {(stashes.data ?? []).filter((s) => perms.seesStash(s.stash)).map((s) => (
              <Chip key={s.stash} label={`${s.stash}${s.online === false ? " · offline" : ""}`} variant={s.online === false ? "outlined" : "filled"} color={s.online === false ? "warning" : "default"} />
            ))}
            {(stashes.data ?? []).length === 0 && !stashes.isLoading && (
              <Typography variant="body2" color="text.secondary">
                No stashes visible. The owner defines regions in <Link component={RouterLink} to="/settings">Settings</Link>.
              </Typography>
            )}
          </Stack>
          <Box sx={{ mt: 1 }}>
            <Typography variant="body2" color="text.secondary">
              Order from the guild stock in <Link component={RouterLink} to="/inventory">Inventory</Link>.
            </Typography>
          </Box>
        </CardContent>
      </Card>
    </Stack>
  );
}
