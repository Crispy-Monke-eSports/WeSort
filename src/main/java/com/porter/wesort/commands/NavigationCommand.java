package com.porter.wesort.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.porter.wesort.compat.NavigationRuntime;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.commands.SharedSuggestionProvider;

public final class NavigationCommand extends Command {
    public NavigationCommand() {
        super("wesort-navigation", "Inspect, cancel or explicitly resume WeSort navigation after checking the route.");
    }

    @Override
    public void build(LiteralArgumentBuilder<SharedSuggestionProvider> builder) {
        builder.executes(context -> {
            info(NavigationRuntime.status());
            return SINGLE_SUCCESS;
        });
        builder.then(literal("cancel").executes(context -> {
            NavigationRuntime.cancel();
            info(NavigationRuntime.status());
            return SINGLE_SUCCESS;
        }));
        builder.then(literal("resume").executes(context -> {
            info(
                    NavigationRuntime.resume()
                            ? "Navigation released for a new goal; no previous route is replayed."
                            : "Cannot resume navigation: " + NavigationRuntime.resumeDenial() + ".");
            return SINGLE_SUCCESS;
        }));
    }
}
