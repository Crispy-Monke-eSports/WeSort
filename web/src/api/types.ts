export interface StashRef {
  server: string;
  stash: string;
  online?: boolean;
  indexedAt?: number;
  player?: string;
  deliveryConfigured?: boolean;
  orderBlocker?: string | null;
  naming?: { anvil?: boolean; anvilSource?: string } | null;
  delivery?: { x: number; y: number; z: number; dimension?: string } | null;
}

export interface IndexItem {
  key: string;
  id: string;
  name?: string;
  englishName?: string;
  count: number;
  maxStack: number;
  contents?: { slot: number; key: string; count: number }[];
  tab?: string;
  tabOrder?: number;
}

export interface ShulkerEntry {
  key: string;
  id: string;
  itemKey?: string;
  count: number;
  contents?: { slot: number; key: string; count: number }[];
  name?: string;
}

export interface IndexResponse {
  mcVersion?: string;
  items: IndexItem[];
  shulkers: ShulkerEntry[];
}

export interface OrderLine {
  type: "item" | "shulker";
  key: string;
  count: number;
}

export interface OrderStatus {
  id: string;
  status: string;
  stage?: string;
  lines?: OrderLine[];
  createdAt?: number;
  updatedAt?: number;
  message?: string;
  progress?: any;
  placedBy?: { id?: string; name?: string } | null;
}

export interface OrdersResponse {
  orders: OrderStatus[];
  available: { items: Record<string, number>; shulkers: Record<string, number> };
}

export interface AuthState {
  loggedIn: boolean;
  elevated?: boolean;
  elevatedUntil?: number;
  user?: {
    id: string;
    name: string;
    owner?: boolean;
    permissions?: string[];
    perms?: string[];
    roles?: string[];
  };
  permissions?: string[];
  perms?: string[];
  can?: Record<string, boolean>;
}

export interface BotInfo {
  id: string;
  player?: string;
  online?: boolean;
  server?: string;
  stash?: string;
  version?: string;
}
