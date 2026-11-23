import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { getRouteApi } from "@tanstack/react-router";
import { useState } from "react";
import { api } from "../api/client";
import { useAuth } from "../api/queries";
import { safeLoginRedirect } from "../lib/safeRedirect";

const loginRoute = getRouteApi("/login");

/** Login + first-run owner setup. Test hooks: #auth-screen, #auth-submit, #auth-username. */
export function LoginPage() {
  const auth = useAuth();
  const search = loginRoute.useSearch();
  const [name, setName] = useState("");
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState("");

  const setupNeeded = auth.data?.setupNeeded === true;
  const minLength = auth.data?.minLength ?? 12;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy("login");
    setError("");
    try {
      // Java AuthRoutes.login reads `username` (blank/absent = owner), not `name`.
      await api("/api/v1/auth/login", {
        method: "POST",
        body: name.trim() ? { username: name.trim(), password } : { password },
      });
      await auth.refetch();
      window.location.assign(safeLoginRedirect(search.redirect));
    } catch (err: any) {
      setError(err?.message ?? "Login failed.");
    } finally {
      setBusy("");
    }
  }

  async function submitSetup(e: React.FormEvent) {
    e.preventDefault();
    setBusy("setup");
    setError("");
    try {
      await api("/api/v1/auth/setup", {
        method: "POST",
        body: { code, password },
      });
      await auth.refetch();
      window.location.assign(safeLoginRedirect(search.redirect));
    } catch (err: any) {
      setError(
        err?.message ??
          "Setup failed. Request a code with the local bootstrap command.",
      );
    } finally {
      setBusy("");
    }
  }

  return (
    <Box sx={{ mx: "auto", mt: { xs: 4, md: 8 }, maxWidth: 440 }}>
      <Box sx={{ mb: 3, textAlign: "center" }}>
        <Box
          component="img"
          src="/wesort-lockup.svg"
          alt="WeSort"
          sx={{ height: 56, mb: 1 }}
        />
        <Typography color="text.secondary">
          Guild stash &amp; order manager
        </Typography>
      </Box>
      <Card>
        <CardContent id="auth-screen" sx={{ p: 3 }}>
          <Typography variant="h5" component="h1" gutterBottom>
            {setupNeeded ? "First-run setup" : "Welcome back"}
          </Typography>
          <Typography color="text.secondary" sx={{ mb: 2 }}>
            {setupNeeded
              ? "Create the owner account to get started."
              : "Sign in to manage the guild stock."}
          </Typography>
          {setupNeeded ? (
            <form onSubmit={submitSetup}>
              <Stack spacing={2}>
                <Alert severity="info">
                  No owner password is set yet. Enter the setup code from the
                  local bootstrap command and choose a password (min {minLength}{" "}
                  chars).
                </Alert>
                <TextField
                  id="auth-code"
                  label="Setup code"
                  value={code}
                  onChange={(e) => setCode(e.target.value)}
                  autoComplete="off"
                  required
                  fullWidth
                  size="small"
                />
                <TextField
                  id="auth-password"
                  label="Owner password"
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  autoComplete="new-password"
                  required
                  fullWidth
                  size="small"
                />
                {error && <Alert severity="error">{error}</Alert>}
                <Button
                  id="auth-submit"
                  type="submit"
                  variant="contained"
                  disabled={busy !== ""}
                >
                  {busy === "setup" ? "Setting up…" : "Create owner"}
                </Button>
              </Stack>
            </form>
          ) : (
            <form onSubmit={submit}>
              <Stack spacing={2}>
                <TextField
                  id="auth-username"
                  label="Account name"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  autoComplete="username"
                  placeholder="Blank for owner"
                  fullWidth
                  size="small"
                />
                <TextField
                  id="auth-password"
                  label="Password"
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  autoComplete="current-password"
                  required
                  fullWidth
                  size="small"
                />
                {error && <Alert severity="error">{error}</Alert>}
                <Button
                  id="auth-submit"
                  type="submit"
                  variant="contained"
                  disabled={busy !== ""}
                >
                  {busy === "login" ? "Signing in…" : "Sign in"}
                </Button>
              </Stack>
            </form>
          )}
          <Typography
            variant="caption"
            color="text.secondary"
            sx={{ display: "block", mt: 2 }}
          >
            Guild stash &amp; order manager.
          </Typography>
        </CardContent>
      </Card>
    </Box>
  );
}
