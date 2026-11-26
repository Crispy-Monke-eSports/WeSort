package com.porter.wesort.craft;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * PR10 (#231, steg 1): bindande, verifierbart Manager-plan-kontrakt (additivt).
 *
 * <p>A {@code CraftPlan} is the single approved truth an agent executes without
 * replanning: recipe IDs, counts, allowed cell allocations, produced/consumed
 * quantities, planned surplus, dependency edges and physical allocation refs,
 * all pinned to a recipe dataset digest and an order revision. The agent
 * validates the whole plan (including mass balance per exact variant) before
 * the first physical intent and never replans on its own: a replan requires a
 * new Manager revision, otherwise fail-closed.
 *
 * <p>Hash is canonical (sorted maps, stable step order) and is integrity only —
 * transport authenticity stays with the existing signed channel.
 */
public record CraftPlan(
        int schemaVersion,
        String planId,
        String recipeDatasetDigest,
        long orderRevision,
        Map<String, Long> requestedOutputs,
        List<PlanStep> steps,
        Map<String, Long> produced,
        Map<String, Long> consumed,
        Map<String, Long> plannedSurplus,
        List<DependencyEdge> dependencies,
        List<AllocationRef> physicalAllocations) {
    public CraftPlan {
        if (schemaVersion != 1) throw new IllegalArgumentException("unsupported plan schema: " + schemaVersion);
        if (planId == null || planId.isEmpty()) throw new IllegalArgumentException("planId required");
        if (recipeDatasetDigest == null || recipeDatasetDigest.isEmpty())
            throw new IllegalArgumentException("recipeDatasetDigest required");
        if (orderRevision < 1) throw new IllegalArgumentException("orderRevision required");
        requestedOutputs = requestedOutputs == null ? Map.of() : Map.copyOf(new TreeMap<>(requestedOutputs));
        steps = steps == null ? List.of() : List.copyOf(steps);
        produced = produced == null ? Map.of() : Map.copyOf(new TreeMap<>(produced));
        consumed = consumed == null ? Map.of() : Map.copyOf(new TreeMap<>(consumed));
        plannedSurplus = plannedSurplus == null ? Map.of() : Map.copyOf(new TreeMap<>(plannedSurplus));
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        physicalAllocations = physicalAllocations == null ? List.of() : List.copyOf(physicalAllocations);
        if (requestedOutputs.isEmpty()) throw new IllegalArgumentException("requestedOutputs required");
    }

    public record PlanStep(String stepId, String recipeId, int crafts) {
        public PlanStep {
            if (stepId == null || stepId.isEmpty()) throw new IllegalArgumentException("stepId required");
            if (recipeId == null || recipeId.isEmpty()) throw new IllegalArgumentException("recipeId required");
            if (crafts <= 0) throw new IllegalArgumentException("crafts must be positive");
        }
    }

    public record DependencyEdge(String fromStep, String toStep) {
        public DependencyEdge {
            if (fromStep == null || toStep == null) throw new IllegalArgumentException("edge endpoints required");
            if (fromStep.equals(toStep)) throw new IllegalArgumentException("self dependency");
        }
    }

    public record AllocationRef(String sourceId, String path, String variantRef, long count, String slotFingerprint) {
        public AllocationRef {
            if (sourceId == null || sourceId.isEmpty()) throw new IllegalArgumentException("sourceId required");
            if (path == null || path.isEmpty()) throw new IllegalArgumentException("path required");
            if (variantRef == null || variantRef.isEmpty()) throw new IllegalArgumentException("variantRef required");
            if (count <= 0) throw new IllegalArgumentException("count must be positive");
            if (slotFingerprint == null || slotFingerprint.isEmpty())
                throw new IllegalArgumentException("slotFingerprint required");
        }
    }

    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();

    /** Canonical hash over sorted maps + stable step order. Integrity only, not auth. */
    public String canonicalHash() {
        var tree = GSON.toJsonTree(this);
        return com.porter.wesort.data.ContainerIdentity.hash(
                "craft-plan:1\n" + com.porter.wesort.data.CanonicalJson.stringify(tree));
    }

    /**
     * Independent mass-balance verifier: per exact variant,
     * {@code externalTake + produced == consumed + finalAllocated + surplus}.
     * Returns the first imbalance description, or empty when balanced.
     * Never trusts {@code planner.ok()} — recompute from the plan itself.
     */
    public static java.util.Optional<String> verifyMassBalance(
            Map<String, Long> externalTake,
            Map<String, Long> producedAll,
            Map<String, Long> consumedAll,
            Map<String, Long> finalAllocated,
            Map<String, Long> surplus) {
        var keys = new java.util.TreeSet<String>();
        keys.addAll(externalTake.keySet());
        keys.addAll(producedAll.keySet());
        keys.addAll(consumedAll.keySet());
        keys.addAll(finalAllocated.keySet());
        keys.addAll(surplus.keySet());
        for (String key : keys) {
            long left, right;
            try {
                left = Math.addExact(externalTake.getOrDefault(key, 0L), producedAll.getOrDefault(key, 0L));
                right = Math.addExact(
                        Math.addExact(finalAllocated.getOrDefault(key, 0L), consumedAll.getOrDefault(key, 0L)),
                        surplus.getOrDefault(key, 0L));
            } catch (ArithmeticException overflow) {
                return java.util.Optional.of("overflow for " + key);
            }
            if (left != right) return java.util.Optional.of("imbalance for " + key + ": " + left + " != " + right);
        }
        return java.util.Optional.empty();
    }
}
