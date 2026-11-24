package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The wiki icon cache version: a cache from before version 2 may hold flat block textures (the "shulker tops"),
 * so its wiki icons are removed once; game renders are kept.
 */
class IconCacheVersionTest {

    private static IconService service(Path data) {
        return new IconService(data, id -> null, v -> false, new GameIcons(data.resolve("icons"), false, false), true);
    }

    @Test
    void sharedBudgetPrunesExistingFilesAndEvictsLeastRecentlyUsed(@TempDir Path data) throws IOException {
        var jar = write(data.resolve("sources/26.3/client.jar"));
        Path wiki = write(data.resolve("minecraft/stone.png"));
        Path render = write(data.resolve("game-26.3-v2/minecraft/diamond.png"));
        IconDiskCache cache = new IconDiskCache(data, 8192);
        cache.initialize();
        cache.read(wiki);
        for (int i = 0; i < 32; i++) {
            cache.write(data.resolve("mod/item" + i + ".png"), new byte[1024]);
            assertTrue(cache.read(wiki) != null);
        }
        assertFalse(Files.exists(render));
        assertTrue(Files.exists(jar));
        try (var files = Files.walk(data)) {
            assertEquals(2, files.filter(p -> p.toString().endsWith(".png")).count());
        }
        var restarted = new IconDiskCache(data, 4096);
        restarted.initialize();
        org.junit.jupiter.api.Assertions.assertThrows(
                IOException.class, () -> restarted.write(data.resolve("large.png"), new byte[8192]));
        try (var files = Files.walk(data)) {
            assertEquals(1, files.filter(p -> p.toString().endsWith(".png")).count());
        }
    }

    @Test
    void oldCacheLosesItsWikiIconsOnce(@TempDir Path data) throws IOException {
        Path icons = data.resolve("icons");
        Path wikiShulker = write(icons.resolve("minecraft/black_shulker_box.png"));
        Path wikiStone = write(icons.resolve("minecraft/stone.png"));
        Path miss = write(icons.resolve("minecraft/some_item.miss"));
        Path modded = write(icons.resolve("somemod/thing.png"));
        Path game = write(icons.resolve("game-26.3-v2/minecraft/stone.png"));
        Path gameShulker = write(icons.resolve("game-26.3-v2/minecraft/red_shulker_box.png"));

        IconService s = service(data);
        try {
            assertEquals(1, s.cacheVersion());
            assertEquals(4, s.migrateCache());
            assertFalse(Files.exists(wikiShulker));
            assertFalse(Files.exists(wikiStone));
            assertFalse(Files.exists(miss));
            assertFalse(Files.exists(modded));
            assertTrue(Files.exists(game), "game renders are kept");
            assertTrue(Files.exists(gameShulker), "game renders are kept");
            assertEquals(IconService.CACHE_VERSION, s.cacheVersion());
            assertEquals(
                    Integer.toString(IconService.CACHE_VERSION),
                    Files.readString(icons.resolve(IconService.CACHE_VERSION_FILE), StandardCharsets.US_ASCII)
                            .trim());

            // Once upgraded, new wiki icons stay.
            Path fresh = write(icons.resolve("minecraft/white_shulker_box.png"));
            assertEquals(0, s.migrateCache());
            assertTrue(Files.exists(fresh));
        } finally {
            s.close();
        }
    }

    @Test
    void newCacheStartsAtTheCurrentVersion(@TempDir Path data) throws IOException {
        IconService s = service(data);
        try {
            assertEquals(0, s.migrateCache());
            assertEquals(IconService.CACHE_VERSION, s.cacheVersion());
            Path png = write(data.resolve("icons/minecraft/stone.png"));
            assertEquals(0, s.migrateCache(), "a second start must not purge a new cache");
            assertTrue(Files.exists(png));
        } finally {
            s.close();
        }
    }

    @Test
    void currentCacheOnlyLosesRetryMarkers(@TempDir Path data) throws IOException {
        Path icons = data.resolve("icons");
        Files.createDirectories(icons);
        Files.writeString(icons.resolve(IconService.CACHE_VERSION_FILE), Integer.toString(IconService.CACHE_VERSION));
        Path png = write(icons.resolve("minecraft/stone.png"));
        Path retry = write(icons.resolve("minecraft/dirt.retry"));
        IconService s = service(data);
        try {
            assertEquals(1, s.migrateCache());
            assertTrue(Files.exists(png));
            assertFalse(Files.exists(retry));
        } finally {
            s.close();
        }
    }

    @Test
    void blockEntityItemsNeverUseFlatTextures() {
        for (String id : new String[] {
            "shulker_box",
            "black_shulker_box",
            "light_blue_shulker_box",
            "chest",
            "trapped_chest",
            "ender_chest",
            "waxed_oxidized_copper_chest",
            "red_bed",
            "white_banner",
            "dragon_head",
            "player_head",
            "skeleton_skull",
            "wither_skeleton_skull",
            "shield",
            "conduit",
            "decorated_pot",
            "copper_golem_statue",
            "exposed_copper_golem_statue"
        }) {
            assertTrue(IconService.blockEntityItem(id), id);
        }
        for (String id : new String[] {
            "stone",
            "chest_minecart",
            "bedrock",
            "banner_pattern",
            "flower_banner_pattern",
            "headless",
            "oak_planks",
            "diamond",
            "conduit_frame"
        }) {
            assertFalse(IconService.blockEntityItem(id), id);
        }
    }

    private static Path write(Path p) throws IOException {
        Files.createDirectories(p.getParent());
        Files.write(p, new byte[] {1, 2, 3});
        return p;
    }
}
