package com.porter.wesortmanager.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Block-entity ("special") item icons. Shulker boxes used to fall back to the wiki pipeline, which could cache
 * mcasset's flat block texture; they must render as the same isometric box as a full block.
 */
class SpecialModelsTest {
    static final String[] COLOURS = {
        "",
        "white_",
        "orange_",
        "magenta_",
        "light_blue_",
        "yellow_",
        "lime_",
        "pink_",
        "gray_",
        "light_gray_",
        "cyan_",
        "purple_",
        "blue_",
        "brown_",
        "green_",
        "red_",
        "black_"
    };

    /** A minimal synthetic model-format fixture: stone and a red shulker box with flat textures. */
    @Test
    void shulkerBoxRendersIsometricFromAMinimalJar(@TempDir Path dir) throws IOException {
        Path jar = dir.resolve("client.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            put(
                    z,
                    "assets/minecraft/items/stone.json",
                    "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:block/stone\"}}");
            put(
                    z,
                    "assets/minecraft/models/block/stone.json",
                    "{\"parent\":\"block/cube_all\",\"textures\":{\"all\":\"block/stone\"}}");
            put(
                    z,
                    "assets/minecraft/models/block/cube_all.json",
                    "{\"parent\":\"block/block\",\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],"
                            + "\"faces\":{\"down\":{\"texture\":\"#all\"},\"up\":{\"texture\":\"#all\"},\"north\":{\"texture\":\"#all\"},"
                            + "\"south\":{\"texture\":\"#all\"},\"west\":{\"texture\":\"#all\"},\"east\":{\"texture\":\"#all\"}}}]}");
            put(
                    z,
                    "assets/minecraft/models/block/block.json",
                    "{\"display\":{\"gui\":{\"rotation\":[30,225,0],\"translation\":[0,0,0],\"scale\":[0.625,0.625,0.625]}}}");
            put(
                    z,
                    "assets/minecraft/items/red_shulker_box.json",
                    "{\"model\":{\"type\":\"minecraft:special\",\"base\":\"minecraft:item/red_shulker_box\","
                            + "\"model\":{\"type\":\"minecraft:shulker_box\",\"texture\":\"minecraft:shulker_red\"}}}");
            put(
                    z,
                    "assets/minecraft/models/item/red_shulker_box.json",
                    "{\"parent\":\"minecraft:item/template_shulker_box\",\"textures\":{\"particle\":\"minecraft:block/red_shulker_box\"}}");
            put(
                    z,
                    "assets/minecraft/models/item/template_shulker_box.json",
                    "{\"display\":{\"gui\":{\"rotation\":[30,45,0],\"translation\":[0,0,0],\"scale\":[0.625,0.625,0.625]}}}");
            put(z, "assets/minecraft/textures/block/stone.png", png(16, 16, 0xFF808080));
            put(z, "assets/minecraft/textures/entity/shulker/shulker_red.png", png(64, 64, 0xFFB02E26));
            // the flat 16x16 face the old fallback cached
            put(z, "assets/minecraft/textures/block/red_shulker_box.png", png(16, 16, 0xFFB02E26));
        }
        try (IconRenderer r = IconRenderer.open(jar, 64)) {
            assertNull(r.explain("minecraft:red_shulker_box"));
            BufferedImage shulker = r.render("minecraft:red_shulker_box").orElseThrow();
            BufferedImage stone = r.render("minecraft:stone").orElseThrow();
            assertIsometric(shulker);
            assertTrue(silhouetteMatch(shulker, stone) > 0.97, "shulker box silhouette should match a full block");
            // Three visible faces, lit differently (top brightest): not a single flat colour.
            assertTrue(brightness(shulker.getRGB(32, 12)) > brightness(shulker.getRGB(20, 40)));
        }
    }

    @Test
    void unsupportedSpecialTypesFallBack() {
        assertNull(SpecialModels.build(
                com.google.gson.JsonParser.parseString("{\"type\":\"minecraft:head\",\"kind\":\"dragon\"}")
                        .getAsJsonObject(),
                t -> Optional.of(new IconRenderer.Tex(1, 1, new int[] {0xFFFFFFFF}))));
        assertNull(SpecialModels.build(
                com.google.gson.JsonParser.parseString("{\"type\":\"minecraft:copper_golem_statue\"}")
                        .getAsJsonObject(),
                t -> Optional.of(new IconRenderer.Tex(1, 1, new int[] {0xFFFFFFFF}))));
        // a missing texture also means "can't draw"
        assertNull(SpecialModels.build(
                com.google.gson.JsonParser.parseString(
                                "{\"type\":\"minecraft:shulker_box\",\"texture\":\"minecraft:shulker\"}")
                        .getAsJsonObject(),
                t -> Optional.empty()));
    }

    // Real Minecraft 26.3 rendering is covered by scripts/mc26/assets-harness.py.
    // Unit tests never inspect a locally installed game or implicitly download Mojang assets.

    // ------------------------------------------------------------------ helpers

    /** Transparent corners, opaque centre: a block icon, not a full-square flat texture. */
    static void assertIsometric(BufferedImage img) {
        assertEquals(0, img.getRGB(1, 1) >>> 24, "top-left corner should be transparent");
        assertEquals(0, img.getRGB(62, 1) >>> 24, "top-right corner should be transparent");
        assertEquals(0, img.getRGB(1, 62) >>> 24, "bottom-left corner should be transparent");
        assertEquals(255, img.getRGB(32, 32) >>> 24, "centre should be opaque");
    }

    /** Intersection over union of the two alpha masks. */
    static double silhouetteMatch(BufferedImage a, BufferedImage b) {
        int both = 0, either = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                boolean pa = (a.getRGB(x, y) >>> 24) > 0, pb = (b.getRGB(x, y) >>> 24) > 0;
                if (pa && pb) both++;
                if (pa || pb) either++;
            }
        }
        return either == 0 ? 0 : (double) both / either;
    }

    static int brightness(int argb) {
        return ((argb >> 16) & 255) + ((argb >> 8) & 255) + (argb & 255);
    }

    static void put(ZipOutputStream z, String name, String text) throws IOException {
        put(z, name, text.getBytes(StandardCharsets.UTF_8));
    }

    static void put(ZipOutputStream z, String name, byte[] data) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(data);
        z.closeEntry();
    }

    static byte[] png(int w, int h, int argb) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, argb);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertNotNull(ImageIO.getImageWritersByFormatName("png").next());
        ImageIO.write(img, "png", (OutputStream) out);
        return out.toByteArray();
    }
}
