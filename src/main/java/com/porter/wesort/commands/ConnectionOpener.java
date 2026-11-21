package com.porter.wesort.commands;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.settings.GenericSetting;
import meteordevelopment.meteorclient.settings.IGeneric;
import net.minecraft.nbt.CompoundTag;

/**
 * The "Connection…" entry in the module settings: opens the WeSort connection screen (address,
 * port, masked API key, connect/disconnect, status) straight from the normal Meteor menu, so the
 * {@code .wesort-connect} command is never required as a first step.
 */
public final class ConnectionOpener implements IGeneric<ConnectionOpener> {
    public static GenericSetting<ConnectionOpener> setting() {
        return new GenericSetting.Builder<ConnectionOpener>()
                .name("connection")
                .description("Open the WeSort connection screen: Manager address, port and masked API key.")
                .defaultValue(new ConnectionOpener())
                .build();
    }

    private ConnectionOpener() {}

    @Override
    public WidgetScreen createScreen(GuiTheme theme, GenericSetting<ConnectionOpener> setting) {
        return new WeSortConnectScreen();
    }

    @Override
    public ConnectionOpener set(ConnectionOpener value) {
        return this;
    }

    @Override
    public ConnectionOpener copy() {
        return new ConnectionOpener();
    }

    @Override
    public CompoundTag toTag() {
        return new CompoundTag();
    }

    @Override
    public ConnectionOpener fromTag(CompoundTag tag) {
        return this;
    }
}
