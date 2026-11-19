package com.porter.wesort.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.porter.wesort.compat.InventorySync;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.commands.SharedSuggestionProvider;

/** Explicit recovery never retries an unconfirmed click. */
public final class InventoryReconcileCommand extends Command {
    public InventoryReconcileCommand() {
        super(
                "wesort-reconcile",
                "After inspecting the physical inventory, request a fresh server readback of the original postcondition.");
    }

    @Override
    public void build(LiteralArgumentBuilder<SharedSuggestionProvider> builder) {
        builder.executes(context -> {
            if (InventorySync.requestReconciliation())
                info(
                        "Reopen the affected container within 30 seconds. Automation stays paused until the server matches the original pending state; no click is replayed.");
            else info("No recoverable pending inventory action, or this baseline does not use the 26.3 readback gate.");
            return SINGLE_SUCCESS;
        });
    }
}
