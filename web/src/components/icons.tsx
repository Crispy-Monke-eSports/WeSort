import { useEffect, useRef, useState } from "react";

/** Item icons are rendered by Java: GET /icon/<namespace>/<path>.png (verified 26.3 client).
 *  `?v=<mc>-g1` picks the game jar; icons render in the background, so one delayed
 *  retry catches items whose render hasn't finished when the grid first paints.
 *  A letter tile shows underneath until the artwork loads (never a broken image). */
export function iconUrl(id: string, mcVersion?: string | null): string {
  const hash = id.indexOf("#");
  const bare = (hash < 0 ? id : id.slice(0, hash)).trim();
  const colon = bare.indexOf(":");
  const ns = colon < 0 ? "minecraft" : bare.slice(0, colon).trim() || "minecraft";
  const path = (colon < 0 ? bare : bare.slice(colon + 1)).replace(/[^a-z0-9/_. -]/gi, "").trim() || "unknown";
  const base = `/icon/${encodeURIComponent(ns)}/${encodeURIComponent(path)}.png`;
  return mcVersion ? `${base}?v=${encodeURIComponent(mcVersion)}-g1` : base;
}

export function ItemIcon({
  id,
  size = 48,
  contents,
  label,
  mcVersion,
}: {
  id: string;
  size?: number;
  contents?: { key: string; count: number }[] | null;
  label?: string;
  mcVersion?: string | null;
}) {
  const [loaded, setLoaded] = useState(false);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const timer = useRef<number | null>(null);
  const title = contents?.length ? `Contains: ${contents.map((c) => `${c.key} ×${c.count}`).join(", ")}` : id;
  const initial = (label ?? id.split(":").pop() ?? "?").trim().charAt(0).toUpperCase() || "?";

  useEffect(() => {
    setLoaded(false);
    setFailed(false);
    setAttempt(0);
    return () => {
      if (timer.current !== null) window.clearTimeout(timer.current);
    };
  }, [id, mcVersion]);

  const src = attempt === 0 ? iconUrl(id, mcVersion) : `${iconUrl(id, mcVersion)}${mcVersion ? "&" : "?"}retry=1`;

  return (
    <span className="relative inline-flex items-center justify-center" style={{ width: size, height: size }} title={title}>
      <span aria-hidden className="absolute inset-0 flex items-center justify-center rounded font-mono text-xl font-bold text-emerald-200/70">
        {initial}
      </span>
      {!failed && (
        <img
          src={src}
          width={size}
          height={size}
          alt=""
          draggable={false}
          onLoad={() => setLoaded(true)}
          onError={() => {
            if (attempt === 0) {
              // The Manager renders icons in the background; a single delayed retry
              // catches items whose render wasn't ready on first paint.
              timer.current = window.setTimeout(() => setAttempt(1), 1200);
            } else {
              setFailed(true);
            }
          }}
          className="mc-slot absolute inset-0"
          style={{ opacity: loaded ? 1 : 0, transition: "opacity 0.2s" }}
        />
      )}
    </span>
  );
}
