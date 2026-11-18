package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.wesort.data.ContainerIdentity;
import com.porter.wesort.data.CanonicalJson;
import java.util.HashSet;
import java.util.List;

/** Exact native transfers that explain custody at the warehouse handoff. */
final class PackageManifest {
    private PackageManifest() {}

    static JsonObject staged(String order, List<JsonObject> proofs, int expected) {
        if (proofs.size() != expected || expected < 1 || expected > OrderStore.MAX_SHULKERS) return null;
        var packages = new JsonArray();
        var destinations = new HashSet<String>();
        var operations = new HashSet<String>();
        for (var transfer : proofs) {
            if (transfer == null || !destinations.add(transfer.getAsJsonObject("staging").toString())) return null;
            String operation = transfer.get("operationId").getAsString();
            if (!operations.add(operation)) return null;
            var entry = transfer.deepCopy();
            String identity = order + "/" + operation;
            entry.addProperty("packageId", java.util.UUID.nameUUIDFromBytes(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
            packages.add(entry);
        }
        var manifest = new JsonObject();
        manifest.addProperty("version", 1);
        manifest.add("packages", packages);
        manifest.addProperty("hash", ContainerIdentity.hash(CanonicalJson.stringify(manifest)));
        return manifest;
    }

    static JsonObject forAgent(JsonObject manifest, String dimension) {
        var view = manifest.deepCopy();
        var packages = view.getAsJsonArray("packages");
        for (var entry : packages) {
            var verified = entry.getAsJsonObject();
            var staging = verified.getAsJsonObject("staging");
            if (!dimension.equals(staging.get("dimension").getAsString()) || !verified.has("packageId")) return null;
            verified.remove("staging");
        }
        return view;
    }

    static JsonObject transfer(JsonObject operation) {
        try {
            var context = operation.getAsJsonObject("context");
            JsonObject intent = null;
            JsonObject frame = null;
            for (var event : operation.getAsJsonArray("events")) {
                var row = event.getAsJsonObject();
                String type = row.get("type").getAsString();
                if ("INTENT".equals(type)) {
                    if (intent != null) return null;
                    intent = row.getAsJsonObject("evidence");
                } else if ("VERIFIED".equals(type) || "RECONCILED".equals(type)) {
                    frame = row.getAsJsonObject("evidence").getAsJsonObject("frame");
                }
            }
            if (intent == null || frame == null) return null;
            var origin = intent.getAsJsonObject("origin");
            if (!origin.get("server").equals(context.get("server"))
                || !origin.get("dimension").equals(context.get("dimension"))
                || !origin.get("player").equals(context.get("agent"))) return null;
            var location = origin.getAsJsonArray("source");
            if (location.size() != 3) return null;
            for (var position : location) position.getAsBigDecimal().intValueExact();
            var before = intent.getAsJsonObject("before");
            if (!"empty".equals(before.get("cursor").getAsString()) || !"empty".equals(frame.get("cursor").getAsString())) return null;
            var beforeSlots = before.getAsJsonArray("slots");
            var afterSlots = frame.getAsJsonArray("slots");
            int containerSlots = beforeSlots.size() - 36;
            if ((containerSlots != 27 && containerSlots != 54) || beforeSlots.size() != afterSlots.size()) return null;

            String received = null;
            String removed = null;
            int destinationSlot = -1;
            for (int i = 0; i < beforeSlots.size(); i++) {
                String old = beforeSlots.get(i).getAsString();
                String next = afterSlots.get(i).getAsString();
                if (old.equals(next)) continue;
                if (i < containerSlots && received == null && "empty".equals(old)) {
                    received = next;
                    destinationSlot = i;
                } else if (i >= containerSlots && removed == null && "empty".equals(next)) {
                    removed = old;
                } else {
                    return null;
                }
            }
            if (received == null || !received.equals(removed) || !oneShulker(received)) return null;
            var staging = new JsonObject();
            staging.add("dimension", origin.get("dimension"));
            staging.add("position", location.deepCopy());
            staging.addProperty("slot", destinationSlot);
            var proof = new JsonObject();
            proof.add("operationId", operation.get("id"));
            proof.add("revision", operation.get("revision"));
            proof.add("checksum", operation.get("checksum"));
            proof.addProperty("stackFingerprint", ContainerIdentity.hash(received));
            proof.add("staging", staging);
            return proof;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static boolean oneShulker(String fingerprint) {
        var item = JsonParser.parseString(fingerprint).getAsJsonObject().getAsJsonObject("value");
        String count = item.getAsJsonObject("count").get("value").getAsString();
        String id = JsonParser.parseString(item.getAsJsonObject("id").get("value").getAsString()).getAsString();
        return "1".equals(count) && id.matches("minecraft:(?:(?:white|orange|magenta|light_blue|yellow|lime|pink|gray|light_gray|cyan|purple|blue|brown|green|red|black)_)?shulker_box");
    }
}
