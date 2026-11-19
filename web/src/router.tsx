import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider, createMemoryHistory, createRootRoute, createRoute, createRouter, redirect } from "@tanstack/react-router";
import React from "react";
import { Layout } from "./components/layout";
import { BotsPage, ActivityPage, LoginPage, OrdersPage, SettingsPage } from "./routes/pages";
import { ShopPage } from "./routes/shop";

const queryClient = new QueryClient({
  defaultOptions: { queries: { refetchOnWindowFocus: false } },
});

const rootRoute = createRootRoute({ component: Layout });

const loginRoute = createRoute({ getParentRoute: () => rootRoute, path: "/login", component: LoginPage });
const shopRoute = createRoute({ getParentRoute: () => rootRoute, path: "/shop", component: ShopPage });
const ordersRoute = createRoute({ getParentRoute: () => rootRoute, path: "/orders", component: OrdersPage });
const botsRoute = createRoute({ getParentRoute: () => rootRoute, path: "/bots", component: BotsPage });
const activityRoute = createRoute({ getParentRoute: () => rootRoute, path: "/activity", component: ActivityPage });
const settingsRoute = createRoute({ getParentRoute: () => rootRoute, path: "/settings", component: SettingsPage });
const indexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/",
  beforeLoad: () => {
    throw redirect({ to: "/shop" });
  },
});

const routeTree = rootRoute.addChildren([indexRoute, loginRoute, shopRoute, ordersRoute, botsRoute, activityRoute, settingsRoute]);

export const router = createRouter({
  routeTree,
  history: createMemoryHistory({ initialEntries: [window.location.pathname] }),
  defaultPreload: "intent",
});

// Keep the URL in sync for direct navigation + refresh (Java serves index.html for these paths).
router.subscribe("onResolved", () => {
  const to = router.state.location.pathname + router.state.location.search;
  if (window.location.pathname + window.location.search !== to) window.history.replaceState(null, "", to);
});

export function App() {
  return (
    <React.StrictMode>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </React.StrictMode>
  );
}
