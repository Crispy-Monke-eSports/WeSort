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

    record Allocation(String source, String path, String ref, long count, String fingerprint, long revision) {
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
            boolean queueable) {
        String reference() {
            return item.isEmpty() ? "shulker:" + shulker : "item:" + item;
        }

        Allocation allocate(String ref, long amount) {
            return new Allocation(source, path, ref, amount, fingerprint, revision);
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
        var candidates = new ArrayList<Candidate>();
        var sources = new LinkedHashMap<String, StockLedger.Source>();
        for (var source : ledger.sources()) {
            if (!source.server().equals(server)
                    || stash != null && !source.stash().equals(stash)) continue;
            var value = source.value();
            if (!value.has("slotFingerprint")) continue;
            String id = value.get("id").getAsString();
            if (sources.putIfAbsent(id, source) != null) continue;
            boolean fresh = source.status().equals("FRESH");
            // The offline queue may only fall back to last-verified counts from sources whose
            // last observation is intact but aged (or pending a first post-restart rescan). An
            // explicitly invalidated source (STALE), an unreachable/missing one, a withdrawn or
            // protected one, or a superseded topology must never back even a queued reservation.
            boolean queueable = fresh
                    || source.status().equals("STALE_AGE")
                    || source.status().equals("STALE_STARTUP");
            for (var slot : ledger.physicalSlots(id)) {
                if (!slot.get("present").getAsBoolean()) continue;
                String path = slot.get("path").getAsString();
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
                        fresh,
                        queueable));
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

    boolean matches(List<Allocation> allocations) {
        return allocations.stream()
                .allMatch(allocation -> candidates.stream()
                        .anyMatch(candidate -> candidate.fresh()
                                && candidate.source().equals(allocation.source())
                                && candidate.path().equals(allocation.path())
                                && candidate.fingerprint().equals(allocation.fingerprint())
                                && candidate.count() >= allocation.count()));
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

    boolean refreshedAfter(List<Allocation> allocations, long cutoff) {
        return allocations.stream().map(Allocation::source).distinct().allMatch(id -> {
            var source = sources.get(id);
            return source != null
                    && source.verifiedAt() > cutoff
                    && source.status().equals("FRESH");
        });
    }
}
