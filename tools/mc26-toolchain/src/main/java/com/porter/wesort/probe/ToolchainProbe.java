package com.porter.wesort.probe;

import baritone.api.BaritoneAPI;
import com.google.gson.GsonBuilder;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.util.LinkedHashMap;

/** A separate minimal addon: proves initialization, never claims inventory/pathfinding E2E. */
public final class ToolchainProbe extends MeteorAddon {
    @Override
    public void onInitialize() {
        if (!Boolean.getBoolean("wesort.toolchain.probe")) return;
        // Allow the real client/render initialization to proceed before checking runtime linkage.
        Thread.ofPlatform().name("wesort-toolchain-probe").daemon().start(() -> {
            try {
                Thread.sleep(10_000);
                MeteorClient.mc.execute(() -> {
                    try {
                        var loader = FabricLoader.getInstance();
                        var result = new LinkedHashMap<String, Object>();
                        for (String id : new String[]{"minecraft", "fabricloader", "meteor-client", "baritone-meteor"}) {
                            result.put(id, loader.getModContainer(id).orElseThrow().getMetadata().getVersion().getFriendlyString());
                        }
                        if (BaritoneAPI.getProvider().getPrimaryBaritone() == null) {
                            throw new IllegalStateException("No primary Baritone instance");
                        }
                        result.put("java", System.getProperty("java.runtime.version"));
                        result.put("result", "PASS");
                        result.put("limits", "Client/addon initialization and Baritone linkage only; no world, inventory or navigation E2E.");
                        Files.writeString(loader.getGameDir().resolve("toolchain-result.json"),
                            new GsonBuilder().setPrettyPrinting().create().toJson(result));
                        MeteorClient.LOG.info("WESORT_TOOLCHAIN_PROBE_PASS");
                        MeteorClient.mc.stop();
                    } catch (Exception e) {
                        throw new IllegalStateException("Toolchain runtime probe failed", e);
                    }
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Override
    public String getPackage() {
        return "com.porter.wesort.probe";
    }
}
