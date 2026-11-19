import { Link, Outlet, useNavigate, useRouterState } from "@tanstack/react-router";
import { useEffect } from "react";
import { api } from "../api/client";
import { permissionsOf, useAuth } from "../api/queries";
import { usePrefs } from "../stores/shop";
import { Disclaimer } from "./ui";

export function Layout() {
  const auth = useAuth();
  const prefs = usePrefs();
  const navigate = useNavigate();
  const pathname = useRouterState({ select: (s) => s.location.pathname });

  useEffect(() => {
    document.body.classList.toggle("theme-classic", prefs.theme === "classic");
    document.body.classList.toggle("theme-clean", prefs.theme !== "classic");
  }, [prefs.theme]);

  useEffect(() => {
    if (auth.isSuccess && !auth.data.loggedIn && pathname !== "/login") void navigate({ to: "/login" });
  }, [auth.isSuccess, auth.data, pathname, navigate]);

  useEffect(() => {
    const onAuth = () => void navigate({ to: "/login" });
    window.addEventListener("wesort:unauthorized", onAuth);
    return () => window.removeEventListener("wesort:unauthorized", onAuth);
  }, [navigate]);

  const perms = permissionsOf(auth.data);
  const can = (p: string) => perms.has(p);

  async function logout() {
    try {
      await api("/api/v1/auth/logout", { method: "POST", body: {} });
    } catch {
      /* still leave */
    }
    await auth.refetch();
    await navigate({ to: "/login" });
  }

  return (
    <div className="min-h-screen text-stone-100">
      <header className="flex items-center gap-3 border-b border-stone-700/60 bg-black/60 px-4 py-2">
        <img src="/wesort-lockup.svg" alt="WeSort" className="brand-logo h-8" />
        <strong className="text-lg">WeSort</strong>
    <nav className="dash-nav ml-4 flex gap-2" id="dash-nav" aria-label="Pages">
          {can("view") && <Link to="/shop" data-page="shop" className="mc-button nav-btn" activeProps={{ className: "mc-button nav-btn selected" }}>Shop</Link>}
          {can("view") && <Link to="/orders" data-page="orders" className="mc-button nav-btn" activeProps={{ className: "mc-button nav-btn selected" }}>Orders</Link>}
          {can("activity") && <Link to="/bots" data-page="bots" className="mc-button nav-btn" activeProps={{ className: "mc-button nav-btn selected" }}>Bots</Link>}
          {can("activity") && <Link to="/activity" data-page="activity" className="mc-button nav-btn" id="nav-activity" activeProps={{ className: "mc-button nav-btn selected" }}>Activity</Link>}
          <Link to="/settings" data-page="settings" className="mc-button nav-btn" activeProps={{ className: "mc-button nav-btn selected" }}>Settings</Link>
        </nav>
        <div className="ml-auto flex items-center gap-2 text-sm">
          <span>{auth.data?.user ? `Logged in as ${auth.data.user.name}` : ""}</span>
          <button
            type="button"
            className="mc-button small"
            onClick={() => prefs.setStreamer(!prefs.streamer)}
            aria-pressed={prefs.streamer}
            title="Streamer mode hides server and player names"
          >
            {prefs.streamer ? "Streamer on" : "Streamer off"}
          </button>
          <button
            type="button"
            className="mc-button small"
            onClick={() => prefs.setTheme(prefs.theme === "classic" ? "clean" : "classic")}
            title="Toggle Classic dirt / clean theme"
          >
            {prefs.theme === "classic" ? "Classic" : "Clean"}
          </button>
          {auth.data?.loggedIn && (
            <button type="button" className="mc-button small" onClick={logout}>
              Log out
            </button>
          )}
        </div>
      </header>
      <main className="mx-auto max-w-6xl p-4">
        <Outlet />
        <Disclaimer id="minecraft-disclaimer-dashboard" />
      </main>
      <nav className="only-mobile fixed bottom-0 left-0 right-0 flex justify-around border-t border-stone-700 bg-black/80 p-2" aria-label="Pages">
        {can("view") && <Link to="/shop" className="bnav-btn">Shop</Link>}
        {can("activity") && <Link to="/bots" className="bnav-btn">Bots</Link>}
        <Link to="/settings" className="bnav-btn">More</Link>
      </nav>
    </div>
  );
}
