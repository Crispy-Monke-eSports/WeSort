package com.porter.wesort.craft;

import java.util.List;
import java.util.Set;

/**
 * PR9 (#240/#253, steg 1): shared versioned crafting ingredient policy.
 *
 * <p>One deterministic eligibility function used by both Manager planning
 * ({@code CraftingCatalog}) and agent execution ({@code Crafter}): an input
 * stack is usable as a recipe ingredient only if it passes every rule below.
 * Bare-ID matching alone is never sufficient — a modified variant with the
 * same ID must be rejected, never silently consumed.
 *
 * <p>Policy is versioned ({@link #POLICY_VERSION}); a change invalidates
 * previews and requires a newly approved plan. Local safety limits may stop
 * an unsafe operation but must never silently rewrite an approved material
 * plan.
 */
public final class CraftIngredientPolicy {
    private CraftIngredientPolicy() {}

    /** Bump for every rule change; persisted with approved plans. */
    public static final int POLICY_VERSION = 1;

    public enum RejectReason {
        MODIFIED_COMPONENTS,
        DAMAGED,
        CONTAINER_CONTENT,
        FORBIDDEN_ID,
        UNKNOWN_METADATA,
        NON_VANILLA_INPUT
    }

    public record Decision(boolean allowed, RejectReason reason) {
        public static Decision allow() {
            return new Decision(true, null);
        }

        public static Decision deny(RejectReason reason) {
            return new Decision(false, reason);
        }
    }

    /**
     * Classifies an ingredient candidate described by its canonical facts.
     *
     * @param hasNonDefaultComponents true when the native patch differs from vanilla defaults
     * @param damaged true for damaged tools/armor
     * @param holdsItems true when the stack itself carries nested content
     * @param forbidden true when the base id is on the never-use list
     * @param unknownMetadata true for unrecognized/custom component data
     */
    public static Decision classify(
            boolean hasNonDefaultComponents,
            boolean damaged,
            boolean holdsItems,
            boolean forbidden,
            boolean unknownMetadata) {
        if (forbidden) return Decision.deny(RejectReason.FORBIDDEN_ID);
        if (unknownMetadata) return Decision.deny(RejectReason.UNKNOWN_METADATA);
        if (damaged) return Decision.deny(RejectReason.DAMAGED);
        if (holdsItems) return Decision.deny(RejectReason.CONTAINER_CONTENT);
        if (hasNonDefaultComponents) return Decision.deny(RejectReason.MODIFIED_COMPONENTS);
        return Decision.allow();
    }

    /** Base ids that are never recipe inputs, shared by both sides. */
    public static final Set<String> ALWAYS_FORBIDDEN = Set.of("minecraft:shulker_box", "minecraft:bundle");

    public static boolean isForbiddenId(String baseId) {
        if (baseId == null) return true;
        if (ALWAYS_FORBIDDEN.contains(baseId)) return true;
        return baseId.endsWith("_shulker_box");
    }

    /** Technical planning budget, separate from any product quota. */
    public record PlanningBudget(long maxNodes, long maxTrials, long maxMillis) {
        public PlanningBudget {
            if (maxNodes <= 0 || maxTrials <= 0 || maxMillis <= 0)
                throw new IllegalArgumentException("budget bounds must be positive");
        }

        public static PlanningBudget standard() {
            return new PlanningBudget(256, 20_000, 5_000);
        }
    }

    /** Structured planning refusal reasons (replaces free-text caps messages). */
    public enum Refusal {
        POLICY_DENIED,
        RECIPE_UNSUPPORTED,
        MATERIAL_SHORTAGE_PROVEN,
        PLANNING_BUDGET_EXHAUSTED,
        BLOCKED_CAPACITY,
        BLOCKED_STATION,
        LEASE_EXPIRED,
        NEEDS_RECONCILIATION
    }

    /** Cell allocation locked by the approved plan (PR9 #239, additive contract). */
    public record CellAllocation(int recipeIndex, int cellIndex, String exactVariantDigest, String lotRef, long count) {
        public CellAllocation {
            if (recipeIndex < 0 || cellIndex < 0) throw new IllegalArgumentException("negative index");
            if (exactVariantDigest == null || exactVariantDigest.isEmpty())
                throw new IllegalArgumentException("variant digest required");
            if (lotRef == null || lotRef.isEmpty()) throw new IllegalArgumentException("lot ref required");
            if (count <= 0) throw new IllegalArgumentException("count must be positive");
        }
    }

    /** Deterministic group order for matching: narrowest candidate set first. */
    public static List<List<String>> orderGroupsNarrowestFirst(List<List<String>> groups) {
        var ordered = new java.util.ArrayList<>(groups);
        ordered.sort((a, b) -> {
            int c = Integer.compare(a.size(), b.size());
            if (c != 0) return c;
            return a.toString().compareTo(b.toString());
        });
        return List.copyOf(ordered);
    }
}
