package com.porter.wesortmanager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The reservation key is a canonical physical source and top-level slot, shared by all stash aliases. */
final class PhysicalReservations {
    record Root(String source, String path) {}

    record Allocation(
            String source, String path, String ref, long count, String fingerprint, long revision,
            String itemFingerprint) {
        Root root() {
            int nested = path.indexOf('/');
            return new Root(source, nested < 0 ? path : path.substring(0, nested));
        }

        boolean outer() {
            return path.equals(root().path());
        }
    }

    private record Candidate(
            String source,
            String path,
            String item,
            String shulker,
            long count,
            String fingerprint,
            long revision,
            boolean fresh,
            boolean queueable,
            String itemFingerprint) {
        String reference() {
            return item.isEmpty() ? "shulker:" + shulker : "item:" + item;
        }

        Allocation allocate(String ref, long amount) {
            return new Allocation(source, path, ref, amount, fingerprint, revision, itemFingerprint);
        }
    }

    private final List<Candidate> candidates;
    private final Map<String, List<Candidate>> bySource;
    private final Map<String, StockLedger.Source> sources;

    private PhysicalReservations(List<Candidate> candidates, Map<String, StockLedger.Source> sources) {
        this.candidates = candidates;
        this.bySource = candidates.stream().collect(java.util.stream.Collectors.groupingBy(Candidate::source));
        this.sources = sources;
    }

    static PhysicalReservations read(StockLedger ledger, String server, String stash) throws IOException {
        var quarantined = ledger.quarantinedBoxes(server);
        var candidates = new ArrayList<Candidate>();
        var sources = new LinkedHashMap<String, StockLedger.Source>();
        for (var source : ledger.sources()) {
            if (!source.server().equals(server)
                    || stash != null && !source.stash().equals(stash)) continue;
            var value = source.value();
            // Shared container identities are visible but never safe for a physical reservation.
            // IndexStore already excludes them from aggregate stock; the slot allocator must agree.
            if (source.shared() || !value.has("slotFingerprint")) continue;
            String id = value.get("id").getAsString();
            if (sources.putIfAbsent(id, source) != null) continue;
            boolean fresh = source.status().equals("FRESH");
            // The offline queue may only fall back to last-verified counts from sources whose
            // last observation is intact but aged (or pending a first post-restart rescan). An
            // explicitly invalidated source (STALE), an unreachable/missing one, a withdrawn or
            // protected one, or a superseded topology must never back even a queued reservation.
            // Shared sources are excluded like the aggregate counts (IndexStore.counted).
            boolean queueable = (fresh
                            || source.status().equals("STALE_AGE")
                            || source.status().equals("STALE_STARTUP"))
                    && !source.shared();
            for (var slot : ledger.physicalSlots(id)) {
                if (!slot.get("present").getAsBoolean()) continue;
                String path = slot.get("path").getAsString();
                int nested = path.indexOf('/');
                boolean safe = !quarantined.contains(new Root(id, nested < 0 ? path : path.substring(0, nested)));
                String group = slot.get("group").getAsString();
                boolean whole = slot.get("kind").getAsString().equals("SHULKER") && !group.isEmpty();
                if (whole && path.contains("/")) continue;
                candidates.add(new Candidate(
                        id,
                        path,
                        whole ? "" : slot.get("key").getAsString(),
                        group,
                        Math.multiplyExact(
                                slot.get("count").getAsLong(),
                                slot.get("copies").getAsLong()),
                        slot.get("fingerprint").getAsString(),
                        slot.get("revision").getAsLong(),
                        safe && fresh,
                        safe && queueable,
                        slot.has("itemFingerprint") ? slot.get("itemFingerprint").getAsString() : ""));
            }
        }
        return new PhysicalReservations(List.copyOf(candidates), Map.copyOf(sources));
    }

    boolean hasSnapshots() {
        return !sources.isEmpty();
    }

    List<Allocation> allocate(Iterable<OrderStore.Line> requested, Set<Root> reserved, Map<String, Integer> firstIndex)
            throws OrderStore.OrderException {
        return allocate(requested, reserved, firstIndex, false);
    }

    /**
     * Slot-level reservation. Fresh candidates only, unless allowStale: while no bot is online the
     * sources are stale and verified counts read zero, so a queued order may reserve last-verified
     * slots. Claim ({@link #matches}) still requires fresh candidates, so the bot re-verifies
     * fingerprints and counts before it touches anything. Ported from PR #89.
     */
    List<Allocation> allocate(
            Iterable<OrderStore.Line> requested,
            Set<Root> reserved,
            Map<String, Integer> firstIndex,
            boolean allowStale)
            throws OrderStore.OrderException {
        var selected = new ArrayList<Allocation>();
        var used = new HashMap<Root, List<Allocation>>();
        for (var line : requested) {
            long remaining = line.count();
            for (var candidate : candidates) {
                if ((!allowStale && !candidate.fresh())
                        || (allowStale && !candidate.queueable())
                        || !candidate.reference().equals(line.ref())) continue;
                var allocation = candidate.allocate(line.ref(), Math.min(remaining, candidate.count()));
                var existing = used.get(allocation.root());
                if (reserved.contains(allocation.root())
                        || existing != null
                                && (allocation.outer()
                                        || existing.stream()
                                                .anyMatch(part -> part.outer()
                                                        || part.path().equals(allocation.path())))) continue;
                selected.add(allocation);
                used.computeIfAbsent(allocation.root(), ignored -> new ArrayList<>())
                        .add(allocation);
                remaining -= allocation.count();
                if (remaining == 0) break;
            }
            if (remaining != 0) {
                throw new OrderStore.OrderException(
                        409,
                        "Only " + (line.count() - remaining) + " verified unreserved units for " + line.key(),
                        firstIndex.get(line.ref()));
            }
        }
        return List.copyOf(selected);
    }

    /**
     * Re-verifies allocations against fresh observations. An exact fingerprint match is the fast
     * path; otherwise a newer observation of the same item on the same slot still counts, provided
     * it holds at least the reserved count minus what sibling holds on that slot already cover.
     * That lets a stack grow (10 -&gt; 11) without orphaning its reservation, while a shrink, an
     * item swap, or a double-spend of the grown delta still fails closed.
     *
     * @param siblingHolds other open holds on the same server; their counts are excluded before
     *     comparing, so two orders cannot both spend the same grown units.
     */
    boolean matches(List<Allocation> allocations) {
        return matches(allocations, List.of());
    }

    boolean matches(List<Allocation> allocations, List<Allocation> siblingHolds) {
        var heldElsewhere = new HashMap<Root, Long>();
        for (var hold : siblingHolds) {
            if (hold.count() > 0) heldElsewhere.merge(new Root(hold.source(), hold.path()), hold.count(), Long::sum);
        }
        return allocations.stream().allMatch(allocation -> matchesOne(
                allocation, heldElsewhere.getOrDefault(new Root(allocation.source(), allocation.path()), 0L)));
    }

    private boolean matchesOne(Allocation allocation, long heldElsewhere) {
        return candidates.stream()
                .anyMatch(candidate -> candidate.fresh()
                        && candidate.source().equals(allocation.source())
                        && candidate.path().equals(allocation.path())
                        && (candidate.fingerprint().equals(allocation.fingerprint()) || sameItemNewer(candidate, allocation))
                        && candidate.count() - heldElsewhere >= allocation.count());
    }

    private static boolean sameItemNewer(Candidate candidate, Allocation allocation) {
        String have = candidate.itemFingerprint();
        String want = allocation.itemFingerprint();
        return have != null
                && !have.isEmpty()
                && have.equals(want)
                && candidate.reference().equals(allocation.ref())
                && candidate.revision() > allocation.revision();
    }

    Map<String, Long> available(Set<Root> held) {
        return available(held, null, false);
    }

    Map<String, Long> available(Set<Root> held, String source) {
        return available(held, source, false);
    }

    /** Like {@link #available(Set, String)}, but counting last-verified slots for the order queue. */
    Map<String, Long> available(Set<Root> held, String source, boolean allowStale) {
        var totals = new HashMap<String, Long>();
        for (var candidate : source == null ? candidates : bySource.getOrDefault(source, List.of())) {
            if (!allowStale && !candidate.fresh()) continue;
            if (allowStale && !candidate.queueable()) continue;
            String ref = candidate.reference();
            if (!held.contains(candidate.allocate(ref, 0).root())) totals.merge(ref, candidate.count(), Math::addExact);
        }
        return totals;
    }

    Map<String, Long> observed(String source) {
        var totals = new HashMap<String, Long>();
        for (var candidate : bySource.getOrDefault(source, List.of())) {
            totals.merge(candidate.reference(), candidate.count(), Math::addExact);
        }
        return totals;
    }

    private static boolean freshAfter(StockLedger.Source source, long cutoff) {
        return source.status().equals("FRESH") && source.verifiedAt() > cutoff;
    }

    boolean refreshedAfter(List<Allocation> allocations, long cutoff) {
        return allocations.stream().map(Allocation::source).distinct().allMatch(id -> {
            var source = sources.get(id);
            if (source == null) return false;
            if (freshAfter(source, cutoff)) return true;
            if (!source.status().equals("SUPERSEDED")) return false;
            // A merge or split changes the canonical source ID. Every old physical
            // position must now belong to a fresh, verified replacement inventory.
            // Never infer a return from an absent, stale or only partly covered source.
            for (var part : source.value().getAsJsonArray("parts")) {
                boolean covered = sources.values().stream()
                        .anyMatch(replacement -> replacement.server().equals(source.server())
                                && replacement
                                        .value()
                                        .get("dimension")
                                        .equals(source.value().get("dimension"))
                                && freshAfter(replacement, cutoff)
                                && replacement.value().getAsJsonArray("parts").contains(part));
                if (!covered) return false;
            }
            return true;
        });
    }
}
