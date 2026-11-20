package com.porter.wesort.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.porter.wesort.modules.StashManager;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.commands.SharedSuggestionProvider;

/**
 * {@code .wesort-connect} opens the WeSort connection screen (address, port, API key — the only
 * thing the addon asks for). {@code .wesort-connect <address> <port> <api-key>} connects directly.
 */
public final class WeSortConnectCommand extends Command {
    public WeSortConnectCommand() {
        super("wesort-connect", "Connects this bot to a WeSort Manager (address, port, API key).");
    }

    @Override
    public void build(LiteralArgumentBuilder<SharedSuggestionProvider> builder) {
        builder.executes(context -> {
            WeSortConnectScreen.open();
            return SINGLE_SUCCESS;
        });
        builder.then(argument("address", StringArgumentType.string())
                .then(argument("port", IntegerArgumentType.integer(1, 65535))
                        .then(argument("api-key", StringArgumentType.greedyString())
                                .executes(context -> {
                                    String error = connect(
                                            StringArgumentType.getString(context, "address"),
                                            String.valueOf(IntegerArgumentType.getInteger(context, "port")),
                                            StringArgumentType.getString(context, "api-key"));
                                    if (error == null) info("Connected to the WeSort Manager.");
                                    else error("%s", error);
                                    return SINGLE_SUCCESS;
                                }))));
    }

    /**
     * Validates and stores a connection, returning null on success or an error message.
     * Shared by the screen and the direct command form.
     */
    static String connect(String address, String portText, String key) {
        StashManager manager = Modules.get().get(StashManager.class);
        if (manager == null) return "The wesort-stash-manager module is missing.";
        String host = address == null ? "" : address.trim();
        if (host.isEmpty()) return "Address must not be empty.";
        String scheme = "http";
        String lower = host.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("http://")) {
            host = host.substring("http://".length());
        } else if (lower.startsWith("https://")) {
            scheme = "https";
            host = host.substring("https://".length());
        }
        if (host.isEmpty() || host.contains(" ") || host.contains("/")) {
            return "Address must be a plain host or IP, without path.";
        }
        int port;
        try {
            port = Integer.parseInt(portText == null ? "" : portText.trim());
        } catch (NumberFormatException e) {
            return "Port must be a number 1-65535.";
        }
        if (port < 1 || port > 65535) return "Port must be a number 1-65535.";
        String apiKey = key == null ? "" : key.trim();
        if (apiKey.isEmpty()) return "API key must not be empty. Ask the Manager host for it (agent-key).";
        String url = scheme + "://" + host + ":" + port;
        if (!setString(manager, "server-url", url) || !setString(manager, "api-key", apiKey)) {
            return "Could not store the connection on wesort-stash-manager.";
        }
        if (!manager.isActive()) manager.toggle();
        return null;
    }

    static void clearKey(StashManager manager) {
        setString(manager, "api-key", "");
    }

    static String stringSetting(StashManager manager, String name) {
        Setting<?> setting = manager.settings.get(name);
        Object value = setting == null ? null : setting.get();
        return value instanceof String s ? s : "";
    }

    @SuppressWarnings("unchecked")
    private static boolean setString(StashManager manager, String name, String value) {
        Setting<?> setting = manager.settings.get(name);
        if (setting == null || !(setting.get() instanceof String)) return false;
        ((Setting<String>) setting).set(value);
        return true;
    }
}
