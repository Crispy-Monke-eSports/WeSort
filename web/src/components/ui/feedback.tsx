import * as React from "react";
import { cn } from "../../lib/utils";

export function QtyInput({
  value,
  min = 1,
  max,
  onChange,
  label = "Quantity",
  small,
}: {
  value: number;
  min?: number;
  max?: number;
  onChange: (n: number) => void;
  label?: string;
  small?: boolean;
}) {
  const clamp = (n: number) => {
    if (!Number.isFinite(n)) return min;
    return Math.max(min, max === undefined ? n : Math.min(max, Math.floor(n)));
  };
  return (
    <span className={cn("inline-flex items-stretch overflow-hidden rounded-lg border border-input bg-background", small && "text-xs")}>
      <button
        type="button"
        aria-label="Decrease quantity"
        className={cn("bg-muted/60 px-2 hover:bg-muted", small ? "h-7" : "h-9")}
        onClick={() => onChange(clamp(value - 1))}
      >
        −
      </button>
      <input
        type="number"
        aria-label={label}
        min={min}
        max={max}
        value={value}
        onChange={(e) => onChange(clamp(Number(e.target.value)))}
        className={cn("w-14 border-0 bg-transparent text-center font-mono outline-none [appearance:textfield] [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none", small ? "h-7 text-xs" : "h-9 text-sm")}
      />
      <button
        type="button"
        aria-label="Increase quantity"
        className={cn("bg-muted/60 px-2 hover:bg-muted", small ? "h-7" : "h-9")}
        onClick={() => onChange(clamp(value + 1))}
      >
        +
      </button>
    </span>
  );
}

export function Steps({ step, onGo }: { step: 1 | 2 | 3; onGo: (n: 1 | 2 | 3) => void }) {
  const labels = ["Cart", "Delivery", "Review"] as const;
  return (
    <ol className="flex items-center gap-1.5" aria-label="Checkout steps">
      {labels.map((label, i) => {
        const n = (i + 1) as 1 | 2 | 3;
        const state = n === step ? "current" : n < step ? "done" : "todo";
        return (
          <li key={label} className="flex items-center gap-1.5">
            {i > 0 && <span aria-hidden className="h-px w-4 bg-border" />}
            <button
              type="button"
              data-step={n}
              aria-current={n === step ? "step" : undefined}
              onClick={() => onGo(n)}
              className={cn(
                "inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-semibold transition-colors",
                state === "current" && "border-primary bg-primary text-primary-foreground",
                state === "done" && "border-emerald-500/40 bg-emerald-500/10 text-emerald-600 dark:text-emerald-300",
                state === "todo" && "border-border text-muted-foreground hover:bg-muted",
              )}
            >
              <span
                className={cn(
                  "inline-flex h-4 w-4 items-center justify-center rounded-full text-[10px]",
                  state === "current" && "bg-primary-foreground/25",
                  state === "done" && "bg-emerald-500/25",
                  state === "todo" && "bg-muted",
                )}
              >
                {state === "done" ? "✓" : n}
              </span>
              {label}
            </button>
          </li>
        );
      })}
    </ol>
  );
}

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

export function ToastHost() {
  const [items, setItems] = React.useState<ToastItem[]>([]);
  React.useEffect(() => {
    const push = (t: ToastItem) => {
      setItems((prev) => [...prev.slice(-2), t]);
      window.setTimeout(() => setItems((prev) => prev.filter((x) => x.id !== t.id)), 4000);
    };
    toastListeners.push(push);
    return () => {
      toastListeners = toastListeners.filter((l) => l !== push);
    };
  }, []);
  return (
    <div className="fixed bottom-4 right-4 z-[60] flex flex-col gap-2" aria-live="polite">
      {items.map((t) => (
        <div
          key={t.id}
          className={cn(
            "rounded-lg border px-3.5 py-2.5 text-sm shadow-xl backdrop-blur",
            t.kind === "err" ? "border-destructive/40 bg-card text-destructive" : "border-emerald-500/40 bg-card",
          )}
        >
          {t.text}
        </div>
      ))}
    </div>
  );
}

export const MINECRAFT_DISCLAIMER =
  "NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.";

export function Disclaimer({ id }: { id: string }) {
  return (
    <p id={id} className="mt-6 text-center text-xs text-muted-foreground">
      {MINECRAFT_DISCLAIMER}
    </p>
  );
}
