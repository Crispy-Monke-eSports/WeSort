import createCache from "@emotion/cache";
import { CacheProvider } from "@emotion/react";
import CssBaseline from "@mui/material/CssBaseline";
import { ThemeProvider } from "@mui/material/styles";
import { useMemo } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./router";
import { wesortTheme } from "./theme/wesortTheme";

/**
 * Emotion cache bound to the per-request CSP style nonce the Manager injects
 * into index.html (<meta name="emotion-nonce">). Without it, style-src 'self'
 * would block every MUI runtime style.
 */
function emotionCache() {
  const nonce = document.querySelector('meta[name="emotion-nonce"]')?.getAttribute("content") ?? undefined;
  return createCache({ key: "mui", nonce: nonce === "__WESORT_STYLE_NONCE__" ? undefined : nonce });
}

function Root() {
  const cache = useMemo(emotionCache, []);
  return (
    <CacheProvider value={cache}>
      <ThemeProvider theme={wesortTheme} defaultMode="dark" noSsr>
        <CssBaseline />
        <App />
      </ThemeProvider>
    </CacheProvider>
  );
}

const el = document.getElementById("root");
if (!el) throw new Error("missing #root");
createRoot(el).render(<Root />);
