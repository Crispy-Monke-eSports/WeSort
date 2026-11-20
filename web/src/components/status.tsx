import Chip from "@mui/material/Chip";

/** Order status → MUI chip color + human label. Statuses come from real backend states. */
export function OrderStatusChip({ status }: { status: string }) {
  const s = (status ?? "unknown").toLowerCase();
  const color =
    s === "completed" || s === "delivered" || s === "done"
      ? "success"
      : s === "failed" || s === "cancelled" || s === "force-cancelled"
        ? "error"
        : s === "pending" || s === "queued" || s === "stale" || s === "unknown"
          ? "default"
          : s === "accepted" || s === "in_progress" || s === "packing" || s === "staging"
            ? "info"
            : "warning";
  return <Chip size="small" color={color} label={status} />;
}

/** Online/offline/unknown presence chip (shape + text, not color alone). */
export function PresenceChip({ online, label }: { online: boolean | null | undefined; label?: string }) {
  if (online == null) return <Chip size="small" variant="outlined" label={label ?? "unknown"} />;
  return (
    <Chip
      size="small"
      color={online ? "success" : "error"}
      variant={online ? "filled" : "outlined"}
      label={label ?? (online ? "online" : "offline")}
    />
  );
}

/** Relative time ("3m ago") for heartbeat/polling timestamps. */
export function timeAgo(ts: number | null | undefined): string {
  if (ts == null || ts <= 0) return "never";
  const s = Math.max(0, Math.floor((Date.now() - ts) / 1000));
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
  return `${Math.floor(s / 86400)}d ago`;
}
