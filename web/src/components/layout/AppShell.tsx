import AccountCircleIcon from "@mui/icons-material/AccountCircle";
import Brightness4Icon from "@mui/icons-material/Brightness4";
import Brightness7Icon from "@mui/icons-material/Brightness7";
import ChevronLeftIcon from "@mui/icons-material/ChevronLeft";
import DashboardIcon from "@mui/icons-material/Dashboard";
import InventoryIcon from "@mui/icons-material/Inventory";
import LogoutIcon from "@mui/icons-material/Logout";
import MenuIcon from "@mui/icons-material/Menu";
import ReceiptIcon from "@mui/icons-material/Receipt";
import SettingsIcon from "@mui/icons-material/Settings";
import SmartToyIcon from "@mui/icons-material/SmartToy";
import TimelineIcon from "@mui/icons-material/Timeline";
import AppBar from "@mui/material/AppBar";
import Avatar from "@mui/material/Avatar";
import Box from "@mui/material/Box";
import Chip from "@mui/material/Chip";
import CssBaseline from "@mui/material/CssBaseline";
import Divider from "@mui/material/Divider";
import Drawer from "@mui/material/Drawer";
import IconButton from "@mui/material/IconButton";
import List from "@mui/material/List";
import ListItem from "@mui/material/ListItem";
import ListItemButton from "@mui/material/ListItemButton";
import ListItemIcon from "@mui/material/ListItemIcon";
import ListItemText from "@mui/material/ListItemText";
import Menu from "@mui/material/Menu";
import MenuItem from "@mui/material/MenuItem";
import Toolbar from "@mui/material/Toolbar";
import Typography from "@mui/material/Typography";
import useMediaQuery from "@mui/material/useMediaQuery";
import { useColorScheme, useTheme } from "@mui/material/styles";
import { Link, Outlet, useLocation, useNavigate } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { useAuth, useLogout, usePermissions } from "../../api/queries";
import { useLiveUpdates } from "../../api/live";
import { LiveBadge } from "../LiveBadge";
import { ToastHost } from "../ToastHost";

const DRAWER_WIDTH = 240;

const NAV = [
  {
    to: "/overview",
    label: "Overview",
    icon: <DashboardIcon />,
    perm: "view" as const,
  },
  {
    to: "/inventory",
    label: "Inventory",
    icon: <InventoryIcon />,
    perm: "view" as const,
  },
  {
    to: "/orders",
    label: "Orders",
    icon: <ReceiptIcon />,
    perm: "view" as const,
  },
  {
    to: "/bots",
    label: "Bots",
    icon: <SmartToyIcon />,
    perm: "activity" as const,
  },
  {
    to: "/operations",
    label: "Operations",
    icon: <TimelineIcon />,
    perm: "activity" as const,
  },
  {
    to: "/settings",
    label: "Settings",
    icon: <SettingsIcon />,
    perm: "settings" as const,
  },
];

export function AppShell() {
  const theme = useTheme();
  const isDesktop = useMediaQuery(theme.breakpoints.up("md"));
  const [mobileOpen, setMobileOpen] = useState(false);
  const [anchor, setAnchor] = useState<null | HTMLElement>(null);
  const auth = useAuth();
  const perms = usePermissions(auth.data);
  const logout = useLogout();
  const navigate = useNavigate();
  const location = useLocation();

  const user = auth.data?.user ?? null;
  // One live stream per tab: server pushes invalidate TanStack Query (no polling).
  useLiveUpdates(!!auth.data?.loggedIn);
  const { mode, setMode } = useColorScheme();
  const dark = mode !== "light";
  function toggleMode() {
    setMode(dark ? "light" : "dark");
  }
  const visibleNav = NAV.filter((n) =>
    n.perm === "view"
      ? perms.can("view") || perms.owner
      : perms.can(n.perm) || perms.owner,
  );

  // Auth gate: unauthenticated users go to login (except on /login itself).
  useEffect(() => {
    if (
      auth.isSuccess &&
      !auth.data.loggedIn &&
      location.pathname !== "/login"
    ) {
      void navigate({ to: "/login", search: { redirect: location.pathname } });
    }
  }, [auth.isSuccess, auth.data, location.pathname, navigate]);

  // The API client fires this on 401: bounce to login.
  useEffect(() => {
    const onAuth = () => {
      const here = window.location.pathname;
      void navigate({
        to: "/login",
        search: { redirect: here === "/login" ? "/overview" : here },
      });
    };
    window.addEventListener("wesort:unauthorized", onAuth);
    return () => window.removeEventListener("wesort:unauthorized", onAuth);
  }, [navigate]);

  async function doLogout() {
    setAnchor(null);
    await logout();
    navigate({ to: "/login", search: { redirect: "/overview" } });
  }

  // Authentication has its own centered, stacked brand mark instead of the app chrome.
  if (location.pathname === "/login") {
    return (
      <Box component="main" sx={{ minHeight: "100vh", px: 2, py: 2 }}>
        <CssBaseline />
        <Outlet />
        <ToastHost />
      </Box>
    );
  }

  const drawer = (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%" }}>
      <Toolbar />
      <Divider />
      <List sx={{ flexGrow: 1 }}>
        {visibleNav.map((n) => {
          const active =
            location.pathname === n.to ||
            (n.to === "/overview" && location.pathname === "/");
          return (
            <ListItem key={n.to} disablePadding>
              <ListItemButton
                component={Link}
                to={n.to}
                selected={active}
                onClick={() => setMobileOpen(false)}
              >
                <ListItemIcon sx={{ minWidth: 40 }}>{n.icon}</ListItemIcon>
                <ListItemText primary={n.label} />
              </ListItemButton>
            </ListItem>
          );
        })}
      </List>
      <Divider />
      <Box sx={{ p: 2 }}>
        <Typography variant="caption" color="text.secondary">
          WeSort Manager · Minecraft 26.3
        </Typography>
        <Typography
          variant="caption"
          color="text.secondary"
          sx={{ display: "block" }}
        >
          NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH
          MOJANG OR MICROSOFT.
        </Typography>
      </Box>
    </Box>
  );

  return (
    <Box sx={{ display: "flex", minHeight: "100vh" }}>
      <CssBaseline />
      <AppBar position="fixed" sx={{ zIndex: (t) => t.zIndex.drawer + 1 }}>
        <Toolbar>
          {!isDesktop && (
            <IconButton
              color="inherit"
              edge="start"
              onClick={() => setMobileOpen(true)}
              aria-label="Open navigation"
              sx={{ mr: 1 }}
            >
              <MenuIcon />
            </IconButton>
          )}
          <Box
            sx={{
              display: "flex",
              alignItems: "center",
              flexGrow: 1,
              minWidth: 0,
              mr: 1,
            }}
          >
            <Box
              component="img"
              src="/wesort-horizontal.svg"
              alt="WeSort"
              data-testid="header-brand"
              sx={{
                display: "block",
                height: { xs: 36, sm: 44 },
                width: "auto",
                maxWidth: "100%",
                objectFit: "contain",
                objectPosition: "left center",
              }}
            />
          </Box>
          {user ? (
            <>
              <LiveBadge />
              <Chip
                label={user.owner ? "Owner" : (user.name ?? user.id)}
                size="small"
                variant="outlined"
                sx={{
                  display: { xs: "none", sm: "inline-flex" },
                  color: "inherit",
                  borderColor: "rgba(255,255,255,0.4)",
                  mr: 1,
                  maxWidth: 160,
                }}
              />
              <IconButton
                color="inherit"
                onClick={toggleMode}
                aria-label={
                  dark ? "Switch to light theme" : "Switch to dark theme"
                }
                title={dark ? "Light theme" : "Dark theme"}
                sx={{ mr: 0.5 }}
              >
                {dark ? <Brightness7Icon /> : <Brightness4Icon />}
              </IconButton>
              <IconButton
                color="inherit"
                onClick={(e) => setAnchor(e.currentTarget)}
                aria-label="Account menu"
              >
                <Avatar
                  sx={{ width: 32, height: 32, bgcolor: "secondary.main" }}
                >
                  <AccountCircleIcon />
                </Avatar>
              </IconButton>
              <Menu
                anchorEl={anchor}
                open={Boolean(anchor)}
                onClose={() => setAnchor(null)}
              >
                <MenuItem disabled>
                  <Typography variant="body2">
                    {user.name ?? user.id}
                  </Typography>
                </MenuItem>
                <MenuItem onClick={doLogout}>
                  <ListItemIcon>
                    <LogoutIcon fontSize="small" />
                  </ListItemIcon>
                  Sign out
                </MenuItem>
              </Menu>
            </>
          ) : (
            <IconButton
              color="inherit"
              onClick={() =>
                navigate({
                  to: "/login",
                  search: { redirect: location.pathname },
                })
              }
              aria-label="Sign in"
            >
              <AccountCircleIcon />
            </IconButton>
          )}
          {isDesktop && (
            <IconButton
              color="inherit"
              edge="end"
              aria-label="Collapse navigation"
              sx={{ display: "none" }}
            >
              <ChevronLeftIcon />
            </IconButton>
          )}
        </Toolbar>
      </AppBar>
      <Box
        component="nav"
        sx={{ width: { md: DRAWER_WIDTH }, flexShrink: { md: 0 } }}
      >
        {!isDesktop && (
          <Drawer
            variant="temporary"
            open={mobileOpen}
            onClose={() => setMobileOpen(false)}
            ModalProps={{ keepMounted: true }}
            sx={{ "& .MuiDrawer-paper": { width: DRAWER_WIDTH } }}
          >
            {drawer}
          </Drawer>
        )}
        {isDesktop && (
          <Drawer
            variant="permanent"
            open
            sx={{ "& .MuiDrawer-paper": { width: DRAWER_WIDTH } }}
          >
            {drawer}
          </Drawer>
        )}
      </Box>
      <Box
        component="main"
        sx={{
          flexGrow: 1,
          minWidth: 0,
          maxWidth: "100%",
          p: { xs: 2, md: 3 },
          width: { md: `calc(100% - ${DRAWER_WIDTH}px)` },
          mt: 8,
        }}
      >
        <Outlet />
      </Box>
      <ToastHost />
    </Box>
  );
}
