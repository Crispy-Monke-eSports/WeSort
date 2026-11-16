package com.porter.wesort;

import com.mojang.logging.LogUtils;
import com.porter.wesort.compat.ClientRuntimeProbe;
import com.porter.wesort.commands.StashWindowCommand;
import com.porter.wesort.modules.StashManager;
import com.porter.wesort.modules.StashOverlay;
import com.porter.wesort.modules.StashSorter;
import com.porter.wesort.modules.StashWindow;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class WeSortAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("WeSort");

    @Override
    public void onInitialize() {
        LOG.info("Initializing WeSort");

        // Modules
        Modules.get().add(new StashSorter());
        Modules.get().add(new StashOverlay());
        Modules.get().add(new StashManager());
        Modules.get().add(new StashWindow());

        // Commands
        Commands.add(new StashWindowCommand());
        Commands.add(new com.porter.wesort.commands.InventoryReconcileCommand());
        ClientRuntimeProbe.afterInitialize();
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.porter.wesort";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("Crispy-Monke-eSports", "WeSort");
    }
}
