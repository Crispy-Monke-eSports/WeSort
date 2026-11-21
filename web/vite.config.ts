import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Production build is served by the Java Manager from its /web classpath folder:
//   web/dist  ->  stash-manager-server/src/main/resources/web (index.html + assets/)
// Dev: `bun run dev` in web/ plus the Java Manager on :8765; /api, /dirt.png and
// /icons + /icon are proxied so Host/Origin/CSRF behaviour matches production.
export default defineConfig({
  plugins: [react()],
  base: "/",
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      "/api": { target: "http://127.0.0.1:8765", changeOrigin: false },
      "/dirt.png": { target: "http://127.0.0.1:8765", changeOrigin: false },
      "/icons": { target: "http://127.0.0.1:8765", changeOrigin: false },
      "/icon": { target: "http://127.0.0.1:8765", changeOrigin: false },
    },
  },
  build: {
    outDir: "../stash-manager-server/src/main/resources/web",
    emptyOutDir: false,
    assetsDir: "assets",
    sourcemap: false,
    chunkSizeWarningLimit: 1200,
  },
});
