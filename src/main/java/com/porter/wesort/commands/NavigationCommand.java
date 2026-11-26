package com.porter.wesort.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.porter.wesort.compat.NavigationRuntime;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.commands.SharedSuggestionProvider;

public final class NavigationCommand extends Command {
    public NavigationCommand() {
        super(
                "wesort-navigation",
                "Inspect or cancel WeSort navigation (debug; the agent recovers automatically with backoff).");
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
    }
}
