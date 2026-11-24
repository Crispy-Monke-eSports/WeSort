package com.porter.wesort.inventory;

/** Retry only transient route failures. A hazardous or operator-cancelled route is not authorised again. */
public final class NavigationRecovery {
    private NavigationRecovery() {}

    public static boolean retryable(String state, String detail) {
        return switch (state) {
            case "PAUSED_NAVIGATION_RELEASED", "PAUSED_NAVIGATION_STUCK", "PAUSED_NAVIGATION_TIMEOUT",
                    "PAUSED_NAVIGATION_INVENTORY", "PAUSED_NAVIGATION_PHYSICAL_UNCERTAINTY" -> true;
            case "PAUSED_NAVIGATION_UNSAFE_BLOCK" -> detail != null && detail.startsWith("unloaded ");
            default -> false;
        };
    }

    public static long delayMs(int failures) {
        return Math.min(60_000L, 5_000L << Math.min(4, Math.max(0, failures - 1)));
    }
}
