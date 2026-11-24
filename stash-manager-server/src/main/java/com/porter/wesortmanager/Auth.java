package com.porter.wesortmanager;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * The dashboard password hash: {@code pbkdf2-sha256$<iterations>$<salt b64>$<hash b64>} (PBKDF2WithHmacSHA256, a 16-byte
 * random salt, a 32-byte hash). The stored string lives in config.properties as dashboardPassword=.
 */
final class Auth {
    static final int ITERATIONS = 600_000;
    static final int MIN_LENGTH = 12;
    static final int MAX_LENGTH = 1024;
    private static final String PREFIX = "pbkdf2-sha256";
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    /** Upper bound on the iteration count accepted from a stored hash, so a hand-edited line can't stall logins. */
    private static final int MAX_ITERATIONS = 10_000_000;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** The most common passwords (lowercase); refused for new passwords whatever their length. */
    private static final java.util.Set<String> COMMON = java.util.Set.of(
            "123456",
            "password",
            "12345678",
            "qwerty",
            "123456789",
            "12345",
            "1234",
            "111111",
            "1234567",
            "dragon",
            "123123",
            "baseball",
            "abc123",
            "football",
            "monkey",
            "letmein",
            "696969",
            "shadow",
            "master",
            "666666",
            "qwertyuiop",
            "123321",
            "mustang",
            "1234567890",
            "michael",
            "654321",
            "superman",
            "1qaz2wsx",
            "7777777",
            "121212",
            "000000",
            "qazwsx",
            "123qwe",
            "killer",
            "trustno1",
            "jordan",
            "jennifer",
            "zxcvbnm",
            "asdfgh",
            "hunter",
            "buster",
            "soccer",
            "harley",
            "batman",
            "andrew",
            "tigger",
            "sunshine",
            "iloveyou",
            "2000",
            "charlie",
            "robert",
            "thomas",
            "hockey",
            "ranger",
            "daniel",
            "starwars",
            "klaster",
            "112233",
            "george",
            "computer",
            "michelle",
            "jessica",
            "pepper",
            "1111",
            "zxcvbn",
            "555555",
            "11111111",
            "131313",
            "freedom",
            "777777",
            "pass",
            "maggie",
            "159753",
            "aaaaaa",
            "ginger",
            "princess",
            "joshua",
            "cheese",
            "amanda",
            "summer",
            "love",
            "ashley",
            "nicole",
            "chelsea",
            "biteme",
            "matthew",
            "access",
            "yankees",
            "987654321",
            "dallas",
            "austin",
            "thunder",
            "taylor",
            "matrix",
            "minecraft",
            "password1",
            "password123",
            "password1234",
            "passwordpassword",
            "qwerty123456",
            "qwertyuiop123",
            "qwertyuiop12",
            "123456789012",
            "1234567890123",
            "12345678910",
            "111111111111",
            "000000000000",
            "123123123123",
            "abcdefghijkl",
            "abc123456789",
            "iloveyou1234",
            "letmein12345",
            "welcome12345",
            "welcome123456",
            "administrator",
            "adminadmin123",
            "changeme1234",
            "minecraft123",
            "minecraft1234",
            "stashmanager",
            "stash-manager",
            "stashmanager123",
            "1q2w3e4r5t6y",
            "qazwsxedcrfv",
            "zaq12wsxcde3",
            "asdfghjkl123",
            "trustno1trustno1",
            "superman1234",
            "monkey123456",
            "dragon123456",
            "football1234",
            "baseball1234",
            "sunshine1234",
            "princess1234");

    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O, 1/I

    private Auth() {}

    /** A problem with a new password (too short, too long, too common), or null. */
    static String checkNew(String p) {
        if (p == null) return "password must be a string";
        if (p.length() < MIN_LENGTH) return "the password needs at least " + MIN_LENGTH + " characters";
        if (p.length() > MAX_LENGTH) return "the password is too long";
        if (COMMON.contains(p.toLowerCase(java.util.Locale.ROOT))) return "that password is too common; pick another";
        return null;
    }

    /** A one-time first-run setup code, "XXXX-XXXX". */
    static String newSetupCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i == 4) sb.append('-');
            sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    /** Whether a typed code matches (case, spaces and dashes ignored; constant time). */
    static boolean codeMatches(String given, String code) {
        if (given == null || code == null) return false;
        String a = given.replaceAll("[\\s-]", "").toUpperCase(java.util.Locale.ROOT);
        String b = code.replace("-", "");
        return MessageDigest.isEqual(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * A short fingerprint of a stored hash, kept with each session: a session made with a password that has since
     * changed no longer matches and ends (even one that was being logged in while the password changed).
     */
    static String tag(String stored) {
        if (stored == null) return null;
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(stored.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The iterations new hashes get. Only tests lower it (so a test can create many users quickly). */
    static volatile int iterations = ITERATIONS;

    private static volatile String dummy;

    static String hash(String password) {
        return hash(password, iterations);
    }

    /**
     * A synthetic hash with the same verification cost as a real credential.
     * Generate random salt and digest directly, without running a KDF before
     * the login rate limiter has admitted the request.
     */
    static String dummyHash() {
        String d = dummy;
        int rounds = iterations;
        if (d == null || !d.contains("$" + rounds + "$")) {
            byte[] salt = new byte[SALT_BYTES];
            byte[] digest = new byte[HASH_BITS / 8];
            RANDOM.nextBytes(salt);
            RANDOM.nextBytes(digest);
            Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
            dummy = d = PREFIX + "$" + rounds + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(digest);
        }
        return d;
    }

    /** Package-private so tests can use fewer iterations. */
    static String hash(String password, int iterations) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(password, salt, iterations);
        Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
        return PREFIX + "$" + iterations + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(hash);
    }

    private record PasswordHash(int rounds, byte[] salt, byte[] digest) {}

    /** Parse once with exact bounds; invalid persisted credentials never reach PBKDF2. */
    private static PasswordHash parse(String stored) {
        if (stored == null || stored.length() > 160) return null;
        String[] parts = stored.split("\\$", -1);
        if (parts.length != 4 || !parts[0].equals(PREFIX) || !parts[1].matches("[0-9]{1,8}")) return null;
        try {
            int rounds = Integer.parseInt(parts[1]);
            if (rounds < 1 || rounds > MAX_ITERATIONS) return null;
            Base64.Decoder b64 = Base64.getDecoder();
            byte[] salt = b64.decode(parts[2]);
            byte[] digest = b64.decode(parts[3]);
            if (salt.length != SALT_BYTES || digest.length != HASH_BITS / 8) return null;
            return new PasswordHash(rounds, salt, digest);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /** True when password matches the stored string. A malformed stored string never matches. */
    static boolean verify(String password, String stored) {
        if (password == null || password.length() > MAX_LENGTH) return false;
        PasswordHash hash = parse(stored);
        if (hash == null) return false;
        return MessageDigest.isEqual(pbkdf2(password, hash.salt(), hash.rounds()), hash.digest());
    }

    /** Whether a stored credential can actually be verified. */
    static boolean wellFormed(String stored) {
        return parse(stored) != null;
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        char[] chars = password.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 is not available", e);
        } finally {
            spec.clearPassword();
            Arrays.fill(chars, '\0');
        }
    }
}
