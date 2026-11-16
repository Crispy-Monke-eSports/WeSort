package com.porter.wesortmanager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.function.LongSupplier;

/** A local administrator issues a 256-bit capability; only its digest lives in memory, for at most ten minutes. */
final class BootstrapToken {
    static final long LIFETIME_MS = 10 * 60_000;
    private final LongSupplier clock;
    private byte[] digest;
    private long expires;

    BootstrapToken() { this(System::currentTimeMillis); }
    BootstrapToken(LongSupplier clock) { this.clock = clock; }

    synchronized String issue() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        digest = hash(token);
        expires = clock.getAsLong() + LIFETIME_MS;
        return token;
    }

    synchronized boolean matches(String token) {
        return token != null && token.matches("[A-Za-z0-9_-]{43}") && digest != null
            && clock.getAsLong() < expires && MessageDigest.isEqual(digest, hash(token));
    }

    synchronized void clear() { digest = null; expires = 0; }

    private static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
