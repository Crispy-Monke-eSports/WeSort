/**
 * User stash/bot scope helpers (P0-01). The backend distinguishes three states:
 * null = unlimited ("All"), [] = none ("no access"), and a sorted list of names.
 * The editor must always send explicit scope keys: an absent key would be
 * ambiguous with unlimited. A scoped user must never be identified as an
 * unlimited preset, even when the boolean flags happen to match.
 */

export function stashListOf(perms: any): string[] {
  const s = perms?.stashes;
  return Array.isArray(s)
    ? s.map((x: any) => String(x?.stash ?? x)).filter(Boolean)
    : [];
}

export function botListOf(perms: any): string[] {
  const b = perms?.bots;
  return Array.isArray(b) ? b.map((x: any) => String(x)).filter(Boolean) : [];
}

/** Canonical scope identity: "all" for null, "sel:…" for a list ([] = none). Order-insensitive. */
export function scopeKey(v: unknown): string {
  if (v == null) return "all";
  if (!Array.isArray(v)) return "all";
  const items = v
    .map((x) => (typeof x === "string" ? x : String((x as any)?.stash ?? "")))
    .filter(Boolean)
    .sort();
  return `sel:${items.join(",")}`;
}

/** True only when flags AND both scopes match the preset definition. */
export function presetMatches(
  def: any,
  perms: any,
  flagFields: readonly string[],
): boolean {
  if (!flagFields.every((f) => !!def?.[f] === !!perms?.[f])) return false;
  if (scopeKey(def?.stashes) !== scopeKey(perms?.stashes)) return false;
  if (scopeKey(def?.bots) !== scopeKey(perms?.bots)) return false;
  return true;
}

/** Complete permission payload: flags plus always-explicit scope keys. */
export function buildUserPerms(
  flags: Record<string, boolean>,
  flagFields: readonly string[],
  stashMode: "all" | "selected",
  stashes: string[],
  botMode: "all" | "selected",
  bots: string[],
): Record<string, unknown> {
  const perms: Record<string, unknown> = {};
  for (const f of flagFields) perms[f] = !!flags[f];
  perms.stashes =
    stashMode === "all"
      ? null
      : [...new Set(stashes)].sort().map((stash) => ({ stash }));
  perms.bots = botMode === "all" ? null : [...new Set(bots)].sort();
  return perms;
}

export function scopeLabel(v: string[] | null): string {
  if (v == null) return "All";
  return v.length ? v.join(", ") : "None";
}

/** Human diff lines for scope changes (empty when unchanged). */
export function scopeDiff(
  beforeStashes: string[] | null,
  nextStashes: string[] | null,
  beforeBots: string[] | null,
  nextBots: string[] | null,
): string[] {
  const out: string[] = [];
  if (JSON.stringify(beforeStashes) !== JSON.stringify(nextStashes)) {
    out.push(
      `stashes: ${scopeLabel(beforeStashes)} → ${scopeLabel(nextStashes)}`,
    );
  }
  if (JSON.stringify(beforeBots) !== JSON.stringify(nextBots)) {
    out.push(`bots: ${scopeLabel(beforeBots)} → ${scopeLabel(nextBots)}`);
  }
  return out;
}
