import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import type { AuthState, BotInfo, IndexResponse, OrdersResponse, StashRef } from "./types";

export const keys = {
  auth: ["auth", "state"] as const,
  stashes: ["stashes"] as const,
  index: (server: string, stash: string) => ["index", server, stash] as const,
  orders: (server: string, stash: string) => ["orders", server, stash] as const,
  bots: ["bots"] as const,
  activity: ["activity"] as const,
  guild: ["guild"] as const,
  quotaMe: ["quotas", "me"] as const,
  users: ["users"] as const,
};

export function useAuth() {
  return useQuery({
    queryKey: [...keys.auth],
    queryFn: () => api<AuthState>("/api/v1/auth/state"),
    staleTime: 15_000,
    retry: 1,
  });
}

export function permissionsOf(auth: AuthState | undefined): Set<string> {
  const out = new Set<string>();
  const push = (v: unknown) => {
    if (Array.isArray(v)) for (const p of v) if (typeof p === "string") out.add(p);
  };
  push(auth?.permissions);
  push(auth?.perms);
  push(auth?.user?.permissions);
  push(auth?.user?.perms);
  push(auth?.user?.roles);
  if (auth?.can) for (const [k, v] of Object.entries(auth.can)) if (v) out.add(k);
  if (auth?.user?.owner) for (const p of ["view", "order", "ordersAll", "activity", "positions", "guildOrders", "link", "plannerOrder"]) out.add(p);
  return out;
}

export function useStashes(enabled: boolean) {
  return useQuery({
    queryKey: [...keys.stashes],
    queryFn: () => api<StashRef[]>("/api/v1/stashes"),
    enabled,
    staleTime: 10_000,
    retry: 1,
  });
}

export function useIndex(server: string | null, stash: string | null, enabled: boolean) {
  return useQuery({
    queryKey: server && stash ? keys.index(server, stash) : ["index", "none"],
    queryFn: () => api<IndexResponse>(`/api/v1/index?server=${encodeURIComponent(server!)}&stash=${encodeURIComponent(stash!)}`),
    enabled: enabled && !!server && !!stash,
    staleTime: 5_000,
    retry: 1,
  });
}

export function useOrders(server: string | null, stash: string | null, enabled: boolean, visible: boolean) {
  return useQuery({
    queryKey: server && stash ? keys.orders(server, stash) : ["orders", "none"],
    queryFn: () => api<OrdersResponse>(`/api/v1/orders?server=${encodeURIComponent(server!)}&stash=${encodeURIComponent(stash!)}`),
    enabled: enabled && !!server && !!stash,
    // Shop polls faster while visible (3s) than in background (15s); stale errors keep last data.
    refetchInterval: enabled && server && stash ? (visible ? 3000 : 15000) : false,
    staleTime: 2_000,
    retry: 1,
  });
}

export function useBots(enabled: boolean) {
  return useQuery({
    queryKey: [...keys.bots],
    queryFn: () => api<BotInfo[]>("/api/v1/bots"),
    enabled,
    staleTime: 5_000,
    retry: 1,
  });
}

export function useGuild(enabled: boolean) {
  return useQuery({
    queryKey: [...keys.guild],
    queryFn: () => api<{ name: string; description?: string; revision?: number }>("/api/v1/guild"),
    enabled,
    staleTime: 30_000,
    retry: 1,
  });
}

export function useInvalidateOrders() {
  const qc = useQueryClient();
  return (server: string, stash: string) => qc.invalidateQueries({ queryKey: keys.orders(server, stash) });
}
