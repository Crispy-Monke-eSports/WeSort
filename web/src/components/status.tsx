import Chip from "@mui/material/Chip";
import { orderUiState } from "../lib/orderStatus";

/** Order status → MUI chip color + human label. Severities come from the tested backend mapping. */
export function OrderStatusChip({ status }: { status: string }) {
  const color = orderUiState(status).severity;
  return <Chip size="small" color={color} label={status} />;
}

/** Online/offline/unknown presence chip (shape + text, not color alone). */
export function PresenceChip({
  online,
  label,
}: {
  online: boolean | null | undefined;
  label?: string;
}) {
  if (online == null)
    return <Chip size="small" variant="outlined" label={label ?? "unknown"} />;
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
