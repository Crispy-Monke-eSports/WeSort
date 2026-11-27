package com.porter.wesort.data;

/** Exact Minecraft endpoint comparison shared by signed Manager scopes and durable agent recovery. */
public final class MinecraftServerAddress {
    private MinecraftServerAddress() {}

    /** Normalize the host spelling, retaining an explicit port as part of the server identity. */
    public static String normalizeServer(String server) {
        if (server == null) return "";
        String address = server.trim().toLowerCase(java.util.Locale.ROOT);
        if (address.isEmpty()) return "";
        int colon = portSeparator(address);
        String host = colon < 0 ? address : address.substring(0, colon).trim();
        String port = colon < 0 ? "" : address.substring(colon);
        while (host.endsWith(".") && host.length() > 1) host = host.substring(0, host.length() - 1);
        return host.isEmpty() ? "" : host + port;
    }

    /** A port separator is unambiguous only for a DNS/IPv4 host or bracketed IPv6. */
    private static int portSeparator(String address) {
        if (address.startsWith("[")) {
            int end = address.indexOf(']');
            return end >= 0 && end + 1 < address.length() && address.charAt(end + 1) == ':' ? end + 1 : -1;
        }
        int colon = address.indexOf(':');
        return colon >= 0 && address.indexOf(':', colon + 1) < 0 ? colon : -1;
    }

    /** Compare aliases and the implicit default port, but never conflate distinct explicit ports. */
    public static boolean sameServer(String want, String got) {
        String a = comparisonServer(want);
        String b = comparisonServer(got);
        return !a.isEmpty() && a.equals(b);
    }

    private static String comparisonServer(String server) {
        String address = canonicalServer(server);
        int colon = portSeparator(address);
        return colon >= 0 && address.substring(colon).equals(":25565") ? address.substring(0, colon) : address;
    }

    /** Normalize the configured spelling and the alt. SRV alias without losing its explicit port. */
    public static String canonicalServer(String server) {
        String host = normalizeServer(server);
        if (host.equals("alt.6b6t.org") || host.startsWith("alt.6b6t.org:")) host = host.substring(4);
        return host;
    }
}
