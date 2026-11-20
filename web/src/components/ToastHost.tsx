import Alert from "@mui/material/Alert";
import Snackbar from "@mui/material/Snackbar";
import { useEffect, useState } from "react";

export interface ToastItem {
  id: number;
  text: string;
  kind?: "ok" | "err";
}

let toastListeners: ((t: ToastItem) => void)[] = [];
let toastId = 1;

export function toast(text: string, kind: "ok" | "err" = "ok"): void {
  const t = { id: toastId++, text, kind };
  for (const l of toastListeners) l(t);
}

/** MUI Snackbar host on the same toast bus the shop code uses. */
export function ToastHost() {
  const [items, setItems] = useState<ToastItem[]>([]);
  useEffect(() => {
    const push = (t: ToastItem) => {
      setItems((prev) => [...prev.slice(-2), t]);
      window.setTimeout(() => setItems((prev) => prev.filter((x) => x.id !== t.id)), 4000);
    };
    toastListeners.push(push);
    return () => {
      toastListeners = toastListeners.filter((l) => l !== push);
    };
  }, []);
  const current = items[0];
  return (
    <Snackbar
      open={!!current}
      autoHideDuration={4000}
      onClose={() => setCurrentClosed()}
      anchorOrigin={{ vertical: "bottom", horizontal: "right" }}
    >
      <Alert severity={current?.kind === "err" ? "error" : "success"} variant="filled">
        {current?.text ?? ""}
      </Alert>
    </Snackbar>
  );

  function setCurrentClosed() {
    if (current) setItems((prev) => prev.filter((x) => x.id !== current.id));
  }
}
