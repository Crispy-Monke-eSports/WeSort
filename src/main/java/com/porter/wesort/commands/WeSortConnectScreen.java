package com.porter.wesort.commands;

import static meteordevelopment.meteorclient.MeteorClient.mc;

import com.porter.wesort.modules.StashManager;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.Utils;

/**
 * The WeSort connection screen: the only thing the addon asks for is address, port and API key.
 * Opened by bare {@code .wesort-connect}; values are stored on the wesort-stash-manager module and
 * the module is switched on, so nothing else in Meteor has to be configured for the connection.
 */
public final class WeSortConnectScreen extends WidgetScreen {
    private WTextBox addressBox;
    private WTextBox portBox;
    private WTextBox keyBox;
    private WLabel status;

    public WeSortConnectScreen() {
        super(GuiThemes.get(), "Connect to WeSort Manager");
    }

    static void open() {
        Utils.screenToOpen = new WeSortConnectScreen();
    }

    static void closeToGame() {
        mc.setScreen(null);
    }

    @Override
    public void initWidgets() {
        WVerticalList list = add(theme.verticalList()).expandX().widget();
        list.add(theme.label("Connect to WeSort Manager", true));
        list.add(theme.label("The only thing this addon needs: address, port and API key."));

        addressBox = field(list, "Address (host or IP, no scheme needed)");
        portBox = field(list, "Port (1-65535)");
        keyBox = secret(list, "API key (from the Manager owner)");
        fillCurrent();

        status = list.add(theme.label("")).expandX().widget();
        refreshStatus();

        WButton connect = list.add(theme.button("Connect")).expandX().widget();
        connect.action = () -> {
            String error = WeSortConnectCommand.connect(addressBox.get(), portBox.get(), keyBox.get());
            if (error == null) refreshStatus();
            else status.set(error);
        };
        WButton disconnect = list.add(theme.button("Disconnect")).expandX().widget();
        disconnect.action = () -> {
            StashManager manager = Modules.get().get(StashManager.class);
            if (manager != null) {
                WeSortConnectCommand.clearKey(manager);
                if (manager.isActive()) manager.toggle();
            }
            keyBox.set("");
            refreshStatus();
        };
        WButton back = list.add(theme.button("Back to Meteor menu")).expandX().widget();
        back.action = WeSortConnectScreen::closeToGame;
    }

    /** Truthful link state (P1-12): storing values is not connecting. Reopen to refresh. */
    private void refreshStatus() {
        StashManager manager = Modules.get().get(StashManager.class);
        if (manager == null) {
            status.set("The wesort-stash-manager module is missing.");
            return;
        }
        ConnectionState state;
        try {
            state = ConnectionState.valueOf(manager.connectionState());
        } catch (RuntimeException e) {
            state = ConnectionState.CONTACTING;
        }
        status.set(state.label() + ": " + state.hint() + " (Reopen this screen to refresh.)");
    }

    private WTextBox field(WVerticalList list, String label) {
        list.add(theme.label(label));
        return list.add(theme.textBox("")).minWidth(300).expandX().widget();
    }

    private WTextBox secret(WVerticalList list, String label) {
        list.add(theme.label(label));
        return list.add(theme.textBox("", null, MaskedRenderer.ANY_CHAR, MaskedRenderer.class))
                .minWidth(300)
                .expandX()
                .widget();
    }

    private void fillCurrent() {
        StashManager manager = Modules.get().get(StashManager.class);
        if (manager == null) return;
        String url = WeSortConnectCommand.stringSetting(manager, "server-url");
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        String host = url;
        if (lower.startsWith("http://")) host = url.substring("http://".length());
        else if (lower.startsWith("https://")) host = url.substring("https://".length());
        String port = "";
        int colon = host.lastIndexOf(':');
        if (colon >= 0 && host.indexOf(':') == colon) {
            port = host.substring(colon + 1);
            host = host.substring(0, colon);
        }
        addressBox.set(host);
        portBox.set(port);
    }
}
