import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import {
  AuthStateSchema,
  BotActivitySchema,
  BotInfoSchema,
  GuildSchema,
  IndexResponseSchema,
  OrdersResponseSchema,
  QuotaMeSchema,
  ServerStateSchema,
  StashDefSchema,
  StashRefSchema,
  type AuthState,
  type BotActivity,
  type BotInfo,
  type Guild,
  type IndexResponse,
  type OrdersResponse,
  type Perms,
  type QuotaMe,
  type ServerState,
  type StashDef,
  type StashRef,
} from "./types";

export const keys = {
  auth: ["auth", "state"] as const,
  stashes: ["stashes"] as const,
  index: (stash: string) => ["index", stash] as const,
  orders: (stash: string) => ["orders", stash] as const,
  bots: ["bots"] as const,
  botActivity: (id: string) => ["bots", id, "activity"] as const,
  botSettings: (id: string) => ["bots", id, "settings"] as const,
  guild: ["guild"] as const,
  quotaMe: ["quotas", "me"] as const,
  users: ["users"] as const,
  server: ["server"] as const,
  stashDefs: ["stash-defs"] as const,
};

export function useAuth() {
  return useQuery({
    queryKey: [...keys.auth],
    queryFn: async () => AuthStateSchema.parse(await api<unknown>("/api/v1/auth/state")),
    staleTime: 15_000,
    retry: 1,
  });
}

/** Exact permission checks straight from Java's perms object (Users.Perms.toJson). */
export function usePermissions(auth: AuthState | undefined): {
  perms: Perms | null;
  owner: boolean;
  mustChange: boolean;
  can: (perm: keyof Perms) => boolean;
  seesStash: (stash: string) => boolean;
  seesBot: (botId: string) => boolean;
} {
  const user = auth?.user ?? null;
  const perms = user?.perms ?? null;
  const owner = user?.owner ?? false;
  // The owner implicitly holds every permission.
  const can = (perm: keyof Perms): boolean => {
    if (owner) return true;
    if (!perms) return false;
    const v = perms[perm];
    return v === true;
  };
  return {
    perms,
    owner,
    mustChange: user?.mustChange ?? false,
    can,
    seesStash: (stash) => {
      if (owner || !perms || perms.stashes == null) return true;
      return perms.stashes.some((r) => r.stash === stash);
    },
    seesBot: (botId) => {
      if (owner || !perms || perms.bots == null) return true;
      return perms.bots.includes(botId);
    },
  };
}

/** Backwards-compatible helper returning the set of granted permission keys. */
export function permissionsOf(auth: AuthState | undefined): Set<string> {
  const out = new Set<string>();
  const user = auth?.user;
  if (!user) return out;
  for (const [k, v] of Object.entries(user.perms ?? {})) if (v === true) out.add(k);
  if (user.owner) for (const p of ["view", "order", "ordersAll", "guildOrders", "positions", "activity", "settings", "rescan", "link"]) out.add(p);
  return out;
}

export function useStashes(enabled: boolean) {
  return useQuery({
    queryKey: [...keys.stashes],
    queryFn: async () => {
      const raw = await api<unknown>("/api/v1/stashes");
      return (Array.isArray(raw) ? raw : []).map((s) => StashRefSchema.parse(s)) as StashRef[];
    },
    enabled,
    staleTime: 10_000,
    retry: 1,
  });
}

export function useIndex(stash: string | null, enabled: boolean) {
  return useQuery({
    queryKey: stash ? keys.index(stash) : ["index", "none"],
    queryFn: async () =>
      IndexResponseSchema.parse(
        await api<unknown>(`/api/v1/index?stash=${encodeURIComponent(stash!)}`),
      ) as IndexResponse,
    enabled: enabled && !!stash,
    staleTime: 5_000,
    retry: 1,
  });
}

/** A 404 here means "no index for that stash" — a valid empty state, not a failure. */
export function indexEmptyState(error: unknown): boolean {
  return (error as { status?: number })?.status === 404;
}

export function useOrders(stash: string | null, enabled: boolean, visible: boolean) {
  return useQuery({
    queryKey: stash ? keys.orders(stash) : ["orders", "none"],
    queryFn: async () =>
      OrdersResponseSchema.parse(
        await api<unknown>(`/api/v1/orders?stash=${encodeURIComponent(stash!)}`),
      ) as OrdersResponse,
    enabled: enabled && !!stash,
    // Shop polls faster while visible (3s) than in background (15s); stale errors keep last data.
    refetchInterval: enabled && stash ? (visible ? 3000 : 15000) : false,
    staleTime: 2_000,
    retry: 1,
  });
}

export function useBots(enabled: boolean) {
  return useQuery({
    queryKey: [...keys.bots],
    queryFn: async () => {
      const raw = await api<unknown>("/api/v1/bots");
      return (Array.isArray(raw) ? raw : []).map((b) => BotInfoSchema.parse(b)) as BotInfo[];
    },
    enabled,
    staleTime: 5_000,
    retry: 1,
  });
}

export function useBotActivity(botId: string | null, enabled: boolean) {
  return useQuery({
    queryKey: botId ? keys.botActivity(botId) : ["bots", "none", "activity"],
    queryFn: async () =>
      BotActivitySchema.parse(await api<unknown>(`/api/v1/bots/${encodeURIComponent(botId!)}/activity`)) as BotActivity,
    enabled: enabled && !!botId,
    staleTime: 5_000,
    retry: 1,
  });
}

export function useBotSettings(botId: string | null, enabled: boolean) {
  return useQuery({
    queryKey: botId ? keys.botSettings(botId) : ["bots", "none", "settings"],
    // Schema and values for the plugin scope; may 404/409 when the bot offers nothing.
    queryFn: () => api<{ schema: unknown[]; values: unknown; schemaHash?: string; valuesHash?: string }>(`/api/v1/bots/${encodeURIComponent(botId!)}/settings?scope=plugin`),
    enabled: enabled && !!botId,
    staleTime: 10_000,
    retry: false,
  });
}

export function useGuild(enabled: boolean) {
  return useQuery({
    queryKey: [...keys.guild],
    queryFn: async () => GuildSchema.parse(await api<unknown>("/api/v1/guild")) as Guild,
    enabled,
    staleTime: 30_000,
    retry: 1,
  });
}

export function useQuotaMe(enabled: boolean) {
  return useQuery({
    queryKey: [...keys.quotaMe],
    queryFn: async () => QuotaMeSchema.parse(await api<unknown>("/api/v1/quotas/me")) as QuotaMe,
    enabled,
    staleTime: 30_000,
    retry: 1,
  });
}

export function useServer(enabled: boolean) {
  return useQuery({
    queryKey: [...keys.server],
    queryFn: async () => ServerStateSchema.parse(await api<unknown>("/api/v1/server")) as ServerState,
    enabled,
    staleTime: 15_000,
    retry: 1,
  });
}

export function useStashDefs(enabled: boolean) {
  return useQuery({
    queryKey: [...keys.stashDefs],
    queryFn: async () => {
      const raw = (await api<unknown>("/api/v1/stash-defs")) as { defs?: unknown[] };
      const list = Array.isArray(raw?.defs) ? raw.defs : [];
      return list.map((d) => StashDefSchema.parse(d)) as StashDef[];
    },
    enabled,
    staleTime: 15_000,
    retry: 1,
  });
}

export function useInvalidateOrders() {
  const qc = useQueryClient();
  return (stash: string) => qc.invalidateQueries({ queryKey: keys.orders(stash) });
}

/** Clear every cached server response on logout so the next user starts clean. */
export function useLogout() {
  const qc = useQueryClient();
  return async () => {
    try {
      await api("/api/v1/auth/logout", { method: "POST", body: {} });
    } catch {
      /* still leave */
    }
    qc.clear();
    await qc.refetchQueries({ queryKey: [...keys.auth] });
  };
}
