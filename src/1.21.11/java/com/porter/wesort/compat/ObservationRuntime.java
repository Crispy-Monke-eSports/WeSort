package com.porter.wesort.compat;
import com.google.gson.JsonObject;
import com.porter.wesort.stash.Stash;
/** Older targets retain their local index; they cannot assert verified 26.3 stock. */
public final class ObservationRuntime {
    private ObservationRuntime() {}
    public static java.util.List<Stash.Box> boxes(Stash stash) { return stash.boxes; }
    public static boolean canRead(Stash.Box box) { return true; }
    public static boolean current(Stash.Box box) { return true; }
    public static void record(Stash.Box box) {}
    public static void append(Stash stash, JsonObject root, boolean includeExcluded) {}
    public static boolean reply(int code, String body) { return false; }
}
