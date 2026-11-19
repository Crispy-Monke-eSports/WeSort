package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A four-box partial or out-of-order staging receipt cannot be called complete. */
class PackageLabelManifestTest {
    private JsonObject names() {
        var names = new JsonObject();
        names.addProperty("mode", "each");
        var labels = new JsonArray();
        for (int i = 1; i <= 4; i++) labels.add("WeSort (" + i + "/4) - Material");
        names.add("names", labels);
        var policy = new JsonObject();
        policy.addProperty("version", 1);
        policy.addProperty("text", "Material");
        policy.addProperty("total", 4);
        names.add("packageLabel", policy);
        return names;
    }

    private List<JsonObject> proofs() {
        var out = new ArrayList<JsonObject>();
        for (int i = 1; i <= 4; i++) {
            var p = new JsonObject();
            p.addProperty("operationId", "stage-" + i);
            p.addProperty("verifiedName", "WeSort (" + i + "/4) - Material");
            var staging = new JsonObject();
            staging.addProperty("dimension", "minecraft:overworld");
            var pos = new JsonArray();
            pos.add(3);
            pos.add(64);
            pos.add(4);
            staging.add("position", pos);
            staging.addProperty("slot", i - 1);
            p.add("staging", staging);
            out.add(p);
        }
        return out;
    }

    @Test
    void completeDistinctVerifiedFourBoxPlanIsAllowed() {
        var manifest = PackageManifest.staged("o-case", proofs(), 4);
        assertNotNull(manifest);
        assertTrue(PackageManifest.requiredLabelsVerified(manifest, names()));
        assertEquals(4, manifest.getAsJsonArray("packages").size());
    }

    @Test
    void partialOrReorderedReceiptDoesNotRenumberOrAllowPickup() {
        var partial = proofs().subList(0, 3);
        assertNull(
                PackageManifest.staged("o-case", partial, 4),
                "Missing 4/4 must be retained as missing, not renumbered 1/3..3/3");
        var reversed = proofs();
        var third = reversed.get(2);
        reversed.set(2, reversed.get(3));
        reversed.set(3, third);
        var outOfOrder = PackageManifest.staged("o-case", reversed, 4);
        assertNotNull(outOfOrder);
        assertFalse(PackageManifest.requiredLabelsVerified(outOfOrder, names()));
        var missing = proofs();
        missing.get(2).remove("verifiedName");
        var withoutReadback = PackageManifest.staged("o-case", missing, 4);
        assertFalse(PackageManifest.requiredLabelsVerified(withoutReadback, names()));
    }

    @Test
    void sameVisibleLabelCannotReplaceUniqueManifestPackageIdentity() {
        var first = PackageManifest.staged("o-order-one", proofs(), 4);
        var second = PackageManifest.staged("o-order-two", proofs(), 4);
        assertTrue(PackageManifest.requiredLabelsVerified(first, names()));
        assertTrue(PackageManifest.requiredLabelsVerified(second, names()));
        for (int i = 0; i < 4; i++) {
            String a = first.getAsJsonArray("packages")
                    .get(i)
                    .getAsJsonObject()
                    .get("packageId")
                    .getAsString();
            String b = second.getAsJsonArray("packages")
                    .get(i)
                    .getAsJsonObject()
                    .get("packageId")
                    .getAsString();
            assertNotEquals(a, b, "Visible names must never identify package custody");
        }
    }
}
