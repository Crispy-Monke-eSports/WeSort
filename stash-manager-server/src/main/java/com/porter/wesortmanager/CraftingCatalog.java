package com.porter.wesortmanager;

import com.google.gson.JsonObject;
import com.porter.wesort.craft.CraftPlanner;
import com.porter.wesort.craft.Recipes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The 26.3 crafting-table catalogue. Only shaped/shapeless 2x2 or 3x3 recipes
 * are supported; no furnace, stonecutter, smithing, special or remainder recipes.
 *
 * Availability is a hint. OrderStore re-plans and reserves physical ingredient
 * slots under the index lock before accepting a crafting order.
 */
final class CraftingCatalog {
    static final String VERSION = "26.3";
    static final int MAX_PREVIEW = 1024;
    static final Recipes RECIPES = Recipes.load(VERSION);

    private CraftingCatalog() {}

    /** Only unmodified vanilla stacks may be treated as recipe ingredients. */
    static Map<String, Long> eligiblePool(Map<String, Long> physicalAvailable) {
        Map<String, Long> pool = new HashMap<>();
        for (var e : physicalAvailable.entrySet()) {
            if (!e.getKey().startsWith("item:")) continue;
            String id = e.getKey().substring("item:".length());
            if (!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || e.getValue() <= 0) continue;
            pool.merge(id, e.getValue(), Math::addExact);
        }
        return pool;
    }

    static CraftPlanner.Result plan(String output, int count, Map<String, Long> available) {
        if (RECIPES == null
                || output.endsWith("shulker_box")
                || !output.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                || RECIPES.producing(output).isEmpty())
            return new CraftPlanner.Result(null, "No 2x2/3x3 crafting-table recipe");
        return CraftPlanner.plan(RECIPES, output, count, available, Set.of(), CraftPlanner.Caps.NONE);
    }

    /** Immutable result sorted by item id, excluding impossible recipes. */
    static JsonObject available(Map<String, Long> physicalAvailable, boolean tableReady) {
        JsonObject out = new JsonObject();
        if (!tableReady || RECIPES == null) return out;
        Map<String, Long> pool = eligiblePool(physicalAvailable);
        if (pool.isEmpty()) return out;
        List<String> results = new ArrayList<>(RECIPES.outputs());
        results.sort(Comparator.naturalOrder());

        // Build a cheap over-approximation of three crafting levels first.
        // Running the recursive planner for every Minecraft recipe on each
        // order-list request would otherwise dominate dashboard latency.
        var reachable = new java.util.HashSet<>(pool.keySet());
        for (int depth = 0; depth < CraftPlanner.MAX_DEPTH; depth++) {
            var newlyReachable = new java.util.HashSet<String>();
            for (String result : results) {
                if (reachable.contains(result)) continue;
                for (var recipe : RECIPES.producing(result)) {
                    boolean possible = true;
                    for (var alts : recipe.cells()) {
                        if (alts == null) continue;
                        if (alts.stream().noneMatch(reachable::contains)) {
                            possible = false;
                            break;
                        }
                    }
                    if (possible) {
                        newlyReachable.add(result);
                        break;
                    }
                }
            }
            if (newlyReachable.isEmpty()) break;
            reachable.addAll(newlyReachable);
        }
        for (String id : results) {
            if (!reachable.contains(id)) continue;
            var p = plan(id, MAX_PREVIEW, pool);
            if (p.ok() && p.plan().count() > 0) out.addProperty(id, p.plan().count());
        }
        return out;
    }

    static void consume(Map<String, Long> pool, Map<String, Integer> need) {
        for (var e : need.entrySet()) {
            long remaining = pool.getOrDefault(e.getKey(), 0L) - e.getValue();
            if (remaining < 0) throw new IllegalArgumentException("Crafting pool exhausted");
            pool.put(e.getKey(), remaining);
        }
    }
}
