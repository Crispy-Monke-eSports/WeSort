package com.porter.wesortmanager;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/**
 * GET /dirt.png: the vanilla dirt texture from the checksum-verified official client (eula=true),
 * for the Classic theme's menu background. Open like the other static web files so the login page
 * gets the same background; 404 when the texture cannot be read and the page falls back to dirt.svg.
 */
final class DirtRoutes {
    private final byte[] png;

    DirtRoutes(byte[] png) {
        this.png = png;
    }

    void register(Router router) {
        router.exact(Router.GET, "/dirt.png", Router.Guard.OPEN, this::dirt);
    }

    private void dirt(HttpExchange ex) throws IOException {
        if (png == null) {
            Http.error(ex, 404, "not found");
            return;
        }
        Http.send(ex, 200, "image/png", png, "no-cache");
    }
}
