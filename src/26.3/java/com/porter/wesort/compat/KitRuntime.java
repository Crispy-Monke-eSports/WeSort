package com.porter.wesort.compat;

import static meteordevelopment.meteorclient.MeteorClient.mc;

import com.porter.wesort.data.KitTemplate;
import com.porter.wesort.stash.Kit;
import java.util.Arrays;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

public final class KitRuntime {
    private KitRuntime() {}

    public static KitTemplate export(Kit kit) {
        return new KitTemplate(kit.name, SlotRuntime.snapshot(kit.layout()));
    }

    public static Kit load(KitTemplate template) {
        ItemStack[] layout = new ItemStack[27];
        Arrays.fill(layout, ItemStack.EMPTY);
        var ops = mc.level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        for (var slot : template.layout().slots()) {
            var id = Identifier.parse(slot.item().id());
            if (!BuiltInRegistries.ITEM.containsKey(id))
                throw new IllegalArgumentException("Unknown Minecraft 26.3 item in template");
            var stack = new ItemStack(BuiltInRegistries.ITEM.getValue(id), slot.count());
            var patch = DataComponentPatch.CODEC
                    .parse(
                            ops,
                            NbtFingerprint.decode(slot.item().components()))
                    .getOrThrow();
            stack.applyComponentsAndValidate(patch);
            layout[slot.slot()] = stack;
        }
        var kit = new Kit(template.name(), layout);
        if (!export(kit).fingerprint().equals(template.fingerprint()))
            throw new IllegalArgumentException("Native template components do not match their exact snapshot");
        return kit;
    }
}
