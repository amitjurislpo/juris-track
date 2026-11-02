package com.jurislpo.juristrack.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.InvalidKeyException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Session and refresh tokens: random base64url values in the cookie, only their SHA-256 (hex) in the database. */
public final class Tokens {
    private Tokens() {}

    private static final SecureRandom RANDOM = new SecureRandom();

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** HMAC-SHA-256 (hex) of {@code text} under a server secret: unlike a plain hash it cannot be guessed offline from a stolen database. */
    public static String hmacSha256(byte[] secret, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code byteCount} random bytes as base64url without padding. */
    public static String randomToken(int byteCount) {
        byte[] randomBytes = new byte[byteCount];
        RANDOM.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    /** A random six-digit sign-in code (leading zeros kept). */
    public static String randomSixDigitCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    /** Equality that takes the same time wherever the texts differ (for hashes). */
    public static boolean isEqualInConstantTime(String first, String second) {
        if (first == null || second == null) return false;
        return MessageDigest.isEqual(first.getBytes(StandardCharsets.UTF_8), second.getBytes(StandardCharsets.UTF_8));
    }
}
