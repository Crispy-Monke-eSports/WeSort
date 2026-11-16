package com.porter.wesort.compat;

/** 26.x binds default item components when the client joins a world. */
public final class ItemComponents {
    private ItemComponents() {}
    public static boolean ready() { return net.minecraft.client.Minecraft.getInstance().level != null; }
}
