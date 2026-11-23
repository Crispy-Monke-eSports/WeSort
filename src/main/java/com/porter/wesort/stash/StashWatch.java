package com.porter.wesort.stash;

import static meteordevelopment.meteorclient.MeteorClient.mc;

import com.porter.wesort.stash.Stash.Box;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * Watches client-observed chest/barrel lids for changes caused by other players.
 * Neighbour blocks do not mutate the tracked inventory; times use caller ticks.
 */
public final class StashWatch {
    private static final int OPEN_FORGET_TICKS = 20 * 60 * 10;

    private static final class Lid {
        boolean open;
        boolean foreign;
        long openedAt;
        long closedAt = -1;
    }

    private final Map<Box, Lid> lids = new HashMap<>();
    // Watching the chests of the delivery storage (excluded from sorting, so the other watch skips them) and nothing
    // else
    private final boolean deliveryOnly;

    public StashWatch() {
        this(false);
    }

    public StashWatch(boolean deliveryOnly) {
        this.deliveryOnly = deliveryOnly;
    }

    public void clear() {
        lids.clear();
    }

    // Lids

    /**
     * Notices lids being opened by someone else. One that somebody other than {@code byUs} looked into and that has stayed
     * closed for {@code delay} ticks is handed to {@code reread}.
     */
    public void tickLids(Stash stash, long now, long delay, Predicate<Box> byUs, Consumer<Box> reread) {
        for (Box box : new ArrayList<>(stash.boxes)) {
            if (deliveryOnly ? !box.delivery : box.excluded) continue;
            Boolean open = isOpen(box);
            if (open == null) continue;

            Lid lid = lids.get(box);
            if (open) {
                if (lid == null) {
                    lid = new Lid();
                    lids.put(box, lid);
                }
                if (!lid.open) {
                    lid.open = true;
                    lid.openedAt = now;
                    lid.closedAt = -1;
                    // Somebody new looking in that isn't the bot, nor the connected player
                    if (!byUs.test(box)) lid.foreign = true;
                }
                // Still shown as open after ten minutes: the close was missed. Forgotten
                else if (now - lid.openedAt > OPEN_FORGET_TICKS) lids.remove(box);
            } else if (lid != null) {
                if (lid.open) {
                    lid.open = false;
                    lid.closedAt = now;
                }
                if (!lid.foreign) lids.remove(box);
                else if (now - lid.closedAt >= delay) {
                    lids.remove(box);
                    reread.accept(box);
                }
            }
        }
        lids.keySet().removeIf(box -> !stash.boxes.contains(box));
    }

    /**
     * Whether anyone has it open (a lid up, a barrel open), or null if it isn't loaded.
     */
    private static Boolean isOpen(Box box) {
        if (mc.level == null) return null;
        boolean open = false;
        for (BlockPos part : box.parts) {
            if (!mc.level.isLoaded(part)) return null;
            if (mc.level.getBlockEntity(part) instanceof ChestBlockEntity chest) {
                if (chest.getOpenNess(1.0f) > 0) open = true;
            } else {
                var state = mc.level.getBlockState(part);
                if (state.getBlock() instanceof BarrelBlock && state.getValue(BarrelBlock.OPEN)) open = true;
            }
        }
        return open;
    }
}
