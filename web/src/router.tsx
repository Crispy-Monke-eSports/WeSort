import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider, createBrowserHistory, createRootRoute, createRoute, createRouter, redirect } from "@tanstack/react-router";
import React from "react";
import { z } from "zod";
import { AppShell } from "./components/layout/AppShell";
import { BotsPage } from "./pages/BotsPage";
import { InventoryPage } from "./pages/InventoryPage";
import { LoginPage } from "./pages/LoginPage";
import { OperationsPage } from "./pages/OperationsPage";
import { OrdersPage } from "./pages/OrdersPage";
import { OverviewPage } from "./pages/OverviewPage";
import { SettingsPage } from "./pages/SettingsPage";

const queryClient = new QueryClient({
  defaultOptions: { queries: { refetchOnWindowFocus: false } },
});

const rootRoute = createRootRoute({ component: AppShell });

const loginSearch = z.object({ redirect: z.string().default("/overview") });
const loginRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/login",
  validateSearch: loginSearch,
  component: LoginPage,
});

const shopSearch = z.object({
  q: z.string().optional(),
  sort: z.enum(["creative", "count", "name", "id"]).optional(),
  cat: z.string().optional(),
  avail: z.enum(["all", "available", "low", "empty"]).optional(),
});

const overviewRoute = createRoute({ getParentRoute: () => rootRoute, path: "/overview", component: OverviewPage });
const inventoryRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/inventory",
  validateSearch: shopSearch,
  component: InventoryPage,
});
const ordersSearch = z.object({ q: z.string().optional() });
const ordersRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/orders",
  validateSearch: ordersSearch,
  component: OrdersPage,
});
const botsRoute = createRoute({ getParentRoute: () => rootRoute, path: "/bots", component: BotsPage });
const operationsRoute = createRoute({ getParentRoute: () => rootRoute, path: "/operations", component: OperationsPage });
const settingsRoute = createRoute({ getParentRoute: () => rootRoute, path: "/settings", component: SettingsPage });
const indexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/",
  beforeLoad: () => {
    throw redirect({ to: "/overview" });
  },
});
// Legacy routes redirect to their dashboard successors.
const shopRedirect = createRoute({
  getParentRoute: () => rootRoute,
  path: "/shop",
  beforeLoad: () => {
    throw redirect({ to: "/inventory" });
  },
});
const activityRedirect = createRoute({
  getParentRoute: () => rootRoute,
  path: "/activity",
  beforeLoad: () => {
    throw redirect({ to: "/operations" });
  },
});

const routeTree = rootRoute.addChildren([
  indexRoute, loginRoute, overviewRoute, inventoryRoute, ordersRoute, botsRoute,
  operationsRoute, settingsRoute, shopRedirect, activityRedirect,
]);

export const router = createRouter({
  routeTree,
  history: createBrowserHistory(),
  defaultPreload: "intent",
});

declare module "@tanstack/react-router" {
  interface Register {
    router: typeof router;
  }
}

export function App() {
  return (
    <React.StrictMode>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </React.StrictMode>
  );
}
