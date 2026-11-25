import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import CardHeader from "@mui/material/CardHeader";
import Chip from "@mui/material/Chip";
import Grid from "@mui/material/Grid";
import LinearProgress from "@mui/material/LinearProgress";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import Tab from "@mui/material/Tab";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Tabs from "@mui/material/Tabs";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import useMediaQuery from "@mui/material/useMediaQuery";
import { useTheme } from "@mui/material/styles";
import { useState } from "react";
import {
  useAuditOrders,
  useAuth,
  useBoxJobs,
  useCustody,
  useDeposits,
  useHandoffs,
  useJobQueue,
  useOperations,
  usePermissions,
  usePhysicalOps,
  useRescan,
  useStashes,
  type OpsList,
} from "../api/queries";
import { classifyStaleAlert } from "../lib/attention";

function rowsOf(data: OpsList | undefined, key: string): any[] {
  const v = (data as any)?.[key];
  return Array.isArray(v) ? v : [];
}

/** Split rescan-expected stale alerts (muted) from actionable ones. */
function partitionAlerts(
  alerts: { code: string; count: number }[],
  rescanPending: boolean,
): {
  actionableAlerts: { code: string; count: number }[];
  mutedAlerts: { code: string; count: number }[];
} {
  const actionableAlerts: { code: string; count: number }[] = [];
  const mutedAlerts: { code: string; count: number }[] = [];
  for (const a of alerts) {
    if (a.code === "STALE" || a.code.startsWith("STALE_")) {
      const kind = classifyStaleAlert(a.code, rescanPending);
      (kind.muted ? mutedAlerts : actionableAlerts).push(a);
    } else {
      actionableAlerts.push(a);
    }
  }
  return { actionableAlerts, mutedAlerts };
}

/**
 * Operations: ongoing/completed work, autonomous physical recovery, job history
 * and the order audit. All physical recovery runs unattended.
 */
export function OperationsPage() {
  const auth = useAuth();
  const perms = usePermissions(auth.data);
  const loggedIn = !!auth.data?.loggedIn;
  const canActivity = loggedIn && (perms.can("activity") || perms.owner);
  const owner = !!auth.data?.loggedIn && perms.owner;
  // The full guild audit exposes other members' identities; only Owner may read it.
  const canAudit = owner;
  const [tab, setTab] = useState(0);

  const ops = useOperations(canActivity);
  const stashes = useStashes(loggedIn && perms.can("view"));
  const rescanPending = (stashes.data ?? []).some((s) => s.rescanPending);
  const physical = usePhysicalOps(owner);
  const jobs = useJobQueue(owner);
  const handoffs = useHandoffs(owner);
  const custody = useCustody(owner);
  const deposits = useDeposits(owner);
  const boxJobs = useBoxJobs(owner);

  const [auditQ, setAuditQ] = useState({
    order: "",
    person: "",
    item: "",
    stash: "",
  });
  const [auditGo, setAuditGo] = useState({
    order: "",
    person: "",
    item: "",
    stash: "",
  });
  const audit = useAuditOrders(canAudit, { limit: 50, ...auditGo });
  const rescan = useRescan();
  const [rescanMsg, setRescanMsg] = useState("");
  const [rescanBusy, setRescanBusy] = useState("");

  const alerts: { code: string; count: number }[] = Array.isArray(
    (ops.data as any)?.alerts,
  )
    ? (ops.data as any).alerts
    : [];
  const stock = (ops.data as any)?.stock as Record<string, number> | undefined;
  const agents = (ops.data as any)?.agents as
    Record<string, number> | undefined;
  const orderStates = (ops.data as any)?.orders as
    Record<string, number> | undefined;
  // STALE_RESCAN (expected rescan fallout) renders muted/info; every other
  // alert code stays actionable. Older servers only emit the bare STALE code,
  // which maps defensively through the stash rescanPending flags.
  const { actionableAlerts, mutedAlerts } = partitionAlerts(
    alerts,
    rescanPending,
  );

  return (
    <Stack spacing={2}>
      <Typography variant="h5" component="h1">
        Operations
      </Typography>
      {!canActivity && (
        <Alert severity="info">
          Sign in with activity permission to see operations.
        </Alert>
      )}

      {actionableAlerts.length > 0 && (
        <Alert severity="warning">
          <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 1 }}>
            {actionableAlerts.map((a) => (
              <Chip
                key={a.code}
                size="small"
                color="warning"
                label={`${a.code} ×${a.count}`}
              />
            ))}
          </Stack>
        </Alert>
      )}
      {mutedAlerts.length > 0 && (
        <Alert severity="info">
          Stock {mutedAlerts.map((a) => `${a.code} ×${a.count}`).join(", ")}{" "}
          during rescan (expected) — availability settles when the rescan lands.
        </Alert>
      )}
      {owner && (
        <Card>
          <CardHeader
            title="Owner controls"
            subheader="Rescan requests a fresh survey; the server replies with a rescanId"
          />
          <CardContent sx={{ pt: 0 }}>
            <Stack spacing={1}>
              <Stack
                direction="row"
                spacing={1}
                sx={{ flexWrap: "wrap", gap: 1 }}
              >
                {(stashes.data ?? [])
                  .filter((s) => perms.seesStash(s.stash))
                  .map((s) => (
                    <Button
                      key={s.stash}
                      size="small"
                      variant="outlined"
                      disabled={rescanBusy !== "" || s.online === false}
                      title={
                        s.online === false
                          ? "Bot offline — rescan queues until the bot returns"
                          : s.rescanPending
                            ? "Rescan already pending"
                            : `Request rescan of ${s.stash}`
                      }
                      onClick={async () => {
                        setRescanMsg("");
                        setRescanBusy(s.stash);
                        try {
                          const res = await rescan(s.stash);
                          setRescanMsg(
                            `Rescan ${s.stash}: ${res.rescanId ?? "requested"}.`,
                          );
                        } catch (e: any) {
                          setRescanMsg(
                            `Rescan ${s.stash} failed: ${e?.message ?? e}.`,
                          );
                        } finally {
                          setRescanBusy("");
                        }
                      }}
                    >
                      Rescan {s.stash}
                    </Button>
                  ))}
              </Stack>
              {rescanMsg && (
                <Typography variant="body2" color="text.secondary">
                  {rescanMsg}
                </Typography>
              )}
            </Stack>
          </CardContent>
        </Card>
      )}

      <Grid container spacing={2}>
        <Grid size={{ xs: 12, md: 4 }}>
          <Card>
            <CardHeader title="Stock" subheader="Sources by state" />
            <CardContent sx={{ pt: 0 }}>
              {ops.isLoading && <LinearProgress />}
              <StateTable states={stock} />
            </CardContent>
          </Card>
        </Grid>
        <Grid size={{ xs: 12, md: 4 }}>
          <Card>
            <CardHeader title="Agents" subheader="Presence" />
            <CardContent sx={{ pt: 0 }}>
              {ops.isLoading && <LinearProgress />}
              <StateTable states={agents} />
            </CardContent>
          </Card>
        </Grid>
        <Grid size={{ xs: 12, md: 4 }}>
          <Card>
            <CardHeader title="Orders" subheader="By stage" />
            <CardContent sx={{ pt: 0 }}>
              {ops.isLoading && <LinearProgress />}
              <StateTable states={orderStates} />
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      {owner && (
        <Card>
          <CardHeader
            title="Automation queues"
            subheader="Autonomous physical recovery and fulfillment work"
          />
          <Tabs
            value={tab}
            onChange={(_, v) => setTab(v)}
            variant="scrollable"
            scrollButtons="auto"
            aria-label="Operator queues"
          >
            {owner && (
              <Tab
                label={`Physical (${rowsOf(physical.data, "operations").length})`}
              />
            )}
            {owner && (
              <Tab label={`Jobs (${rowsOf(jobs.data, "jobs").length})`} />
            )}
            {owner && (
              <Tab
                label={`Handoffs (${rowsOf(handoffs.data, "handoffs").length})`}
              />
            )}
            {owner && (
              <Tab
                label={`Custody (${rowsOf(custody.data, "packages").length})`}
              />
            )}
            {owner && (
              <Tab
                label={`Deposits (${rowsOf(deposits.data, "deposits").length})`}
              />
            )}
            {owner && (
              <Tab
                label={`Box jobs (${rowsOf(boxJobs.data, "jobs").length})`}
              />
            )}
            <Tab label="Order audit" />
          </Tabs>
          <CardContent>
            {owner && tab === 0 && (
              <OpsTable
                loading={physical.isLoading}
                error={physical.isError}
                columns={["Operation", "State", "Revision", "Last seen"]}
                rows={rowsOf(physical.data, "operations").map((o: any) => [
                  String(o.id ?? o.operation ?? "?"),
                  <Chip
                    key="s"
                    size="small"
                    color={
                      /need|fail|uncertain/i.test(String(o.state ?? ""))
                        ? "warning"
                        : "default"
                    }
                    label={String(o.managerState ?? o.state ?? "unknown")}
                  />,
                  String(o.revision ?? "—"),
                  o.lastSeen ? new Date(o.lastSeen).toLocaleString() : "—",
                ])}
              />
            )}
            {owner && tab === 1 && (
              <OpsTable
                loading={jobs.isLoading}
                error={jobs.isError}
                columns={["Job", "Category", "State", "Chain", "Updated"]}
                rows={rowsOf(jobs.data, "jobs").map((j: any) => [
                  String(j.id ?? "?"),
                  String(j.category ?? "—"),
                  String(j.state ?? "—"),
                  String(j.chain ?? "—"),
                  j.updatedAt ? new Date(j.updatedAt).toLocaleString() : "—",
                ])}
              />
            )}
            {owner && tab === 2 && (
              <OpsTable
                loading={handoffs.isLoading}
                error={handoffs.isError}
                columns={[
                  "Handoff",
                  "Order",
                  "Recipient",
                  "State",
                  "Confirmed by",
                ]}
                rows={rowsOf(handoffs.data, "handoffs").map((h: any) => [
                  String(h.id ?? "?"),
                  String(h.orderId ?? "—"),
                  String(h.recipient ?? "—"),
                  String(h.state ?? "—"),
                  String(h.confirmedBy ?? "—"),
                ])}
              />
            )}
            {owner && tab === 3 && (
              <OpsTable
                loading={custody.isLoading}
                error={custody.isError}
                columns={["Package", "Order", "State", "Label", "Updated"]}
                rows={rowsOf(custody.data, "packages").map((p: any) => [
                  String(p.packageId ?? p.id ?? "?"),
                  String(p.orderId ?? "—"),
                  String(p.state ?? "—"),
                  String(p.label ?? "—"),
                  p.updatedAt ? new Date(p.updatedAt).toLocaleString() : "—",
                ])}
              />
            )}
            {owner && tab === 4 && (
              <OpsTable
                loading={deposits.isLoading}
                error={deposits.isError}
                columns={["Deposit", "Order", "Kind", "State", "Destination"]}
                rows={rowsOf(deposits.data, "deposits").map((d: any) => [
                  String(d.packageId ?? d.id ?? "?"),
                  String(d.orderId ?? "—"),
                  String(d.kind ?? "—"),
                  String(d.state ?? "—"),
                  String(d.destination ?? "—"),
                ])}
              />
            )}
            {owner && tab === 5 && (
              <OpsTable
                loading={boxJobs.isLoading}
                error={boxJobs.isError}
                columns={["Job", "State", "Server", "Stash", "Updated"]}
                rows={rowsOf(boxJobs.data, "jobs").map((j: any) => [
                  String(j.id ?? "?"),
                  String(j.state ?? "—"),
                  String(j.server ?? "—"),
                  String(j.stash ?? "—"),
                  j.updatedAt ? new Date(j.updatedAt).toLocaleString() : "—",
                ])}
              />
            )}
            {tab === 6 && (
              <Stack spacing={1.5} id="order-audit">
                <Stack direction={{ xs: "column", sm: "row" }} spacing={1}>
                  <TextField
                    label="Order id"
                    value={auditQ.order}
                    onChange={(e) =>
                      setAuditQ({ ...auditQ, order: e.target.value })
                    }
                    size="small"
                  />
                  <TextField
                    label="Person"
                    value={auditQ.person}
                    onChange={(e) =>
                      setAuditQ({ ...auditQ, person: e.target.value })
                    }
                    size="small"
                  />
                  <TextField
                    label="Item"
                    value={auditQ.item}
                    onChange={(e) =>
                      setAuditQ({ ...auditQ, item: e.target.value })
                    }
                    size="small"
                  />
                  <TextField
                    label="Stash"
                    value={auditQ.stash}
                    onChange={(e) =>
                      setAuditQ({ ...auditQ, stash: e.target.value })
                    }
                    size="small"
                  />
                  <Button
                    variant="contained"
                    onClick={() => setAuditGo(auditQ)}
                  >
                    Search
                  </Button>
                </Stack>
                {audit.isError && (
                  <Alert severity="error">Audit search failed.</Alert>
                )}
                {audit.isLoading && <LinearProgress />}
                <OpsTable
                  loading={false}
                  error={false}
                  columns={["Order", "Person", "Items", "Status"]}
                  rows={rowsOf(audit.data, "orders").map((o: any) => [
                    String(o.id ?? "?"),
                    String(o.placedBy?.name ?? o.person ?? "—"),
                    Array.isArray(o.lines)
                      ? o.lines
                          .map((l: any) => `${l.key} ×${l.count}`)
                          .join(", ")
                      : "—",
                    String(o.status ?? "—"),
                  ])}
                />
              </Stack>
            )}
          </CardContent>
        </Card>
      )}
    </Stack>
  );
}

function StateTable({
  states,
}: {
  states: Record<string, number> | undefined;
}) {
  const entries = Object.entries(states ?? {});
  if (!entries.length)
    return <Typography color="text.secondary">No data.</Typography>;
  const isXs = useMediaQuery(useTheme().breakpoints.down("sm"));
  // Two-column tables clip the value column on narrow screens (P1-06): on xs
  // both the label and the value stack vertically so each stays visible.
  if (isXs) {
    return (
      <Stack spacing={0.75}>
        {entries.map(([k, v]) => (
          <Box key={k} sx={{ minWidth: 0 }}>
            <Typography
              variant="caption"
              color="text.secondary"
              sx={{ wordBreak: "break-word" }}
            >
              {k}
            </Typography>
            <Typography variant="body2" sx={{ fontWeight: 700 }}>
              {v}
            </Typography>
          </Box>
        ))}
      </Stack>
    );
  }
  return (
    <TableContainer sx={{ minWidth: 0 }}>
      <Table size="small" aria-label="State counts">
        <TableBody>
          {entries.map(([k, v]) => (
            <TableRow key={k}>
              <TableCell sx={{ wordBreak: "break-word" }}>{k}</TableCell>
              <TableCell align="right">
                <strong>{v}</strong>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  );
}

function OpsTable({
  loading,
  error,
  columns,
  rows,
}: {
  loading: boolean;
  error: boolean;
  columns: string[];
  rows: React.ReactNode[][];
}) {
  // Render exactly one variant: dense table on sm+, key-value cards on xs.
  const isXs = useMediaQuery(useTheme().breakpoints.down("sm"));
  return (
    <Box>
      {loading && <LinearProgress sx={{ mb: 1 }} />}
      {error && (
        <Alert severity="error" sx={{ mb: 1 }}>
          Could not load this queue.
        </Alert>
      )}
      {/* Desktop: dense table. Mobile: key-value cards, no horizontal overflow. */}
      {!isXs && (
        <TableContainer component={Paper} variant="outlined">
          <Table size="small">
            <TableHead>
              <TableRow>
                {columns.map((c) => (
                  <TableCell key={c}>{c}</TableCell>
                ))}
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map((r, i) => (
                <TableRow key={i} hover>
                  {r.map((c, j) => (
                    <TableCell key={j}>{c}</TableCell>
                  ))}
                </TableRow>
              ))}
              {!loading && rows.length === 0 && (
                <TableRow>
                  <TableCell colSpan={columns.length}>
                    <Typography color="text.secondary">Empty.</Typography>
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </TableContainer>
      )}
      {isXs && (
        <Stack spacing={1}>
          {rows.map((r, i) => (
            <Paper key={i} variant="outlined" sx={{ p: 1.5 }}>
              {r.map((c, j) => (
                <Stack key={j} direction="row" spacing={1} sx={{ py: 0.25 }}>
                  <Typography
                    variant="caption"
                    color="text.secondary"
                    sx={{ minWidth: 88, flexShrink: 0 }}
                  >
                    {columns[j] ?? ""}
                  </Typography>
                  <Box sx={{ minWidth: 0, wordBreak: "break-word" }}>{c}</Box>
                </Stack>
              ))}
            </Paper>
          ))}
          {!loading && rows.length === 0 && (
            <Typography color="text.secondary">Empty.</Typography>
          )}
        </Stack>
      )}
    </Box>
  );
}
