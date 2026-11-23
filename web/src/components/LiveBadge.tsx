import Chip from "@mui/material/Chip";
import Tooltip from "@mui/material/Tooltip";
import { useLiveStore, type LiveStatus } from "../api/live";

const LABEL: Record<LiveStatus, string> = {
  live: "Live",
  connecting: "Connecting…",
  offline: "Offline",
};

/**
 * Live connection indicator: green while the SSE stream pushes updates,
 * amber/grey while reconnecting or without a session. Text is never
 * color-only (a11y).
 */
export function LiveBadge() {
  const status = useLiveStore((s) => s.status);
  const color =
    status === "live"
      ? "success"
      : status === "connecting"
        ? "warning"
        : "default";
  return (
    <Tooltip
      title={
        status === "live"
          ? "Connected to the Manager — updates arrive in real time."
          : "No live connection — the page resyncs when it returns."
      }
    >
      <Chip
        size="small"
        color={color}
        variant={status === "live" ? "filled" : "outlined"}
        label={LABEL[status]}
        data-testid="live-badge"
        sx={{ color: "inherit", borderColor: "rgba(255,255,255,0.4)" }}
      />
    </Tooltip>
  );
}
