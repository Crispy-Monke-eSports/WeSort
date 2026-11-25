package com.porter.wesort.inventory;

/** Retry only transient route failures. A hazardous or operator-cancelled route is not authorised again.
 * No human can ever interact with the agent instance, so every latch also has a
 * bounded automatic way out (operator expiry, unsafe degrade); manual resume is debug only. */
public final class NavigationRecovery {
    private NavigationRecovery() {}

    /** Non-unloaded unsafe blocks degrade to replanning after this many bounded retries. */
    public static final int UNSAFE_BLOCK_MAX_RETRIES = 3;
    /** Operator-taken latches expire after this long when attended... */
    public static final long OPERATOR_IDLE_TIMEOUT_MS = 10 * 60_000;
    /** ...and sooner when headless: no human will ever come back. */
    public static final long OPERATOR_IDLE_TIMEOUT_DEDICATED_MS = 5 * 60_000;

    public static boolean retryable(String state, String detail) {
        return switch (state) {
            case "PAUSED_NAVIGATION_RELEASED",
                    "PAUSED_NAVIGATION_STUCK",
                    "PAUSED_NAVIGATION_TIMEOUT",
                    "PAUSED_NAVIGATION_INVENTORY",
                    "PAUSED_NAVIGATION_PHYSICAL_UNCERTAINTY" -> true;
            case "PAUSED_NAVIGATION_UNSAFE_BLOCK" -> detail != null && detail.startsWith("unloaded ");
            default -> false;
        };
    }

    /** True for latches that only an operator's absence (or their idle timeout) clears. */
    public static boolean operatorLatch(String state) {
        return switch (state) {
            case "PAUSED_NAVIGATION_OTHER_PROCESS",
                    "PAUSED_NAVIGATION_CANCELLED",
                    "FOREIGN_PROCESS_CANCELLED",
                    "PAUSED_NAVIGATION_DISCONNECTED",
                    "PAUSED_WRONG_DIMENSION" -> true;
            default -> false;
        };
    }

    public static long operatorTimeoutMs(boolean dedicated) {
        return dedicated ? OPERATOR_IDLE_TIMEOUT_DEDICATED_MS : OPERATOR_IDLE_TIMEOUT_MS;
    }

    public static boolean unsafeDegraded(int unsafeRetries) {
        return unsafeRetries >= UNSAFE_BLOCK_MAX_RETRIES;
    }

    public static long delayMs(int failures) {
        return Math.min(60_000L, 5_000L << Math.min(4, Math.max(0, failures - 1)));
    }
}
