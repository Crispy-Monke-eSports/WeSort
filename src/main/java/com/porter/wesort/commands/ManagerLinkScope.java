package com.porter.wesort.commands;

import com.porter.wesort.data.ContainerIdentity;

/**
 * Binds an authenticated Manager reply to the exact Minecraft server, Manager
 * endpoint and credential that sent the request.
 *
 * <p>The raw key is never included in the scope string or logged.
 */
public final class ManagerLinkScope {
    private ManagerLinkScope() {}

    public static String of(String managerUrl, String minecraftServer, String apiKey) {
        if (managerUrl == null || managerUrl.isBlank() || apiKey == null || apiKey.isBlank()) return "";
        String base = managerUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.isEmpty()) return "";
        return base + "|" + minecraftServer + "|" + ContainerIdentity.hash(apiKey.trim());
    }

    /** An empty or stale scope cannot authenticate a reply or continue physical work. */
    public static boolean matches(String requestedScope, String currentScope) {
        return requestedScope != null && !requestedScope.isEmpty() && requestedScope.equals(currentScope);
    }
}
