package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The React SPA must be servable: index.html exists and app routes fall back to it. */
class StaticRoutesTest {
    @Test
    void indexHtmlIsOnTheClasspath() {
        assertNotNull(
                StaticRoutes.class.getResourceAsStream("/web/index.html"), "React build must provide /web/index.html");
    }

    @Test
    @SuppressWarnings("unchecked")
    void spaRoutesCoverTheAppPages() throws Exception {
        Field f = StaticRoutes.class.getDeclaredField("SPA_ROUTES");
        f.setAccessible(true);
        Set<String> routes = (Set<String>) f.get(null);
        assertTrue(routes.containsAll(Set.of("/shop", "/orders", "/bots", "/activity", "/settings", "/login")));
    }
}
