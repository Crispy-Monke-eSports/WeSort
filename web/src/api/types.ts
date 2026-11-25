import { z } from "zod";

/* Exact TypeScript models of the Java Manager's JSON responses.
 * Field names and shapes verified against AuthRoutes, Users, BrowserRoutes,
 * OrderRoutes/OrderStore, DashboardRoutes/BotHub, GuildRoutes,
 * UserRoutes and OrderAuditRoutes. Unknown extra fields are ignored. */

// ---------- auth ----------

export const PermsSchema = z.object({
  view: z.boolean().default(false),
  order: z.boolean().default(false),
  ordersAll: z.boolean().default(false),
  guildOrders: z.boolean().default(false),
  positions: z.boolean().default(false),
  activity: z.boolean().default(false),
  settings: z.boolean().default(false),
  rescan: z.boolean().default(false),
  link: z.boolean().default(false),
  stashes: z
    .array(z.object({ stash: z.string() }))
    .nullable()
    .default(null),
  bots: z.array(z.string()).nullable().default(null),
});
export type Perms = z.infer<typeof PermsSchema>;

export const AuthUserSchema = z.object({
  id: z.string(),
  name: z.string(),
  owner: z.boolean().default(false),
  mustChange: z.boolean().default(false),
  perms: PermsSchema.prefault({}),
});
export type AuthUser = z.infer<typeof AuthUserSchema>;

export const AuthStateSchema = z.object({
  setupNeeded: z.boolean().default(false),
  setupAllowed: z.boolean().default(false),
  codeRequired: z.boolean().default(false),
  loggedIn: z.boolean().default(false),
  elevated: z.boolean().default(false),
  elevatedUntil: z.number().default(0),
  minLength: z.number().default(12),
  user: AuthUserSchema.optional(),
});
export type AuthState = z.infer<typeof AuthStateSchema>;

// ---------- stashes / index ----------

export const StashSummarySchema = z.object({
  open: z.number().default(0),
  active: z
    .object({
      id: z.string(),
      status: z.string(),
      phase: z.string().nullable().optional(),
      progress: z.unknown().nullable().optional(),
    })
    .nullable()
    .optional(),
});

export const StashRefSchema = z.object({
  stash: z.string(),
  player: z.string().nullable().optional(),
  online: z.boolean().default(false),
  lastSeen: z.number().nullable().optional(),
  indexedAt: z.number().nullable().optional(),
  itemTypes: z.number().default(0),
  itemTotal: z.number().default(0),
  delivery: z
    .object({
      x: z.number(),
      y: z.number(),
      z: z.number(),
      dimension: z.string().nullable().optional(),
    })
    .nullable()
    .optional(),
  naming: z
    .object({
      anvil: z.boolean().default(false),
      anvilSource: z.string().nullable().optional(),
    })
    .nullable()
    .optional(),
  crafting: z
    .object({ enabled: z.boolean().default(false) })
    .nullable()
    .optional(),
  deliveryStorage: z.unknown().nullable().optional(),
  orderBlocker: z.string().nullable().optional(),
  // Java serializes these stall flags as booleans or epoch timestamps (or explicit nulls).
  rescanPending: z
    .union([z.boolean(), z.number()])
    .nullish()
    .transform((v) => (typeof v === "number" ? v > 0 : (v ?? false))),
  rescanBlocked: z
    .union([z.boolean(), z.number()])
    .nullish()
    .transform((v) => (typeof v === "number" ? v > 0 : (v ?? false))),
  owner: z.string().nullable().optional(),
  conflict: z.unknown().nullable().optional(),
  orders: StashSummarySchema.optional(),
});
export type StashRef = z.infer<typeof StashRefSchema>;

export const VecSchema = z.object({
  x: z.number().int(),
  y: z.number().int(),
  z: z.number().int(),
});
export type Vec = z.infer<typeof VecSchema>;

export const StashDefSchema = z.object({
  name: z.string(),
  dimension: z.string().default("minecraft:overworld"),
  // Coordinates are owner/positions-only: redacted responses omit them.
  pos1: VecSchema.optional(),
  pos2: VecSchema.optional(),
  dropoff: VecSchema.nullable().optional(),
  dropoffDimension: z.string().nullable().optional(),
  delivery: VecSchema.nullable().optional(),
  deliveryDimension: z.string().nullable().optional(),
  anvil: VecSchema.nullable().optional(),
  table: VecSchema.nullable().optional(),
  revision: z.number().default(1),
  updatedAt: z.number().default(0),
});
export type StashDef = z.infer<typeof StashDefSchema>;

export const ServerStateSchema = z.object({
  server: z.string().default(""),
  configured: z.boolean().default(false),
});
export type ServerState = z.infer<typeof ServerStateSchema>;

/**
 * Defensively parsed Manager `/health` (RuntimeHealth.detail). Every field is
 * optional with a safe default: the joint-health branch adds `recovering`,
 * aged `stuckOrderIds` entries and stale-code detail, but older servers only
 * send the base counters — both must parse.
 */
export const HealthSchema = z.object({
  status: z.string().nullable().optional(),
  staleIndex: z
    .boolean()
    .nullish()
    .transform((v) => v ?? false),
  stuckOrders: z
    .number()
    .nullish()
    .transform((v) => v ?? 0),
  stuckOrderIds: z.unknown().nullable().optional(),
  recovering: z.number().nullable().optional(),
  reservationHoldingOrders: z
    .number()
    .nullish()
    .transform((v) => v ?? 0),
  warnings: z.array(z.string()).nullish(),
  staleStock: z.boolean().nullish(),
  oldestStockAgeMs: z.number().nullish(),
  oldestIndexAgeMs: z.number().nullable().optional(),
  checkedAt: z.number().nullable().optional(),
  userRegistryUnavailable: z.boolean().nullable().optional(),
});
export type HealthState = z.infer<typeof HealthSchema>;

const BoxContentSchema = z.object({
  slot: z.number(),
  key: z.string(),
  count: z.number(),
});

export const IndexItemSchema = z.object({
  key: z.string(),
  id: z.string(),
  name: z.string().nullable().optional(),
  englishName: z.string().nullable().optional(),
  count: z.number().default(0),
  maxStack: z.number().default(64),
  contents: z.array(BoxContentSchema).nullable().optional(),
  tab: z.string().nullable().optional(),
  tabOrder: z.number().nullable().optional(),
});
export type IndexItem = z.infer<typeof IndexItemSchema>;

export const ShulkerEntrySchema = z.object({
  key: z.string(),
  id: z.string().default("minecraft:shulker_box"),
  itemKey: z.string().nullable().optional(),
  count: z.number().default(0),
  name: z.string().nullable().optional(),
  contents: z.array(BoxContentSchema).nullable().optional(),
});
export type ShulkerEntry = z.infer<typeof ShulkerEntrySchema>;

export const IndexResponseSchema = z.object({
  mcVersion: z.string().nullable().optional(),
  items: z.array(IndexItemSchema).default([]),
  shulkers: z.array(ShulkerEntrySchema).default([]),
});
export type IndexResponse = z.infer<typeof IndexResponseSchema>;

// ---------- orders ----------

export const OrderLineSchema = z.object({
  type: z.enum(["item", "shulker", "kit", "craft"]),
  key: z.string().min(1),
  count: z.number().int().min(1),
});
export type OrderLine = z.infer<typeof OrderLineSchema>;

// ---------- kits ----------

/** One filled box slot of a kit template: 0-based slot, exact item variant key, count. */
export const KitSlotSchema = z.object({
  slot: z.number().int().min(0).max(26),
  key: z.string().min(1),
  count: z.number().int().min(1),
});
export type KitSlot = z.infer<typeof KitSlotSchema>;

/** An owner-managed kit template: what one ordered shulker box holds, slot for slot. */
export const KitTemplateSchema = z.object({
  name: z.string().min(1),
  revision: z.number().default(1),
  updatedAt: z.number().default(0),
  slots: z.array(KitSlotSchema).default([]),
});
export type KitTemplate = z.infer<typeof KitTemplateSchema>;

export const KitListResponseSchema = z.object({
  kits: z.array(KitTemplateSchema).default([]),
});
export type KitListResponse = z.infer<typeof KitListResponseSchema>;

export const OrderStatusSchema = z.object({
  id: z.string(),
  server: z.string().optional(),
  stash: z.string().optional(),
  status: z.string().default("pending"),
  stage: z.string().nullable().optional(),
  phase: z.string().nullable().optional(),
  message: z.string().nullable().optional(),
  progress: z.unknown().nullable().optional(),
  lines: z.array(OrderLineSchema).default([]),
  createdAt: z.number().optional(),
  updatedAt: z.number().optional(),
  claimedAt: z.number().nullable().optional(),
  agent: z.string().nullable().optional(),
  purpose: z.string().nullable().optional(),
  recipient: z.string().nullable().optional(),
  declaredRecipient: z.string().nullable().optional(),
  verifiedRecipient: z.string().nullable().optional(),
  deliveryMode: z.string().nullable().optional(),
  comment: z.string().nullable().optional(),
  approvalState: z.string().nullable().optional(),
  decidedBy: z.string().nullable().optional(),
  packageTotal: z.number().nullable().optional(),
  cancelRequested: z.boolean().default(false),
  // Set when a terminal order was placed again: it stands for its successor (OrderStore.statusOf
  // follows the chain). Present in backend toJson; parsed here so lists can hide superseded orders.
  retriedAs: z.string().nullable().optional(),
  requestedBy: z.string().nullable().optional(),
  placedBy: z
    .object({
      id: z.string().nullable().optional(),
      name: z.string().nullable().optional(),
    })
    .nullable()
    .optional(),
  names: z.unknown().nullable().optional(),
  history: z.array(z.unknown()).optional(),
  events: z.array(z.unknown()).optional(),
});
export type OrderStatus = z.infer<typeof OrderStatusSchema>;

export const OrdersResponseSchema = z.object({
  orders: z.array(OrderStatusSchema).default([]),
  available: z
    .object({
      items: z.record(z.string(), z.number()).default({}),
      shulkers: z.record(z.string(), z.number()).default({}),
      kits: z.record(z.string(), z.number()).default({}),
      craftables: z.record(z.string(), z.number()).default({}),
    })
    .default({ items: {}, shulkers: {}, kits: {}, craftables: {} }),
});
export type OrdersResponse = z.infer<typeof OrdersResponseSchema>;

// ---------- bots / activity ----------

export const BotInfoSchema = z.object({
  botId: z.string(),
  player: z.string().nullable().optional(),
  online: z.boolean().default(false),
  lastSeen: z.number().nullable().optional(),
  lastHeartbeat: z.number().nullable().optional(),
  plugin: z.unknown().nullable().optional(),
  runtime: z.unknown().nullable().optional(),
  caps: z.array(z.string()).default([]),
  summary: z.string().nullable().optional(),
  activityAt: z.number().nullable().optional(),
  scopes: z.array(z.string()).default([]),
  stashes: z.array(z.object({ stash: z.string() })).default([]),
  lastLocation: z.unknown().nullable().optional(),
  // Joint-health additions: per-bot stall cause and lease identity. Optional
  // so older servers that do not send them still parse; the UI falls back to
  // the order/journal tables when they are absent.
  cause: z.string().nullable().optional(),
  currentLeaseFingerprint: z.string().nullable().optional(),
  leaseFingerprint: z.string().nullable().optional(),
  leaseAgeMs: z.number().nullable().optional(),
  leaseSince: z.number().nullable().optional(),
  managerState: z.string().nullable().optional(),
  inspectionState: z.string().nullable().optional(),
});
export type BotInfo = z.infer<typeof BotInfoSchema>;

export const BotActivitySchema = z.object({
  activity: z.unknown().nullable().optional(),
  receivedAt: z.number().nullable().optional(),
  events: z.array(z.unknown()).default([]),
  online: z.boolean().default(false),
});
export type BotActivity = z.infer<typeof BotActivitySchema>;

// ---------- guild / users ----------

export const GuildSchema = z.object({
  name: z.string().default(""),
  description: z.string().nullable().optional(),
  revision: z.number().default(0),
  approvalPolicy: z.string().nullable().optional(),
});
export type Guild = z.infer<typeof GuildSchema>;

export const ManagedUserSchema = z.object({
  id: z.string(),
  name: z.string(),
  minecraftAccount: z.string().nullable().optional(),
  disabled: z.boolean().default(false),
  mustChange: z.boolean().default(false),
  perms: PermsSchema.prefault({}),
  createdAt: z.number().optional(),
  lastLoginAt: z.number().nullable().optional(),
  sessions: z.number().optional(),
});
export type ManagedUser = z.infer<typeof ManagedUserSchema>;
