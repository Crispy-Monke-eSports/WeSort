import CancelIcon from "@mui/icons-material/Cancel";
import DeleteIcon from "@mui/icons-material/Delete";
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
import InputLabel from "@mui/material/InputLabel";
import LinearProgress from "@mui/material/LinearProgress";
import MenuItem from "@mui/material/MenuItem";
import Paper from "@mui/material/Paper";
import Select from "@mui/material/Select";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TablePagination from "@mui/material/TablePagination";
import TableRow from "@mui/material/TableRow";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import useMediaQuery from "@mui/material/useMediaQuery";
import { useTheme } from "@mui/material/styles";
import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useState } from "react";
import { api } from "../api/client";
import { keys, useAllOrders, useAuth, usePermissions, useStashes } from "../api/queries";
import { OrderStatusChip } from "../components/status";

function progressText(o: any): string {
  const p = o?.progress;
  if (p == null) return "";
  if (typeof p === "string") return p;
  try {
    if (typeof p.done === "number" && typeof p.total === "number") return `${p.done}/${p.total}`;
  } catch { /* ignore */ }
  return "";
}

/**
 * Orders: the guild's order dashboard. Real backend states, per-stash errors surfaced
 * (never a silent empty list), pagination, and working retry/cancel/force-cancel/clear.
 */
export function OrdersPage() {
  const auth = useAuth();
  const perms = usePermissions(auth.data);
  const queryClient = useQueryClient();
  const loggedIn = !!auth.data?.loggedIn && perms.can("view");
  const stashes = useStashes(loggedIn);
  const stashList = useMemo(() => stashes.data ?? [], [stashes.data]);
  const visible = useMemo(() => stashList.filter((s) => perms.seesStash(s.stash)), [stashList, perms]);
  const { orders, failedStashes, loading } = useAllOrders(visible, loggedIn);

  const [statusFilter, setStatusFilter] = useState("all");
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(0);
  const [rowsPerPage, setRowsPerPage] = useState(25);
  const [detail, setDetail] = useState<any | null>(null);
  const [actionMsg, setActionMsg] = useState("");
  // Genuine mobile view (cards, not a squeezed table): render exactly one variant.
  const theme = useTheme();
  const isXs = useMediaQuery(theme.breakpoints.down("sm"));

  const canChange = perms.can("order") || perms.owner;

  const filtered = useMemo(() => {
    let list = orders;
    if (!perms.owner && !perms.can("ordersAll")) {
      const me = auth.data?.user?.id;
      list = list.filter((o: any) => o.placedBy?.id === me || o.userId === me || o.placedBy?.name === auth.data?.user?.name);
    }
    if (statusFilter !== "all") list = list.filter((o: any) => (o.status ?? "").toLowerCase() === statusFilter);
    if (query.trim()) {
      const t = query.trim().toLowerCase();
      list = list.filter((o: any) =>
        String(o.id ?? "").toLowerCase().includes(t) ||
        String(o.stash ?? o.stashName ?? "").toLowerCase().includes(t) ||
        (o.lines ?? []).some((l: any) => String(l.key ?? "").toLowerCase().includes(t)) ||
        String(o.recipient ?? "").toLowerCase().includes(t));
    }
    return list;
  }, [orders, statusFilter, query, perms, auth.data]);

  async function act(order: any, action: "cancel" | "force-cancel" | "retry") {
    setActionMsg("");
    const verb = action === "cancel" ? "request cancel for" : action === "force-cancel" ? "force-cancel" : "retry";
    if (!window.confirm(`${verb} order ${order.id}?`)) return;
    try {
      const path = action === "retry" ? `/api/v1/orders/${order.id}/retry` : `/api/v1/orders/${order.id}/${action}`;
      const res = await api<{ ok: boolean; order?: any }>(path, { method: "POST", body: {} });
      setActionMsg(action === "retry" ? `Retried as ${res.order?.id ?? "a new order"}.` : `Order ${order.id}: ${action} sent.`);
      if (order.stash) queryClient.invalidateQueries({ queryKey: keys.orders(order.stash) });
      setDetail(null);
    } catch (e: any) {
      setActionMsg(e?.message ?? `${action} failed.`);
    }
  }

  async function clearFinished(stash: string) {
    setActionMsg("");
    if (!window.confirm(`Clear finished orders for stash ${stash}? Open orders stay.`)) return;
    try {
      const res = await api<{ removed: number }>("/api/v1/orders/clear", { method: "POST", body: { stash } });
      setActionMsg(`Cleared ${res.removed} finished orders (${stash}).`);
      queryClient.invalidateQueries({ queryKey: keys.orders(stash) });
    } catch (e: any) {
      setActionMsg(e?.message ?? "Clear failed.");
    }
  }

  return (
    <Stack spacing={2}>
      <Typography variant="h1">Orders</Typography>
      {failedStashes.length > 0 && (
        <Alert severity="error">Could not load orders for {failedStashes.join(", ")} — the table below is partial.</Alert>
      )}
      {actionMsg && <Alert severity="info" onClose={() => setActionMsg("")}>{actionMsg}</Alert>}

      <Stack direction={{ xs: "column", sm: "row" }} spacing={1}>
        <TextField label="Search orders" value={query} onChange={(e) => { setQuery(e.target.value); setPage(0); }} size="small" sx={{ minWidth: 220 }} placeholder="order id, stash, item, recipient…" />
        <FormControl size="small" sx={{ minWidth: 160 }}>
          <InputLabel id="orders-status-label">Status</InputLabel>
          <Select value={statusFilter} labelId="orders-status-label" id="orders-status" label="Status" onChange={(e) => { setStatusFilter(e.target.value); setPage(0); }}>
            {["all", "pending", "accepted", "in_progress", "packed", "needs_reconciliation", "delivered", "failed", "cancelled"].map((s) => (
              <MenuItem key={s} value={s}>{s === "all" ? "All statuses" : s}</MenuItem>
            ))}
          </Select>
        </FormControl>
      </Stack>

      {!isXs && (<TableContainer component={Paper} id="order-list">
        <Table size="small" aria-label="Orders table">
          <TableHead>
            <TableRow>
              <TableCell>Order</TableCell>
              <TableCell>Stash</TableCell>
              <TableCell>Items</TableCell>
              <TableCell>Status</TableCell>
              <TableCell>Updated</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {loading && (
              <TableRow><TableCell colSpan={5}><LinearProgress /></TableCell></TableRow>
            )}
            {filtered.slice(page * rowsPerPage, page * rowsPerPage + rowsPerPage).map((o: any) => (
              <TableRow
                hover key={`${o.stash}:${o.id}`} sx={{ cursor: "pointer" }}
                onClick={() => setDetail(o)}
                onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); setDetail(o); } }}
                tabIndex={0} role="button" aria-label={`Order ${o.id}`}
                data-testid="order-card"
              >
                <TableCell sx={{ fontFamily: "monospace", fontSize: "0.8rem" }}>{o.id}</TableCell>
                <TableCell sx={{ fontFamily: "monospace", fontSize: "0.8rem" }}>{o.stash ?? o.stashName ?? o.server}</TableCell>
                <TableCell>{(o.lines ?? []).map((l: any) => `${l.key} ×${l.count}`).join(", ")}</TableCell>
                <TableCell><OrderStatusChip status={String(o.status ?? "unknown")} /></TableCell>
                <TableCell>{o.updatedAt ? new Date(o.updatedAt).toLocaleString() : "—"}</TableCell>
              </TableRow>
            ))}
            {!loading && filtered.length === 0 && (
              <TableRow><TableCell colSpan={5}><Typography color="text.secondary">No orders match.</Typography></TableCell></TableRow>
            )}
          </TableBody>
        </Table>
        <TablePagination
          component="div" count={filtered.length} page={page} rowsPerPage={rowsPerPage}
          rowsPerPageOptions={[10, 25, 50, 100]}
          onPageChange={(_, p) => setPage(p)}
          onRowsPerPageChange={(e) => { setRowsPerPage(Number(e.target.value)); setPage(0); }}
        />
      </TableContainer>)}

      {/* Genuine mobile view: order cards instead of a squeezed table. */}
      {isXs && (<Stack spacing={1} id="order-list-cards">
        {loading && <LinearProgress />}
        {filtered.slice(page * rowsPerPage, page * rowsPerPage + rowsPerPage).map((o: any) => (
          <Card
            key={`${o.stash}:${o.id}`} data-testid="order-card" tabIndex={0} role="button"
            aria-label={`Order ${o.id}`}
            onClick={() => setDetail(o)}
            onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); setDetail(o); } }}
            sx={{ cursor: "pointer" }}
          >
            <CardContent sx={{ "&:last-child": { pb: 2 } }}>
              <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
                <Typography variant="body2" sx={{ fontFamily: "monospace", flexGrow: 1, wordBreak: "break-all" }}>{o.id}</Typography>
                <OrderStatusChip status={String(o.status ?? "unknown")} />
              </Stack>
              <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
                {o.stash ?? o.stashName ?? o.server} · {(o.lines ?? []).map((l: any) => `${l.key} ×${l.count}`).join(", ")}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                {o.updatedAt ? new Date(o.updatedAt).toLocaleString() : "—"}
              </Typography>
            </CardContent>
          </Card>
        ))}
        {!loading && filtered.length === 0 && (
          <Typography color="text.secondary">No orders match.</Typography>
        )}
        <TablePagination
          component="div" count={filtered.length} page={page} rowsPerPage={rowsPerPage}
          rowsPerPageOptions={[10, 25, 50, 100]}
          onPageChange={(_, p) => setPage(p)}
          onRowsPerPageChange={(e) => { setRowsPerPage(Number(e.target.value)); setPage(0); }}
        />
      </Stack>)}
      {perms.can("ordersAll") && (
        <Card>
          <CardContent>
            <Typography variant="h4" gutterBottom>Order administration</Typography>
            <Stack spacing={1}>
              {visible.map((s) => (
                <Stack key={s.stash} direction={{ xs: "column", sm: "row" }} spacing={1} sx={{ alignItems: { sm: "center" } }}>
                  <Typography variant="body2" sx={{ fontFamily: "monospace", flexGrow: 1, wordBreak: "break-all" }}>{s.stash}</Typography>
                  <Box><Button variant="outlined" size="small" onClick={() => clearFinished(s.stash)}>Clear finished</Button></Box>
                </Stack>
              ))}
            </Stack>
          </CardContent>
        </Card>
      )}

      <OrderDialog order={detail} onClose={() => setDetail(null)} canChange={canChange} canAudit={perms.can("ordersAll") || perms.owner} onAction={act} />
    </Stack>
  );
}

function OrderDialog({ order, onClose, canChange, canAudit, onAction }: {
  order: any | null; onClose: () => void; canChange: boolean; canAudit: boolean; onAction: (o: any, a: "cancel" | "force-cancel" | "retry") => void;
}) {
  const [busy, setBusy] = useState(false);
  const [audit, setAudit] = useState<any[] | null>(null);
  const orderId = order?.id;
  useEffect(() => {
    setAudit(null);
    if (!orderId || !canAudit) return;
    let live = true;
    api<any>(`/api/v1/audit/orders?order=${encodeURIComponent(String(orderId))}&limit=1`)
      .then((d) => {
        if (!live) return;
        const o = (d.orders ?? [])[0];
        setAudit(o?.physicalOperations ?? []);
      })
      .catch(() => { if (live) setAudit([]); });
    return () => { live = false; };
  }, [orderId, canAudit]);
  if (!order) return null;
  const status = String(order.status ?? "unknown").toLowerCase();
  // Retry is for terminal states; cancel actions are for open ones. Never nested in one gate.
  const cancellable = ["pending", "accepted", "in_progress"].includes(status);
  const retryable = ["failed", "cancelled"].includes(status);
  const lines: any[] = order.lines ?? [];

  async function run(action: "cancel" | "force-cancel" | "retry") {
    setBusy(true);
    try { await onAction(order, action); } finally { setBusy(false); }
  }

  return (
    <Dialog open={!!order} onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle sx={{ fontFamily: "monospace" }}>{order.id}</DialogTitle>
      <DialogContent>
        <Stack spacing={2}>
          <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
            <OrderStatusChip status={String(order.status ?? "unknown")} />
            <Chip size="small" variant="outlined" label={order.stash ?? order.stashName ?? "?"} />
            {order.recipient && <Chip size="small" variant="outlined" label={`for ${order.recipient}`} />}
          </Stack>
          <Box>
            <Typography variant="body2" color="text.secondary">
              {order.purpose === "GUILD" ? "For the guild" : "For myself"}
              {order.verifiedRecipient ? ` · verified recipient ${order.verifiedRecipient}` : ""}
              {` · ${order.deliveryMode ?? "WAREHOUSE_PICKUP"}`}
              {order.destination?.player ? ` → player ${order.destination.player}` : order.destination?.stash ? ` → stash ${order.destination.stash}` : ""}
            </Typography>
            <Typography variant="body2" color="text.secondary">
              Approval: {String(order.approvalState ?? "DIRECT")}
              {order.decidedBy ? ` by ${order.decidedBy}${order.decidedAt ? ` at ${new Date(order.decidedAt).toLocaleString()}` : ""}` : ""}
              {order.agent ? ` · bot ${order.agent}` : ""}
              {order.state && order.state.toLowerCase() !== status ? ` · stage ${order.state}` : ""}
              {order.packageTotal ? ` · ${order.packageTotal} box${order.packageTotal === 1 ? "" : "es"}` : ""}
            </Typography>
            {order.cancelRequested && (
              <Typography variant="body2" color="warning.main">Cancel requested — waiting for the bot to confirm.</Typography>
            )}
            {order.retriedAs && (
              <Typography variant="body2" color="text.secondary">Retried as {order.retriedAs}.</Typography>
            )}
          </Box>
          <Box>
            <Typography variant="h4" gutterBottom>Items</Typography>
            {lines.map((l: any, i: number) => (
              <Typography key={i} variant="body2">×{l.count} {l.type === "shulker" ? `packed ${l.key}` : l.key}</Typography>
            ))}
            {order.packageLabel && (
              <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
                Box text: {order.packageLabel.mode === "all" ? order.packageLabel.text : (order.packageLabel.texts ?? []).filter(Boolean).join(" / ") || "“Beställning”"} · server numbers every box WeSort (X/Y).
              </Typography>
            )}
            {order.message && <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>{order.message}</Typography>}
            {progressText(order) && <Typography variant="body2" color="text.secondary">Progress: {progressText(order)}</Typography>}
          </Box>
          <Divider />
          <Box>
            <Typography variant="h4" gutterBottom>Timeline</Typography>
            <Stack spacing={1}>
              {[
                order.createdAt ? { at: order.createdAt, label: `Placed by ${order.placedBy?.name ?? order.requestedBy ?? "?"}` } : null,
                order.claimedAt ? { at: order.claimedAt, label: `Claimed by bot${order.agent ? ` ${order.agent}` : ""}` } : null,
                ...(audit ?? []).map((e: any) => ({
                  at: e.at ?? e.time ?? e.createdAt ?? 0,
                  label: String(e.event ?? e.action ?? e.state ?? JSON.stringify(e)).slice(0, 160),
                })),
                order.manifestAcknowledgedAt ? { at: order.manifestAcknowledgedAt, label: "Staging manifest acknowledged" } : null,
                order.releasedAt ? { at: order.releasedAt, label: "Released" } : null,
                order.updatedAt ? { at: order.updatedAt, label: `Last update — ${order.status}` } : null,
              ].filter((e) => e && e.at).map((e: any, i: number) => (
                <Stack key={i} direction="row" spacing={1.5} sx={{ alignItems: "baseline" }}>
                  <Box sx={{ width: 10, height: 10, borderRadius: "50%", bgcolor: i === 2 ? "primary.main" : "action.disabled", flexShrink: 0, alignSelf: "center" }} />
                  <Typography variant="body2" color="text.secondary" sx={{ minWidth: 150 }}>{new Date(e.at).toLocaleString()}</Typography>
                  <Typography variant="body2">{e.label}</Typography>
                </Stack>
              ))}
            </Stack>
            {canAudit && audit === null && <LinearProgress sx={{ mt: 1 }} />}
          </Box>
          {(order.phase || order.message) && (
            <Alert severity={status === "failed" ? "error" : "info"}>
              {order.phase ? <strong>{order.phase}: </strong> : null}{order.message ?? progressText(order)}
            </Alert>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        {canChange && cancellable && (
          <>
            <Button startIcon={<CancelIcon />} disabled={busy} onClick={() => run("cancel")}>Request cancel</Button>
            <Button startIcon={<DeleteIcon />} color="error" disabled={busy} onClick={() => run("force-cancel")}>Force cancel</Button>
          </>
        )}
        {canChange && retryable && (
          <Button variant="contained" disabled={busy} onClick={() => run("retry")}>Retry as new order</Button>
        )}
        <Button onClick={onClose}>Close</Button>
      </DialogActions>
    </Dialog>
  );
}
