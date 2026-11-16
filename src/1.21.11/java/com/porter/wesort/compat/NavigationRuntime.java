package com.porter.wesort.compat;
/** Baseline navigation is unchanged. */
public final class NavigationRuntime {
    private NavigationRuntime() {}
    public static void watch(Object goal) {}
    public static void released(Object goal) {}
    public static boolean paused() { return false; }
    public static String status() { return "BASELINE"; }
    public static void cancel() {}
    public static boolean resume() { return false; }
}
