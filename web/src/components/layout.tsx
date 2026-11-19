import { Link, Outlet, useNavigate, useRouterState } from "@tanstack/react-router";
import { useEffect } from "react";
import { usePermissions } from "../api/queries";
import { useAuth, useLogout } from "../api/queries";
import { usePrefs } from "../stores/shop";
import { Button } from "./ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "./ui/dropdown-menu";
import { Disclaimer, ToastHost } from "./ui/feedback";
import { cn } from "../lib/utils";

function NavLink({ to, page, label }: { to: string; page: string; label: string }) {
  return (
    <Link
      to={to}
      data-page={page}
      className="rounded-full px-3.5 py-1.5 text-sm font-medium text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
      activeProps={{ className: "rounded-full px-3.5 py-1.5 text-sm font-medium transition-colors bg-primary/15 text-primary hover:bg-primary/20" }}
    >
      {label}
    </Link>
  );
}

export function Layout() {
  const auth = useAuth();
  const logout = useLogout();
  const prefs = usePrefs();
  const navigate = useNavigate();
  const pathname = useRouterState({ select: (s) => s.location.pathname });
  const perms = usePermissions(auth.data);

  useEffect(() => {
    const theme = prefs.theme === "classic" ? "classic" : "modern";
    document.body.classList.toggle("theme-classic", theme === "classic");
    document.body.classList.toggle("theme-modern", theme === "modern");
    document.documentElement.classList.toggle("dark", true);
    if (prefs.theme !== theme) prefs.setTheme(theme);
  }, [prefs]);

  useEffect(() => {
    if (auth.isSuccess && !auth.data.loggedIn && pathname !== "/login") {
      void navigate({ to: "/login", search: { redirect: pathname } });
    }
  }, [auth.isSuccess, auth.data, pathname, navigate]);

  useEffect(() => {
    const onAuth = () => {
      const here = window.location.pathname;
      void navigate({ to: "/login", search: { redirect: here === "/login" ? "/shop" : here } });
    };
    window.addEventListener("wesort:unauthorized", onAuth);
    return () => window.removeEventListener("wesort:unauthorized", onAuth);
  }, [navigate]);

  async function doLogout() {
    await logout();
    await navigate({ to: "/login", search: { redirect: "/shop" } });
  }

  const elevatedLive = !!auth.data?.loggedIn && (auth.data.elevatedUntil ?? 0) > Date.now();

  return (
    <div className="min-h-screen text-foreground">
      <header className="sticky top-0 z-40 border-b border-border/70 bg-background/85 backdrop-blur-md">
        <div className="mx-auto flex h-14 max-w-[1400px] items-center gap-2 px-4">
          <Link to="/shop" className="flex shrink-0 items-center gap-2">
            <img src="/wesort-lockup.svg" alt="WeSort" className="brand-logo h-7" />
          </Link>
          <nav className="dash-nav ml-2 hidden items-center gap-0.5 md:flex" id="dash-nav" aria-label="Pages">
            {perms.can("view") && <NavLink to="/shop" page="shop" label="Shop" />}
            {perms.can("view") && <NavLink to="/orders" page="orders" label="Orders" />}
            {perms.can("activity") && <NavLink to="/bots" page="bots" label="Bots" />}
            {perms.can("activity") && <NavLink to="/activity" page="activity" label="Activity" />}
            <NavLink to="/settings" page="settings" label="Settings" />
          </nav>
          <div className="ml-auto flex items-center gap-1.5">
            {auth.data?.user && (
              <span className={cn("mr-1 hidden text-sm text-muted-foreground", !prefs.streamer && "lg:inline")}>
                {prefs.streamer ? "Logged in" : `Logged in as ${auth.data.user.name}`}
                {elevatedLive && <span className="ml-1 rounded bg-amber-500/20 px-1.5 py-0.5 text-[11px] font-semibold text-amber-500">admin unlocked</span>}
              </span>
            )}
            <Button variant="ghost" size="icon" title={prefs.streamer ? "Streamer mode on: names hidden" : "Streamer mode off"} onClick={() => prefs.setStreamer(!prefs.streamer)} aria-pressed={prefs.streamer}>
              <span aria-hidden>{prefs.streamer ? "◉" : "◎"}</span>
            </Button>
            <Button
              variant="ghost"
              size="icon"
              title={prefs.theme === "classic" ? "Classic theme — switch to Modern" : "Modern theme — switch to Classic"}
              onClick={() => prefs.setTheme(prefs.theme === "classic" ? "modern" : "classic")}
            >
              <span aria-hidden>{prefs.theme === "classic" ? "▦" : "◐"}</span>
            </Button>
            {auth.data?.loggedIn ? (
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button variant="secondary" size="sm" className="max-w-36 truncate">
                    {prefs.streamer ? "Account" : (auth.data.user?.name ?? "Account")}
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end">
                  <DropdownMenuLabel>{prefs.streamer ? "Signed in" : (auth.data.user?.name ?? "Account")}</DropdownMenuLabel>
                  <DropdownMenuSeparator />
                  <DropdownMenuItem onSelect={() => void navigate({ to: "/settings" })}>Settings</DropdownMenuItem>
                  <DropdownMenuItem onSelect={() => void doLogout()}>Log out</DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            ) : (
              <Button variant="secondary" size="sm" onClick={() => void navigate({ to: "/login", search: { redirect: pathname === "/login" ? "/shop" : pathname } })}>
                Log in
              </Button>
            )}
          </div>
        </div>
        <nav className="dash-nav flex items-center gap-0.5 overflow-x-auto px-3 pb-2 md:hidden" id="dash-nav-mobile" aria-label="Pages">
          {perms.can("view") && <NavLink to="/shop" page="shop" label="Shop" />}
          {perms.can("view") && <NavLink to="/orders" page="orders" label="Orders" />}
          {perms.can("activity") && <NavLink to="/bots" page="bots" label="Bots" />}
          {perms.can("activity") && <NavLink to="/activity" page="activity" label="Activity" />}
          <NavLink to="/settings" page="settings" label="Settings" />
        </nav>
      </header>
      <main className="mx-auto max-w-[1400px] px-4 pb-24 pt-5 md:pb-16">
        <Outlet />
        <Disclaimer id="minecraft-disclaimer-dashboard" />
      </main>
      <ToastHost />
    </div>
  );
}
