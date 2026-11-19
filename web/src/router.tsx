import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider, createBrowserHistory, createRootRoute, createRoute, createRouter, redirect } from "@tanstack/react-router";
import React from "react";
import { z } from "zod";
import { Layout } from "./components/layout";
import { BotsPage, ActivityPage, LoginPage, OrdersPage, SettingsPage } from "./routes/pages";
import { ShopPage } from "./routes/shop";

const queryClient = new QueryClient({
  defaultOptions: { queries: { refetchOnWindowFocus: false } },
});

const rootRoute = createRootRoute({ component: Layout });

const loginSearch = z.object({ redirect: z.string().default("/shop") });
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
const shopRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/shop",
  validateSearch: shopSearch,
  component: ShopPage,
});

const ordersSearch = z.object({ q: z.string().optional() });
const ordersRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/orders",
  validateSearch: ordersSearch,
  component: OrdersPage,
});

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
