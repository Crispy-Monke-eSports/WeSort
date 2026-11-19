package com.porter.wesort.compat;

import static meteordevelopment.meteorclient.MeteorClient.mc;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;

/** Development-only export from a live vanilla 26.3 registry with bound item components and tags. */
public final class MetadataExport {
    private MetadataExport() {}

    public static void export(Path jar, Path output) throws Exception {
        if (!Boolean.getBoolean("wesort.e2e") || mc.level == null)
            throw new IllegalStateException("No isolated E2E world");
        Files.createDirectories(output);
        CreativeModeTabs.tryRebuildTabContents(mc.level.enabledFeatures(), false, mc.level.registryAccess());
        List<String> order = List.of(
                "building_blocks",
                "colored_blocks",
                "natural_blocks",
                "functional_blocks",
                "redstone_blocks",
                "tools_and_utilities",
                "combat",
                "food_and_drinks",
                "ingredients",
                "spawn_eggs",
                "op_blocks");
        JsonObject items = new JsonObject();
        for (String path : order)
            for (var tab : BuiltInRegistries.CREATIVE_MODE_TAB) {
                if (tab.getType() != CreativeModeTab.Type.CATEGORY
                        || !BuiltInRegistries.CREATIVE_MODE_TAB
                                .getKey(tab)
                                .getPath()
                                .equals(path)) continue;
                int index = 0;
                for (var stack : tab.getDisplayItems()) {
                    String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                    if (!items.has(id)) {
                        JsonArray value = new JsonArray();
                        value.add(path);
                        value.add(index);
                        items.add(id, value);
                    }
                    index++;
                }
            }
        JsonObject tabs = new JsonObject();
        tabs.addProperty("version", "26.3");
        tabs.add("tabs", new Gson().toJsonTree(order));
        tabs.add("items", items);
        Files.writeString(
                output.resolve("creative-tabs.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(tabs) + "\n");
        JsonArray blocks = new JsonArray();
        for (var item : BuiltInRegistries.ITEM)
            if (item instanceof BlockItem)
                blocks.add(BuiltInRegistries.ITEM.getKey(item).toString());
        JsonObject blockItems = new JsonObject();
        blockItems.addProperty("version", "26.3");
        blockItems.add("items", blocks);
        Files.writeString(output.resolve("block-items.json"), blockItems.toString() + "\n");
        MetadataRecipes.export(jar, output.resolve("recipes.json"));
    }
}
