import {
  useQueries,
  useQuery,
  useQueryClient,
  type UseQueryOptions,
} from "@tanstack/react-query";
import { api } from "./client";
import {
  AuthStateSchema,
  BotActivitySchema,
  BotInfoSchema,
  GuildSchema,
  HealthSchema,
  IndexResponseSchema,
  KitTemplateSchema,
  OrdersResponseSchema,
  ServerStateSchema,
  StashDefSchema,
  StashRefSchema,
  type AuthState,
  type BotActivity,
  type BotInfo,
  type Guild,
  type HealthState,
  type IndexResponse,
  type KitTemplate,
  type OrdersResponse,
  type Perms,
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
  users: ["users"] as const,
  server: ["server"] as const,
  stashDefs: ["stash-defs"] as const,
  operations: ["operations"] as const,
  physical: ["physical"] as const,
  jobs: ["jobs"] as const,
  handoffs: ["handoffs"] as const,
  custody: ["custody"] as const,
  deposits: ["deposits"] as const,
  boxJobs: ["boxes", "jobs"] as const,
  auditOrders: ["audit", "orders"] as const,
  kits: ["kits"] as const,
};

export function useAuth() {
  return useQuery({
    queryKey: [...keys.auth],
    queryFn: async () =>
      AuthStateSchema.parse(await api<unknown>("/api/v1/auth/state")),
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
  for (const [k, v] of Object.entries(user.perms ?? {}))
    if (v === true) out.add(k);
  if (user.owner)
    for (const p of [
      "view",
      "order",
      "ordersAll",
      "guildOrders",
      "positions",
      "activity",
      "settings",
      "rescan",
      "link",
    ])
      out.add(p);
  return out;
}

export function useStashes(enabled: boolean) {
  return useQuery({
    queryKey: [...keys.stashes],
    queryFn: async () => {
      const raw = await api<unknown>("/api/v1/stashes");
      return (Array.isArray(raw) ? raw : []).map((s) =>
        StashRefSchema.parse(s),
      ) as StashRef[];
    },
    enabled,
    staleTime: 10_000,
    // Live: bot online/offline arrives over the SSE stream (presence channel),
    // which invalidates this query. No interval polling.
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

export function useBots(enabled: boolean) {
  return useQuery({
    queryKey: [...keys.bots],
    queryFn: async () => {
      const raw = await api<unknown>("/api/v1/bots");
      return (Array.isArray(raw) ? raw : []).map((b) =>
        BotInfoSchema.parse(b),
      ) as BotInfo[];
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
      BotActivitySchema.parse(
        await api<unknown>(
          `/api/v1/bots/${encodeURIComponent(botId!)}/activity`,
        ),
      ) as BotActivity,
    enabled: enabled && !!botId,
    staleTime: 5_000,
    retry: 1,
  });
}

export function useBotSettings(botId: string | null, enabled: boolean) {
  return useQuery({
    queryKey: botId ? keys.botSettings(botId) : ["bots", "none", "settings"],
    // Schema and values for the plugin scope; may 404/409 when the bot offers nothing.
    queryFn: () =>
      api<{
        schema: unknown[];
        values: unknown;
        schemaHash?: string;
        valuesHash?: string;
      }>(`/api/v1/bots/${encodeURIComponent(botId!)}/settings?scope=plugin`),
    enabled: enabled && !!botId,
    staleTime: 10_000,
    retry: false,
  });
}

export function useGuild(enabled: boolean) {
  return useQuery({
    queryKey: [...keys.guild],
    queryFn: async () =>
      GuildSchema.parse(await api<unknown>("/api/v1/guild")) as Guild,
    enabled,
    staleTime: 30_000,
    retry: 1,
  });
}

export function useServer(enabled: boolean) {
  return useQuery({
    queryKey: [...keys.server],
    queryFn: async () =>
      ServerStateSchema.parse(
        await api<unknown>("/api/v1/server"),
      ) as ServerState,
    enabled,
    staleTime: 15_000,
    retry: 1,
  });
}

/**
 * Manager liveness + stuck-work signal (`/health`, open endpoint). The server
 * answers 503 while degraded — that body still carries the counters, so it is
 * parsed from the error payload instead of treated as "no data". A missing or
 * older-than-60s sample is `null` (unknown), never "ready".
 */
export function useHealth(enabled: boolean) {
  return useQuery({
    queryKey: ["health"] as const,
    queryFn: async (): Promise<HealthState | null> => {
      try {
        return HealthSchema.parse(await api<unknown>("/health"));
      } catch (e) {
        const body = (e as { body?: unknown })?.body;
        if (body != null) {
          try {
            return HealthSchema.parse(body);
          } catch {
            return null;
          }
        }
        return null;
      }
    },
    enabled,
    staleTime: 10_000,
    refetchInterval: 30_000,
    retry: 1,
  });
}

export function useStashDefs(enabled: boolean) {
  return useQuery({
    queryKey: [...keys.stashDefs],
    queryFn: async () => {
      const raw = (await api<unknown>("/api/v1/stash-defs")) as {
        defs?: unknown[];
      };
      const list = Array.isArray(raw?.defs) ? raw.defs : [];
      return list.map((d) => StashDefSchema.parse(d)) as StashDef[];
    },
    enabled,
    staleTime: 15_000,
    retry: 1,
  });
}

/** Owner-managed kit templates (one kit is exactly one box, packed on demand). */
export function useKitTemplates(enabled: boolean) {
  return useQuery({
    queryKey: [...keys.kits],
    queryFn: async () => {
      const raw = (await api<unknown>("/api/v1/kits")) as { kits?: unknown[] };
      const list = Array.isArray(raw?.kits) ? raw.kits : [];
      return list.map((k) => KitTemplateSchema.parse(k)) as KitTemplate[];
    },
    enabled,
    staleTime: 15_000,
    retry: 1,
  });
}

export function useInvalidateOrders() {
  const qc = useQueryClient();
  return (stash: string) =>
    qc.invalidateQueries({ queryKey: keys.orders(stash) });
}

export interface OpsList {
  [key: string]: unknown;
}

function useOwnerList(
  key: readonly string[],
  path: string,
  enabled: boolean,
  query = "",
) {
  return useQuery({
    queryKey: [...key, query],
    queryFn: async () => (await api<unknown>(`${path}${query}`)) as OpsList,
    enabled,
    staleTime: 10_000,
    retry: 1,
  });
}

export interface StashOrder {
  stash: string;
  id: string;
  status: string;
  lines: { type: string; key: string; count: number }[];
  message?: string | null;
  createdAt?: number;
  updatedAt?: number;
  [key: string]: unknown;
}

/**
 * Canonical per-stash orders cache entry: every consumer reads the same
 * `OrdersResponse` under `keys.orders(stash)` and transforms afterwards.
 * (Two shapes under one key previously let Inventory and Orders poison
 * each other's cache on SPA navigation.)
 */
export function ordersQueryOptions(
  stash: string,
  enabled: boolean,
): UseQueryOptions<OrdersResponse> {
  return {
    queryKey: keys.orders(stash),
    queryFn: async () =>
      OrdersResponseSchema.parse(
        await api<unknown>(`/api/v1/orders?stash=${encodeURIComponent(stash)}`),
      ) as OrdersResponse,
    enabled,
    staleTime: 2_000,
    retry: 1 as const,
  };
}

/**
 * Orders across all visible stashes, newest first. Reads the canonical
 * `OrdersResponse` cache entries (shared with Inventory) and projects them
 * to `StashOrder[]`. A stash whose API fails is reported in `failedStashes`
 * — never silently treated as an empty order list.
 */
export function useAllOrders(
  stashes: { stash: string }[],
  enabled: boolean,
): { orders: StashOrder[]; failedStashes: string[]; loading: boolean } {
  const results = useQueries({
    queries: stashes.map(
      (s): UseQueryOptions<OrdersResponse, Error, StashOrder[]> => ({
        ...ordersQueryOptions(s.stash, enabled),
        select: (d: OrdersResponse) =>
          (d.orders ?? []).map(
            (o: any) => ({ ...o, stash: s.stash }) as StashOrder,
          ),
      }),
    ),
  });
  const orders = results
    .flatMap((r: { data?: StashOrder[] }) => r.data ?? [])
    .sort((a, b) => (b.createdAt ?? 0) - (a.createdAt ?? 0));
  const failedStashes = results
    .map((r: { isError: boolean }, i: number) =>
      r.isError ? stashes[i].stash : null,
    )
    .filter((s): s is string => s != null);
  const loading = results.some((r: { isLoading: boolean }) => r.isLoading);
  return { orders, failedStashes, loading };
}

/** Global operations view: stock/agent/order states plus alerts (activity perm). */
export function useOperations(enabled: boolean) {
  return useQuery({
    queryKey: [...keys.operations],
    queryFn: async () => (await api<unknown>("/api/v1/operations")) as OpsList,
    enabled,
    staleTime: 5_000,
    retry: 1,
  });
}

/** Owner-only operational lists. Each returns the raw JSON object for table display. */
export function usePhysicalOps(enabled: boolean) {
  return useOwnerList([...keys.physical], "/api/v1/physical", enabled);
}
export function useJobQueue(enabled: boolean) {
  return useOwnerList([...keys.jobs], "/api/v1/jobs", enabled);
}
export function useHandoffs(enabled: boolean, order = "") {
  return useOwnerList(
    [...keys.handoffs],
    "/api/v1/handoffs",
    enabled,
    order ? `?order=${encodeURIComponent(order)}` : "",
  );
}
export function useCustody(enabled: boolean, order = "") {
  return useOwnerList(
    [...keys.custody],
    "/api/v1/custody",
    enabled,
    order ? `?order=${encodeURIComponent(order)}` : "",
  );
}
export function useDeposits(enabled: boolean, order = "") {
  return useOwnerList(
    [...keys.deposits],
    "/api/v1/deposits",
    enabled,
    order ? `?order=${encodeURIComponent(order)}` : "",
  );
}
export function useBoxJobs(enabled: boolean) {
  return useOwnerList([...keys.boxJobs], "/api/v1/boxes/jobs", enabled);
}
export function useAuditOrders(
  enabled: boolean,
  params: {
    limit?: number;
    order?: string;
    person?: string;
    item?: string;
    stash?: string;
  },
) {
  const q = new URLSearchParams();
  if (params.limit) q.set("limit", String(params.limit));
  if (params.order) q.set("order", params.order);
  if (params.person) q.set("person", params.person);
  if (params.item) q.set("item", params.item);
  if (params.stash) q.set("stash", params.stash);
  const qs = q.toString();
  return useQuery({
    queryKey: [...keys.auditOrders, qs],
    queryFn: async () =>
      (await api<unknown>(
        `/api/v1/audit/orders${qs ? `?${qs}` : ""}`,
      )) as OpsList,
    enabled,
    staleTime: 10_000,
    retry: 1,
  });
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

/** #202: owner-only operational mutations (Rescan / owner-force). UI gates on perms.owner. */
export function useRescan() {
  const qc = useQueryClient();
  return async (stash: string): Promise<{ ok: boolean; rescanId?: string }> => {
    const res = await api<{ ok: boolean; rescanId?: string }>(
      "/api/v1/rescan",
      {
        method: "POST",
        body: { stash },
      },
    );
    await qc.invalidateQueries({ queryKey: [...keys.stashes] });
    await qc.invalidateQueries({ queryKey: [...keys.operations] });
    return res;
  };
}

export function useOwnerForce() {
  const qc = useQueryClient();
  return async (orderId: string, stash?: string): Promise<{ ok: boolean }> => {
    const res = await api<{ ok: boolean }>(
      `/api/v1/orders/${orderId}/owner-force`,
      {
        method: "POST",
        body: {},
      },
    );
    if (stash) await qc.invalidateQueries({ queryKey: keys.orders(stash) });
    await qc.invalidateQueries({ queryKey: [...keys.operations] });
    return res;
  };
}
