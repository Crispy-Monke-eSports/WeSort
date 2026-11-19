package com.porter.wesortmanager;

import com.porter.wesortmanager.assets.IconRenderer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;

/**
 * Renders Minecraft 26.3 icons exclusively from the official client JAR that
 * this Manager downloads, verifies and caches itself after explicit EULA acceptance.
 * Never probes the host's Minecraft installations or user-provided JAR paths.
 */
final class GameIcons implements AutoCloseable {
    static final int SIZE = 64;

    private final Path iconsDir;
    private final boolean enabled;
    private final boolean eulaAccepted;
    private final Map<String, Optional<IconRenderer>> renderers = new ConcurrentHashMap<>();
    /** Per version: ids the renderer can't draw (block-entity items etc.), so they aren't retried. */
    private final Map<String, Set<String>> empty = new ConcurrentHashMap<>();

    GameIcons(Path iconsDir, boolean enabled, boolean eulaAccepted) {
        this.iconsDir = iconsDir.toAbsolutePath().normalize();
        this.enabled = enabled;
        this.eulaAccepted = eulaAccepted;
    }

    boolean enabled() {
        return enabled;
    }

    /** Whether a client jar for this version was found and opened (opens it on first call). */
    boolean available(String mcVersion) {
        return renderer(mcVersion).isPresent();
    }

    private Path cacheFile(String mcVersion, String ns, String path) {
        return iconsDir.resolve("game-26.3-v2").resolve(ns).resolve(path + ".png");
    }

    /** True when a game render for this id is already on disk. */
    boolean cached(String mcVersion, String ns, String path) {
        return enabled
                && IconService.MC_VERSION.matcher(mcVersion).matches()
                && Files.isRegularFile(cacheFile(mcVersion, ns, path));
    }

    /**
     * The game-rendered PNG for an id: from the cache, or rendered now (about 5 ms cold) and cached.
     * Null when the source is off, no jar exists for the version, or the renderer can't draw the item.
     * ns, path and mcVersion must already be validated (IconService.ID_PART / MC_VERSION).
     */
    byte[] get(String mcVersion, String ns, String path) {
        if (!enabled || !IconService.MC_VERSION.matcher(mcVersion).matches()) return null;
        Path file = cacheFile(mcVersion, ns, path);
        try {
            if (Files.isRegularFile(file)) return Files.readAllBytes(file);
        } catch (IOException ignored) {
        }
        return render(mcVersion, ns, path, file);
    }

    /** Renders and caches if not cached. Returns false when the game can't provide this icon. */
    boolean ensure(String mcVersion, String ns, String path) {
        if (cached(mcVersion, ns, path)) return true;
        return get(mcVersion, ns, path) != null;
    }

    private byte[] render(String mcVersion, String ns, String path, Path file) {
        Optional<IconRenderer> r = renderer(mcVersion);
        if (r.isEmpty()) return null;
        String id = ns + ":" + path;
        Set<String> none = empty.computeIfAbsent(mcVersion, v -> ConcurrentHashMap.newKeySet());
        if (none.contains(id)) return null;
        Optional<BufferedImage> img = r.get().render(id);
        if (img.isEmpty()) {
            none.add(id);
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(img.get(), "png", out)) return null;
            byte[] png = out.toByteArray();
            AtomicFiles.write(file, png);
            return png;
        } catch (IOException e) {
            System.err.println("Could not cache game icon " + id + ": " + e.getMessage());
            return null;
        }
    }

    private Optional<IconRenderer> renderer(String mcVersion) {
        if (!enabled || !IconService.MC_VERSION.matcher(mcVersion).matches()) return Optional.empty();
        return renderers.computeIfAbsent(mcVersion, this::open);
    }

    /** Read only the checksum-verified official client from the private Manager cache. */
    private Optional<IconRenderer> open(String mcVersion) {
        if (!"26.3".equals(mcVersion) || !eulaAccepted) return Optional.empty();
        try {
            return Optional.of(IconRenderer.open(VanillaAssets.client26(iconsDir, eulaAccepted), SIZE));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Game icons 26.3: interrupted while loading official client assets");
        } catch (IOException | RuntimeException e) {
            System.err.println("Game icons 26.3: official client assets unavailable: " + e.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public void close() {
        for (Optional<IconRenderer> r : renderers.values()) {
            r.ifPresent(renderer -> {
                try {
                    renderer.close();
                } catch (IOException ignored) {
                }
            });
        }
    }
}
