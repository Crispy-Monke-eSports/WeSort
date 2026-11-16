package com.porter.wesort.compat;

/** Baseline behavior is unchanged; the server-readback port is specific to 26.3. */
public final class InventorySync {
    private InventorySync() {}
    public static boolean ready() { return true; }
    public static boolean failed() { return false; }
    public static String reason() { return ""; }
    public static boolean requestReconciliation() { return false; }
}
