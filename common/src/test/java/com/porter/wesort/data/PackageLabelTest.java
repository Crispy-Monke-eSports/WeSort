package com.porter.wesort.data;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class PackageLabelTest {
    @Test
    void generatesExactWeSortPrefixAndFreezesTotal() {
        assertEquals("WeSort (1/4) - Byggmaterial", PackageLabel.name(1, 4, "Byggmaterial"));
        assertEquals("WeSort (4/4) - Byggmaterial", PackageLabel.name(4, 4, "Byggmaterial"));
        assertEquals("WeSort (54/54) - Projekt A", PackageLabel.name(54, 54, "Projekt A"));
        assertEquals("WeSort (1/1) - Beställning", PackageLabel.name(1, 1, "Beställning"));
    }

    @Test
    void numberOutOfRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PackageLabel.name(0, 1, "x"));
        assertThrows(IllegalArgumentException.class, () -> PackageLabel.name(2, 1, "x"));
        assertThrows(IllegalArgumentException.class, () -> PackageLabel.name(1, 55, "x"));
    }

    @Test
    void textBudgetIsThirtyThreeCodeUnits() {
        assertEquals("a".repeat(33), PackageLabel.text("a".repeat(33), "packageLabel.text"));
        assertThrows(IllegalArgumentException.class, () -> PackageLabel.text("a".repeat(34), "packageLabel.text"));
        // 17 + 33 == 50: the full name stays inside the anvil limit.
        assertEquals(50, PackageLabel.name(54, 54, "a".repeat(33)).length());
    }

    @Test
    void textRejectsSectionControlAndInvisibleCharacters() {
        assertThrows(IllegalArgumentException.class, () -> PackageLabel.text("a§b", "packageLabel.text"));
        assertThrows(IllegalArgumentException.class, () -> PackageLabel.text("a\nb", "packageLabel.text"));
        assertThrows(IllegalArgumentException.class, () -> PackageLabel.text("a\u200eb", "packageLabel.text")); // LRM
        assertThrows(
                IllegalArgumentException.class,
                () -> PackageLabel.text("a\uD800b", "packageLabel.text")); // broken surrogate
        assertThrows(IllegalArgumentException.class, () -> PackageLabel.text("\t", "packageLabel.text"));
        assertThrows(IllegalArgumentException.class, () -> PackageLabel.text("\u0085test", "packageLabel.text"));
        assertThrows(IllegalArgumentException.class, () -> PackageLabel.text("test\uDB40\uDC01", "packageLabel.text"));
        assertEquals("Kit 📦", PackageLabel.text("\u3000Kit 📦 ", "packageLabel.text"));
        assertEquals("\u00a0", PackageLabel.text("\u00a0", "packageLabel.text"));
        assertEquals("abc", PackageLabel.text("  abc  ", "packageLabel.text"));
        assertEquals(PackageLabel.DEFAULT_TEXT, PackageLabel.text("   ", "packageLabel.text"));
    }

    @Test
    void normalizeCommonAndPerBoxLabels() {
        var common = new JsonObject();
        common.addProperty("text", "  Byggmaterial  ");
        var normalized = PackageLabel.normalize(common);
        assertEquals("all", normalized.get("mode").getAsString());
        assertEquals("Byggmaterial", normalized.get("text").getAsString());

        var each = new JsonObject();
        each.add(
                "texts",
                com.google.gson.JsonParser.parseString("[\"A\",\"B\",\"\"]").getAsJsonArray());
        var normalizedEach = PackageLabel.normalize(each);
        assertEquals("each", normalizedEach.get("mode").getAsString());
        assertEquals(2, normalizedEach.getAsJsonArray("texts").size());
        assertEquals("A", normalizedEach.getAsJsonArray("texts").get(0).getAsString());
        assertEquals("B", normalizedEach.getAsJsonArray("texts").get(1).getAsString());
    }

    @Test
    void ambiguousAndMalformedFrozenLabelsAreRejected() {
        var both = new JsonObject();
        both.addProperty("text", "A");
        both.add("texts", new com.google.gson.JsonArray());
        assertThrows(IllegalArgumentException.class, () -> PackageLabel.normalize(both));
        var wrongMode = new JsonObject();
        wrongMode.addProperty("mode", "each");
        wrongMode.addProperty("text", "A");
        assertThrows(IllegalArgumentException.class, () -> PackageLabel.generate(wrongMode, 1));
        var invalid = new JsonObject();
        invalid.addProperty("mode", "all");
        invalid.addProperty("text", "a".repeat(34));
        assertThrows(IllegalArgumentException.class, () -> PackageLabel.generate(invalid, 1));
        assertThrows(IllegalArgumentException.class, () -> PackageLabel.name(1, 1, "bad§text"));
    }

    @Test
    void generateProducesEveryBoxForFrozenTotal() {
        var common = new JsonObject();
        common.addProperty("text", "Projekt A");
        var names = PackageLabel.generate(PackageLabel.normalize(common), 4);
        assertEquals(4, names.size());
        assertEquals("WeSort (1/4) - Projekt A", names.get(0).getAsString());
        assertEquals("WeSort (4/4) - Projekt A", names.get(3).getAsString());

        // Default when no label is supplied.
        var defaulted = PackageLabel.generate(null, 2);
        assertEquals("WeSort (1/2) - Beställning", defaulted.get(0).getAsString());
        assertEquals("WeSort (2/2) - Beställning", defaulted.get(1).getAsString());

        // Per-box suffixes fill the frozen total with the default where absent.
        var each = new JsonObject();
        each.add("texts", com.google.gson.JsonParser.parseString("[\"X\"]").getAsJsonArray());
        var eachNames = PackageLabel.generate(PackageLabel.normalize(each), 3);
        assertEquals("WeSort (1/3) - X", eachNames.get(0).getAsString());
        assertEquals("WeSort (2/3) - Beställning", eachNames.get(1).getAsString());
        assertEquals("WeSort (3/3) - Beställning", eachNames.get(2).getAsString());
    }
}
