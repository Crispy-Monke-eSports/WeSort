package com.porter.wesort.compat;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.function.BooleanSupplier;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.orbit.EventHandler;

/**
 * Right mouse button presses, for Minecraft 26.3.
 */
public final class RightClick {
    private final BooleanSupplier handler;

    private RightClick(BooleanSupplier handler) {
        this.handler = handler;
    }

    /**
     * Calls {@code handler} on every right click press. If it returns true the click is cancelled, so it doesn't
     * place, use or open anything.
     */
    public static void listen(BooleanSupplier handler) {
        MeteorClient.EVENT_BUS.subscribe(new RightClick(handler));
    }

    @EventHandler
    private void onMouse(MouseClickEvent event) {
        if (event.action != KeyAction.Press || event.button() != InputConstants.MOUSE_BUTTON_RIGHT) return;
        if (handler.getAsBoolean()) event.cancel();
    }
}
