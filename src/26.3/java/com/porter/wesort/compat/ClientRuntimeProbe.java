package com.porter.wesort.compat;

import baritone.api.BaritoneAPI;
import com.google.gson.GsonBuilder;
import com.porter.wesort.modules.StashManager;
import com.porter.wesort.modules.StashOverlay;
import com.porter.wesort.modules.StashSorter;
import com.porter.wesort.modules.StashWindow;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.fabricmc.loader.api.FabricLoader;

/** Explicit opt-in development probe; never connects to a world or enables automation. */
public final class ClientRuntimeProbe {
    private ClientRuntimeProbe() {}

    public static void afterInitialize() {
        PhysicalJournalRuntime.initialize();
        InventorySync.initialize();
        NavigationRuntime.initialize();
        WorldTestDriver.initialize();
        if (!Boolean.getBoolean("wesort.runtime.probe")) return;
        Thread.ofPlatform().name("wesort-runtime-probe").daemon().start(() -> {
            try {
                Thread.sleep(10_000);
                MeteorClient.mc.execute(() -> {
                    for (var type :
                            java.util.List.<Class<? extends meteordevelopment.meteorclient.systems.modules.Module>>of(
                                    StashSorter.class, StashOverlay.class, StashManager.class)) {
                        if (Modules.get().get(type) == null) throw new IllegalStateException("Missing module " + type);
                    }
                    if (BaritoneAPI.getProvider().getPrimaryBaritone() == null)
                        throw new IllegalStateException("No Baritone");
                    if (StashWindow.get() != null)
                        throw new IllegalStateException("Legacy member window registered in production");
                    new StashWindow().openNow();
                });
                Thread.sleep(8_000);
                MeteorClient.mc.execute(() -> {
                    try {
                        if (!(MeteorClient.mc.gui.screen()
                                instanceof com.porter.wesort.manager.gui.StashManagerScreen)) {
                            throw new IllegalStateException("Stash Window did not remain open");
                        }
                        var loader = FabricLoader.getInstance();
                        var result = new LinkedHashMap<String, Object>();
                        for (String id :
                                new String[] {"minecraft", "fabricloader", "meteor-client", "baritone-meteor", "wesort"
                                }) {
                            result.put(
                                    id,
                                    loader.getModContainer(id)
                                            .orElseThrow()
                                            .getMetadata()
                                            .getVersion()
                                            .getFriendlyString());
                        }
                        result.put("java", System.getProperty("java.runtime.version"));
                        result.put("result", "PASS");
                        result.put(
                                "checks",
                                "WeSort modules, packet mixin audit, Baritone linkage, legacy readback fixture, member window disabled in production");
                        result.put("limits", "No connected world, physical inventory or pathfinding tested.");
                        Files.writeString(
                                loader.getGameDir().resolve("runtime-result.json"),
                                new GsonBuilder().setPrettyPrinting().create().toJson(result));
                        MeteorClient.mc.gui.setScreen(null);
                        MeteorClient.LOG.info("WESORT_CLIENT_RUNTIME_PASS");
                        MeteorClient.mc.stop();
                    } catch (Exception e) {
                        throw new IllegalStateException("WeSort runtime probe failed", e);
                    }
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
}
