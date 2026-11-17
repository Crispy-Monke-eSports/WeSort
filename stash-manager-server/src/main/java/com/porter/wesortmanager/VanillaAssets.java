package com.porter.wesortmanager;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/** Official immutable 26.3 assets downloaded into this WeSort installation, never bundled in the image. */
final class VanillaAssets {
    static final String CLIENT_SHA1 = "e877b6a07acd633fb3bb475002175cec036e7b87";
    static final long CLIENT_BYTES = 41_483_720;
    private VanillaAssets() {}

    static Path client26(Path iconsDir, boolean eulaAccepted) throws IOException, InterruptedException {
        if (!eulaAccepted) throw new IOException("Minecraft download requires eula=true");
        Path directory = iconsDir.resolve("sources/26.3");
        if (Files.isSymbolicLink(iconsDir) || Files.isSymbolicLink(iconsDir.resolve("sources")) || Files.isSymbolicLink(directory)) throw new IOException("Symlink asset cache");
        Files.createDirectories(directory);
        Path target = directory.resolve("client-" + CLIENT_SHA1 + ".jar");
        if (valid(target)) return target;
        Path temporary = Files.createTempFile(directory, "client-", ".tmp");
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://piston-data.mojang.com/v1/objects/" + CLIENT_SHA1 + "/client.jar"))
                .timeout(Duration.ofSeconds(60)).header("User-Agent", "WeSort-Manager/26.3-assets").GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofFile(temporary));
            if (response.statusCode() != 200 || !valid(temporary)) throw new IOException("Official 26.3 client download failed checksum/size verification");
            AtomicFiles.move(temporary, target);
            System.out.println("Game icons 26.3: verified official client assets in isolated WeSort cache");
            return target;
        } finally { Files.deleteIfExists(temporary); }
    }

    static boolean valid(Path jar) throws IOException {
        if (Files.isSymbolicLink(jar) || !Files.isRegularFile(jar) || Files.size(jar) != CLIENT_BYTES) return false;
        try (var input = Files.newInputStream(jar)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[65536]; int n;
            while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
            return CLIENT_SHA1.equals(HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
