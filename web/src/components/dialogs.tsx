import Alert from "@mui/material/Alert";
import Button from "@mui/material/Button";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogContentText from "@mui/material/DialogContentText";
import DialogTitle from "@mui/material/DialogTitle";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { useEffect, useState } from "react";

/** Per-page confirm flow: `ask({...})` shows the dialog, `dialog` renders it. */
export function useConfirm() {
  const [state, setState] = useState<null | {
    title: string;
    intro?: string;
    changes?: string[];
    confirmLabel: string;
    danger?: boolean;
    run: () => Promise<void> | void;
  }>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function run() {
    if (!state) return;
    setBusy(true);
    setError("");
    try {
      await state.run();
      setState(null);
    } catch (e: any) {
      setError(e?.message ?? "Action failed.");
    } finally {
      setBusy(false);
    }
  }
  const dialog = state ? (
    <ConfirmDialog
      open
      title={state.title}
      intro={state.intro}
      changes={state.changes}
      confirmLabel={state.confirmLabel}
      danger={state.danger}
      busy={busy}
      error={error}
      onClose={() => {
        if (!busy) setState(null);
      }}
      onConfirm={run}
    />
  ) : null;
  return { dialog, ask: setState };
}

/**
 * Reusable MD2 confirmation dialog. Replaces window.confirm so privilege,
 * server, bot-setting and order actions all show WHAT changes, separate
 * normal from destructive actions (color + explicit label, never color
 * alone), trap focus, and prevent double-submit.
 */
export function ConfirmDialog({
  open,
  title,
  intro,
  changes,
  confirmLabel,
  danger,
  busy,
  error,
  onClose,
  onConfirm,
}: {
  open: boolean;
  title: string;
  intro?: string;
  changes?: string[];
  confirmLabel: string;
  danger?: boolean;
  busy?: boolean;
  error?: string;
  onClose: () => void;
  onConfirm: () => void;
}) {
  return (
    <Dialog
      open={open}
      onClose={(_, reason) => {
        if (busy && (reason === "backdropClick" || reason === "escapeKeyDown"))
          return;
        onClose();
      }}
      aria-labelledby="confirm-dialog-title"
      aria-describedby={intro ? "confirm-dialog-desc" : undefined}
      maxWidth="xs"
      fullWidth
    >
      <DialogTitle id="confirm-dialog-title">{title}</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ mt: 0.5 }}>
          {intro && (
            <DialogContentText id="confirm-dialog-desc">
              {intro}
            </DialogContentText>
          )}
          {changes && changes.length > 0 && (
            <Stack spacing={0.5} component="ul" sx={{ m: 0, pl: 2.5 }}>
              {changes.map((c) => (
                <Typography
                  component="li"
                  variant="body2"
                  key={c}
                  sx={{ fontFamily: "monospace", wordBreak: "break-word" }}
                >
                  {c}
                </Typography>
              ))}
            </Stack>
          )}
          {error && <Alert severity="error">{error}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={busy} autoFocus>
          Cancel
        </Button>
        <Button
          variant="contained"
          color={danger ? "error" : "primary"}
          onClick={onConfirm}
          disabled={busy}
        >
          {busy
            ? "Working…"
            : danger
              ? `Yes, ${confirmLabel.toLowerCase()}`
              : confirmLabel}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

/**
 * Masked password reset dialog (P0-03): a real MUI Dialog with
 * type="password", length validation, an explicit sessions-ended notice,
 * labelled fields, and loading/error states. Never window.prompt.
 */
export function PasswordDialog({
  open,
  title,
  userName,
  minLength,
  busy,
  error,
  onClose,
  onConfirm,
}: {
  open: boolean;
  title: string;
  userName?: string;
  minLength: number;
  busy?: boolean;
  error?: string;
  onClose: () => void;
  onConfirm: (password: string) => void;
}) {
  const [value, setValue] = useState("");
  const [touched, setTouched] = useState(false);
  useEffect(() => {
    if (open) {
      setValue("");
      setTouched(false);
    }
  }, [open]);
  const tooShort = value.length > 0 && value.length < minLength;
  const invalid = value.length === 0 || tooShort;
  return (
    <Dialog
      open={open}
      onClose={(_, reason) => {
        if (busy && (reason === "backdropClick" || reason === "escapeKeyDown"))
          return;
        onClose();
      }}
      aria-labelledby="password-dialog-title"
      aria-describedby="password-dialog-desc"
      maxWidth="xs"
      fullWidth
    >
      <DialogTitle id="password-dialog-title">{title}</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ mt: 0.5 }}>
          <DialogContentText id="password-dialog-desc">
            {userName ? (
              <>
                Set a new temporary password for <strong>{userName}</strong>.
                Their sessions end immediately and they must change it at next
                sign-in.
              </>
            ) : (
              "Their sessions end immediately and they must change it at next sign-in."
            )}
          </DialogContentText>
          <TextField
            label="New temporary password"
            type="password"
            value={value}
            onChange={(e) => setValue(e.target.value)}
            onBlur={() => setTouched(true)}
            size="small"
            fullWidth
            autoFocus
            autoComplete="new-password"
            error={touched && invalid}
            helperText={
              touched && value.length === 0
                ? "A password is required."
                : touched && tooShort
                  ? `At least ${minLength} characters.`
                  : `Minimum ${minLength} characters. Nothing is logged.`
            }
            onKeyDown={(e) => {
              if (e.key === "Enter" && !invalid && !busy) onConfirm(value);
            }}
          />
          {error && <Alert severity="error">{error}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={busy}>
          Cancel
        </Button>
        <Button
          variant="contained"
          onClick={() => onConfirm(value)}
          disabled={invalid || busy}
        >
          {busy ? "Working…" : "Set password"}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
