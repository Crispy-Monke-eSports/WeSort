package com.porter.wesort.compat;

import com.google.gson.*;
import com.porter.wesort.data.ContainerIdentity;
import com.porter.wesort.stash.Stash;
import com.porter.wesort.stash.StashIndex;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import java.util.*;
import static meteordevelopment.meteorclient.MeteorClient.mc;

/** Witness tokens are issued only after a complete, matching server inventory read. */
public final class ObservationRuntime {
    private static Object level;
    private static String session = UUID.randomUUID().toString(), epoch = "";
    private static long sequence, revision;
    private static final Map<String, Witness> consumed = new HashMap<>();
    private record Witness(long contentEpoch, String session, long sequence, long at, String dimension,
                           String fingerprint, String block, String epoch, long baseRevision) {
        void copyTo(Stash.Box box) {
            box.observationSession = session; box.observationSequence = sequence; box.observationAt = at;
            box.observationDimension = dimension; box.observationFingerprint = fingerprint;
            box.observationBlock = block; box.observationEpoch = epoch; box.observationBaseRevision = baseRevision;
            box.observationUnreachable = false;
            box.lastRead = at;
        }
    }
    private ObservationRuntime() {}

    private static String session() {
        if (level != mc.level) { level = mc.level; session = UUID.randomUUID().toString(); consumed.clear(); }
        return session;
    }
    public static boolean canRead(Stash.Box box) { return InventorySync.confirmedSource(box.parts); }
    public static boolean current(Stash.Box box) {
        return mc.level != null && box.observationSession.equals(session())
            && box.observationDimension.equals(StashIndex.dimension()) && box.known() && topologyMatches(box)
            && box.observationFingerprint.equals(fingerprint(box));
    }
    private static String fingerprint(Stash.Box box) {
        JsonArray slots = new JsonArray();
        var ops = mc.level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        for (var stack : box.contents) slots.add(stack.isEmpty() ? "empty" : NbtFingerprint.encode(net.minecraft.world.item.ItemStack.CODEC.encodeStart(ops, stack).getOrThrow()));
        return ContainerIdentity.hash(slots.toString());
    }
    public static void record(Stash.Box box) {
        if (!canRead(box) || box.parts.stream().anyMatch(p -> !mc.level.isLoaded(p))) { box.stale = true; return; }
        String currentSession = session();
        var identity = new ContainerIdentity(Stash.currentServer(), StashIndex.dimension(), box.parts.stream()
            .map(p -> new ContainerIdentity.Position(p.getX(), p.getY(), p.getZ())).toList());
        long contentEpoch = InventorySync.contentsEpoch();
        String fingerprint = fingerprint(box);
        Witness witness = consumed.get(identity.id());
        if (witness != null && contentEpoch <= witness.contentEpoch) {
            if (contentEpoch == witness.contentEpoch && fingerprint.equals(witness.fingerprint)) witness.copyTo(box);
            else box.stale = true;
            return;
        }
        witness = new Witness(contentEpoch, currentSession, ++sequence, InventorySync.contentsReceivedAt(),
            StashIndex.dimension(), fingerprint, BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(box.pos).getBlock()).toString(), epoch, revision);
        consumed.put(identity.id(), witness);
        witness.copyTo(box);
    }
    private static boolean topologyMatches(Stash.Box box) {
        if (box.parts.stream().anyMatch(p -> !mc.level.isLoaded(p))) return false;
        var state = mc.level.getBlockState(box.pos);
        if (!BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(box.observationBlock)) return false;
        List<net.minecraft.core.BlockPos> parts = new ArrayList<>(List.of(box.pos));
        if (state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock && state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE)
            != net.minecraft.world.level.block.state.properties.ChestType.SINGLE)
            parts.add(box.pos.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(state)));
        return new HashSet<>(parts).equals(new HashSet<>(box.parts)) && box.contents.length == parts.size() * 27;
    }
    private static String dimension(Stash stash, Stash.Box box) {
        String dim = box.observationDimension;
        if (dim.isEmpty()) {
            dim = stash.dimension;
            int slash = dim.lastIndexOf(" / "); if (slash >= 0) dim = dim.substring(slash + 3).replace("]", "");
            if (dim.isEmpty()) dim = StashIndex.dimension();
        }
        return dim;
    }
    public static List<Stash.Box> boxes(Stash stash) {
        Set<String> occupied = new HashSet<>(); List<Stash.Box> out = new ArrayList<>();
        var sorted = stash.boxes.stream().sorted(Comparator.<Stash.Box>comparingInt(b -> b.delivery || b.excluded ? 0 : 1)
            .thenComparingInt(b -> current(b) ? 0 : 1).thenComparingLong(b -> -b.observationSequence)).toList();
        for (var box : sorted) {
            String dimension = dimension(stash, box);
            if (box.parts.stream().anyMatch(p -> occupied.contains(dimension + p))) continue;
            for (var part : box.parts) occupied.add(dimension + part);
            out.add(box);
        }
        return out;
    }
    public static void append(Stash stash, JsonObject root, boolean includeExcluded) {
        JsonArray sources = new JsonArray();
        for (var box : boxes(stash)) {
            if (box.delivery || box.foreign || box.excluded && !includeExcluded) continue;
            String dim = dimension(stash, box);
            var identity = new ContainerIdentity(stash.server, dim, box.parts.stream().map(p -> new ContainerIdentity.Position(p.getX(), p.getY(), p.getZ())).toList());
            JsonObject source = new JsonObject(); source.addProperty("id", identity.id()); source.addProperty("dimension", dim);
            JsonArray parts = new JsonArray();
            for (var pos : identity.parts()) { JsonArray p = new JsonArray(); p.add(pos.x()); p.add(pos.y()); p.add(pos.z()); parts.add(p); }
            source.add("parts", parts); source.addProperty("kind", box.observationBlock);
            source.addProperty("state", !box.known() ? "UNKNOWN" : box.unreachable || box.observationUnreachable ? "UNREACHABLE"
                : box.stale || !current(box) ? "STALE" : "FRESH");
            source.addProperty("session", box.observationSession); source.addProperty("sequence", box.observationSequence);
            source.addProperty("epoch", box.observationEpoch); source.addProperty("baseRevision", box.observationBaseRevision);
            source.addProperty("observedAt", box.observationAt); source.addProperty("fingerprint", box.observationFingerprint);
            source.addProperty("size", box.known() ? box.contents.length : box.parts.size() * 27);
            if (box.known()) {
                JsonObject aggregate = StashIndex.aggregate(Arrays.asList(box.contents));
                source.add("items", aggregate.get("items")); source.add("shulkers", aggregate.get("shulkers"));
            } else { source.add("items", new JsonArray()); source.add("shulkers", new JsonArray()); }
            sources.add(source);
        }
        root.addProperty("physicalVersion", 1); root.add("physicalSources", sources);
    }
    public static void invalidate(net.minecraft.core.BlockPos position) {
        if (position == null) return;
        var sorter = meteordevelopment.meteorclient.systems.modules.Modules.get().get(com.porter.wesort.modules.StashSorter.class);
        var stash = sorter == null ? null : sorter.currentStash();
        if (stash != null) for (var box : stash.boxes) if (box.has(position)) { box.stale = true; box.observationSession = ""; stash.markDirty(); }
    }
    /** Called only for responses whose server signature has already been checked. */
    public static boolean reply(int code, String body) {
        try {
            JsonObject response = JsonParser.parseString(body).getAsJsonObject();
            if (response.has("stockEpoch") && response.has("stockRevision")) {
                epoch = response.get("stockEpoch").getAsString(); revision = response.get("stockRevision").getAsLong();
            }
            return code == 409 && response.has("stockRevision");
        } catch (RuntimeException ignored) { return false; }
    }
}
