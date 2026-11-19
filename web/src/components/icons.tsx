import { useState } from "react";

export function ItemIcon({ id, size = 32, contents }: { id: string; size?: number; contents?: { key: string; count: number }[] | null }) {
  // Icons are served by Java (/icons or /icon). Missing icons fall back to a local placeholder
  // after one retry; titles carry contents for shulker previews.
  const [err, setErr] = useState(0);
  const title = contents?.length ? `Contains: ${contents.map((c) => `${c.key} ×${c.count}`).join(", ")}` : id;
  if (err > 1) {
    return <img src="/placeholder.svg" width={size} height={size} alt={id} title={title} className="mc-slot" draggable={false} />;
  }
  const src = err === 0 ? `/icons/${encodeURIComponent(id)}.png` : `/icon?id=${encodeURIComponent(id)}`;
  return (
    <img
      src={src}
      width={size}
      height={size}
      alt={id}
      title={title}
      className="mc-slot"
      draggable={false}
      onError={() => setErr((n) => n + 1)}
    />
  );
}

export function maskServer(name: string, streamer: boolean): string {
  return streamer ? "Hidden" : name;
}
