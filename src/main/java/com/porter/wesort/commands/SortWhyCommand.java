package com.porter.wesort.commands;

import static meteordevelopment.meteorclient.MeteorClient.mc;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.commands.SharedSuggestionProvider;

/** Explains the held variant without exposing coordinates to a server log or moving any items. */
public final class SortWhyCommand extends Command {
    public SortWhyCommand() {
        super("wesort-sort-why", "Explain destinations and blocked containers for the held item variant.");
    }

    @Override
    public void build(LiteralArgumentBuilder<SharedSuggestionProvider> builder) {
        builder.executes(context -> {
            if (mc.player == null || mc.player.getMainHandItem().isEmpty()) {
                info("Hold the item variant to inspect.");
                return SINGLE_SUCCESS;
            }
            var report = com.porter.wesort.WeSortAddon.sorter().explainSort(mc.player.getMainHandItem());
            info(
                    "%s: %s; sorting %s",
                    report.get("item").getAsString(),
                    report.get("mode").getAsString(),
                    report.get("permitted").getAsBoolean() ? "permitted" : "paused by Manager");
            for (var candidate : report.getAsJsonArray("candidates")) {
                var c = candidate.getAsJsonObject();
                info(
                        "%s: %s (score %d)",
                        c.get("labels"),
                        c.get("reason").getAsString(),
                        c.get("score").getAsInt());
            }
            return SINGLE_SUCCESS;
        });
    }
}
