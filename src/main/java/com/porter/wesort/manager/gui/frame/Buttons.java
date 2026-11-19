package com.porter.wesort.manager.gui.frame;

import static com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT;

import meteordevelopment.meteorclient.utils.misc.input.Input;

/**
 * Whether the left mouse button is down now.
 *
 * <p>Not {@code Minecraft.mouseHandler.isLeftPressed()}: the game only updates that flag while no screen is open
 * (verified in the game's MouseHandler.onButton, which skips it when a screen is up), so inside a screen it is always
 * false. That made every drag of the window end on its second frame. Meteor's {@code Input} is fed by its mixin at the
 * head of the same method, whatever screen is up, and is the same in every Meteor build this addon supports.
 */
public final class Buttons {
    private Buttons() {}

    public static boolean leftDown() {
        return Input.isButtonPressed(MOUSE_BUTTON_LEFT);
    }
}
