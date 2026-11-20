import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import CardHeader from "@mui/material/CardHeader";
import Chip from "@mui/material/Chip";
import CircularProgress from "@mui/material/CircularProgress";
import Grid from "@mui/material/Grid";
import LinearProgress from "@mui/material/LinearProgress";
import Link from "@mui/material/Link";
import List from "@mui/material/List";
import ListItem from "@mui/material/ListItem";
import ListItemText from "@mui/material/ListItemText";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import { Link as RouterLink } from "@tanstack/react-router";
import { useAuth, useBots, usePermissions, useServer, useStashes, useAllOrders } from "../api/queries";
import { OrderStatusChip, PresenceChip, timeAgo } from "../components/status";

/**
 * Overview: the system's control center. Answers "does the system work, what are the
 * bots doing, what is happening with orders, is anything blocked" from real backend data.
 */
export function OverviewPage() {
  const auth = useAuth();
  const perms = usePermissions(auth.data);
  const loggedIn = !!auth.data?.loggedIn && perms.can("view");
  const bots = useBots(loggedIn && perms.can("activity"));
  const stashes = useStashes(loggedIn);
  const server = useServer(loggedIn);
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
  const openOrders = orders.filter((o) => !["completed", "delivered", "failed", "cancelled"].includes((o.status ?? "").toLowerCase()));
  const needAttention = orders.filter((o) =>
    ["failed", "needs_reconciliation", "uncertain"].includes((o.status ?? "").toLowerCase()) ||
    (o.message ?? "").length > 0 && (o.status ?? "").toLowerCase() === "failed",
  );

  return (
    <Stack spacing={2}>
      <Typography variant="h1">Overview</Typography>

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

      <Grid container spacing={2}>
        <Grid size={{ xs: 12, sm: 6, md: 3 }}>
          <Card>
            <CardHeader title="Bots" subheader={`${onlineBots.length} of ${botList.length} online`} />
            <CardContent>
              {bots.isLoading ? <CircularProgress size={24} /> : (
                <Typography variant="h3">{onlineBots.length}/{botList.length}</Typography>
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
                <Typography variant="h3">{stashes.data?.length ?? 0}</Typography>
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
                <Typography variant="h3">{openOrders.length}</Typography>
              )}
              <Typography variant="body2" color="text.secondary">
                {orders.length} total tracked.
              </Typography>
            </CardContent>
          </Card>
        </Grid>
        <Grid size={{ xs: 12, sm: 6, md: 3 }}>
          <Card>
            <CardHeader title="Needs attention" subheader="Failed / blocked" />
            <CardContent>
              {ordersLoading ? <CircularProgress size={24} /> : (
                <Typography variant="h3" color={needAttention.length ? "error" : undefined}>{needAttention.length}</Typography>
              )}
              <Typography variant="body2" color="text.secondary">
                {needAttention.length ? "Open Orders to resolve." : "Nothing blocked."}
              </Typography>
            </CardContent>
          </Card>
        </Grid>
      </Grid>

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
