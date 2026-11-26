package com.porter.wesort.data;

import com.google.gson.*;
import java.text.Normalizer;
import java.util.Locale;

/** A guild-shareable definition, never a claim that any physical box currently exists. */
public record KitTemplate(String name, PhysicalSlots.Snapshot layout) {
    public static final String FORMAT = "wesort-kit:3", MINECRAFT = "26.3";

    public KitTemplate {
        if (name == null
                || name.isBlank()
                || name.length() > 64
                || name.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid WeSort kit name");
        name = name.trim();
        if (!nameKey(name).matches(".*[a-z0-9].*"))
            throw new IllegalArgumentException("Kit name needs letters or numbers");
        if (layout == null || layout.size() != 27 || layout.slots().isEmpty())
            throw new IllegalArgumentException("A kit needs 27 slots and at least one item");
        if (layout.located().stream().anyMatch(slot -> slot.stack().item().kind() == PhysicalSlots.Kind.SHULKER))
            throw new IllegalArgumentException("Minecraft shulkers cannot contain shulkers");
    }

    public String key() {
        return nameKey(name);
    }

    public static String nameKey(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replace('_', ' ')
                .replace('-', ' ')
                .replaceAll("[^a-z0-9*& ]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public String fingerprint() {
        return layout.fingerprint();
    }

    public JsonObject json() {
        var out = layout.envelope();
        out.addProperty("format", FORMAT);
        out.addProperty("minecraft", MINECRAFT);
        out.addProperty("name", name);
        out.addProperty("size", 27);
        out.addProperty("fingerprint", fingerprint());
        return out;
    }

    public static KitTemplate read(JsonObject value) {
        if (!FORMAT.equals(value.get("format").getAsString())
                || !MINECRAFT.equals(value.get("minecraft").getAsString())
                || value.get("size").getAsBigDecimal().intValueExact() != 27)
            throw new IllegalArgumentException("Only new WeSort Minecraft 26.3 kit definitions are accepted");
        var layout = PhysicalSlots.Snapshot.readEnvelope(value);
        if (!layout.fingerprint().equals(value.get("fingerprint").getAsString()))
            throw new IllegalArgumentException("Kit content fingerprint mismatch");
        return new KitTemplate(value.get("name").getAsString(), layout);
    }
}
