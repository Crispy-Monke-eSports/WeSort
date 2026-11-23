import { createTheme } from "@mui/material/styles";

/**
 * WeSort Material Design 2 theme. MD2, not Material You:
 * restrained 4dp corners, standard elevation scale, solid surfaces,
 * a single WeSort/minecraft green accent. Dark is the default;
 * light is a complete variant of the same system.
 */
const GREEN = {
  main: "#4caf50",
  light: "#80e27e",
  dark: "#087f23",
  contrastText: "#0b120b",
};

export const wesortTheme = createTheme({
  colorSchemes: {
    dark: {
      palette: {
        primary: GREEN,
        secondary: {
          main: "#90a4ae",
          light: "#c1d5e0",
          dark: "#62757f",
          contrastText: "#0b120b",
        },
        background: { default: "#121614", paper: "#1b211e" },
        error: {
          main: "#e57373",
          light: "#ff8a80",
          dark: "#c62828",
          contrastText: "#170b0b",
        },
        warning: {
          main: "#ffb74d",
          light: "#ffcc80",
          dark: "#e65100",
          contrastText: "#170f04",
        },
        info: {
          main: "#64b5f6",
          light: "#90caf9",
          dark: "#1565c0",
          contrastText: "#08131c",
        },
        success: {
          main: "#81c784",
          light: "#a5d6a7",
          dark: "#2e7d32",
          contrastText: "#0b120b",
        },
        text: {
          primary: "#e8ece9",
          secondary: "rgba(232, 236, 233, 0.68)",
          disabled: "rgba(232, 236, 233, 0.38)",
        },
        divider: "rgba(232, 236, 233, 0.12)",
      },
    },
    light: {
      palette: {
        primary: {
          main: "#2e7d32",
          light: "#60ad5e",
          dark: "#005005",
          contrastText: "#ffffff",
        },
        secondary: {
          main: "#546e7a",
          light: "#819ca9",
          dark: "#29434e",
          contrastText: "#ffffff",
        },
        background: { default: "#f2f4f1", paper: "#ffffff" },
        error: {
          main: "#c62828",
          light: "#e57373",
          dark: "#8e0000",
          contrastText: "#ffffff",
        },
        warning: {
          main: "#e65100",
          light: "#ff833a",
          dark: "#ac1900",
          contrastText: "#000000",
        },
        info: {
          main: "#1565c0",
          light: "#5e92f3",
          dark: "#003c8f",
          contrastText: "#ffffff",
        },
        success: {
          main: "#2e7d32",
          light: "#60ad5e",
          dark: "#005005",
          contrastText: "#ffffff",
        },
        text: {
          primary: "rgba(18, 24, 19, 0.87)",
          secondary: "rgba(18, 24, 19, 0.6)",
          disabled: "rgba(18, 24, 19, 0.38)",
        },
        divider: "rgba(18, 24, 19, 0.12)",
      },
    },
  },
  shape: { borderRadius: 4 },
  typography: {
    // MD2 type scale: MUI v9 defaults already implement the MD2 roles
    // (H1 96/Light … H6 20/Medium, subtitle, body, caption, overline).
    // Pages use the semantic level with an explicit smaller visual variant
    // (e.g. component="h1" variant="h5") instead of redefining the scale.
    fontFamily:
      '"Inter", "IBM Plex Sans", "Roboto", "Helvetica", "Arial", sans-serif',
    button: { textTransform: "none", fontWeight: 600 },
  },
  components: {
    MuiButton: {
      // MD2 contained buttons carry 2dp elevation (8dp pressed): no global
      // disableElevation. Text buttons are unaffected (they have none).
      styleOverrides: {
        root: { borderRadius: 4 },
      },
    },
    MuiCard: {
      defaultProps: { elevation: 2 },
      styleOverrides: {
        root: { borderRadius: 4 },
      },
    },
    MuiPaper: {
      styleOverrides: {
        root: { borderRadius: 4 },
      },
    },
    // MuiChip: intentionally no overrides — the MUI default (fully rounded
    // stadium with Body-2 labelling) is the MD2 chip pattern.
    MuiDialog: {
      styleOverrides: {
        paper: { borderRadius: 4 },
      },
    },
    MuiDrawer: {
      styleOverrides: {
        paper: { borderRadius: 0 },
      },
    },
    MuiTooltip: {
      styleOverrides: {
        tooltip: { borderRadius: 4 },
      },
    },
    MuiTableCell: {
      styleOverrides: {
        root: { padding: "8px 12px" },
      },
    },
  },
});

export type WesortColorScheme = "dark" | "light";
