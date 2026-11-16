package com.porter.wesort.manager.gui.frame;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import meteordevelopment.meteorclient.utils.misc.input.Input;

/** SDL cursors through Meteor; no GLFW handles or hardcoded platform constants. */
public final class WindowCursor {
    private WindowCursor() {}

    public static void set(CursorShape shape) {
        Input.setCursorStyle(switch (shape) {
            case HORIZONTAL -> CursorTypes.RESIZE_EW;
            case VERTICAL -> CursorTypes.RESIZE_NS;
            case DIAGONAL_NWSE, DIAGONAL_NESW -> CursorTypes.CROSSHAIR;
            case DEFAULT -> CursorTypes.ARROW;
        });
    }

    public static void reset() { set(CursorShape.DEFAULT); }
}
