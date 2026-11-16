package com.porter.wesort.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.porter.wesort.modules.StashWindow;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.commands.SharedSuggestionProvider;

/** {@code .wesort-stash-window} (or {@code .stash}) opens the WeSort Manager window once the chat has closed. */
public class StashWindowCommand extends Command {
    public StashWindowCommand() {
        super("wesort-stash-window", "Opens the WeSort Manager window.", "wesort");
    }

    @Override
    public void build(LiteralArgumentBuilder<SharedSuggestionProvider> builder) {
        builder.executes(context -> {
            StashWindow module = StashWindow.get();
            if (module == null) return 0;
            module.openLater();
            return SINGLE_SUCCESS;
        });
    }
}
