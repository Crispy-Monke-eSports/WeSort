package com.porter.wesortmanager;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The website's files from the jar's /web folder, plus the React single-page app.
 *
 * <p>Registered last as the GET catch-all: every Java API, icon and texture route runs first.
 * Frontend routes (/shop, /orders, /bots, /activity, /settings, /login) serve index.html so
 * direct navigation and refresh work; /assets/* serves hashed Vite files immutable; unknown
 * /api/* paths answer JSON 404 here instead of leaking the SPA.
 */
final class StaticRoutes {
    private static final Pattern STATIC_NAME = Pattern.compile("^[a-z0-9_-][a-z0-9_.-]{0,63}$");

    /** React app routes served with index.html (direct navigation + refresh). */
    private static final Set<String> SPA_ROUTES = Set.of("/shop", "/orders", "/bots", "/activity", "/settings", "/login");

    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

    void register(Router router) {
        router.fallback(Router.GET, "/", this::staticFile);
    }

    private void staticFile(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        if (path.equals("/")) {
            sendCached(ex, "index.html", "no-cache");
            return;
        }
        if (path.equals("/index.html")) {
            sendCached(ex, "index.html", "no-cache");
            return;
        }
        if (SPA_ROUTES.contains(path)) {
            sendCached(ex, "index.html", "no-cache");
            return;
        }
        if (path.startsWith("/assets/")) {
            String name = path.substring(1); // assets/<hash>.js
            if (!name.matches("assets/[A-Za-z0-9_.-]{1,128}")) {
                Http.error(ex, 404, "not found");
                return;
            }
            byte[] data = cached(name);
            if (data == null) {
                Http.error(ex, 404, "not found");
                return;
            }
            // Vite hashes asset filenames: safe to cache immutably for a year.
            Http.send(ex, 200, type(name), data, "public, max-age=31536000, immutable");
            return;
        }
        if (path.startsWith("/api/")) {
            Http.error(ex, 404, "not found");
            return;
        }
        String name = path.substring(1);
        if (!STATIC_NAME.matcher(name).matches() || name.contains("..")) {
            Http.error(ex, 404, "not found");
            return;
        }
        if (name.equals("index.html")) {
            sendCached(ex, "index.html", "no-cache");
            return;
        }
        byte[] data = cached(name);
        if (data == null) {
            Http.error(ex, 404, "not found");
            return;
        }
        Http.send(ex, 200, type(name), data, "no-cache");
    }

    private void sendCached(HttpExchange ex, String name, String cacheControl) throws IOException {
        byte[] data = cached(name);
        if (data == null) {
            Http.error(ex, 404, "not found");
            return;
        }
        Http.send(ex, 200, type(name), data, cacheControl);
    }

    private byte[] cached(String name) {
        byte[] data = cache.get(name);
        if (data != null) return data;
        data = load(name);
        // Only real resources are cached, so the map stays small.
        if (data != null) cache.put(name, data);
        return data;
    }

    private static byte[] load(String name) {
        try (InputStream in = StaticRoutes.class.getResourceAsStream("/web/" + name)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    private static String type(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1);
        return switch (ext) {
            case "html" -> "text/html; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "js" -> "text/javascript; charset=utf-8";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "ico" -> "image/x-icon";
            case "woff2" -> "font/woff2";
            case "json" -> "application/json; charset=utf-8";
            default -> "application/octet-stream";
        };
    }
}
