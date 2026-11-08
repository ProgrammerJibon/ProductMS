package com.productms.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.mindrot.jbcrypt.BCrypt;

/**
 * Password hashing with bcrypt (cost 12, salted). Old unsalted SHA-256 hashes are still
 * accepted so existing accounts can sign in once; {@link #needsRehash} tells the caller to
 * replace them with bcrypt immediately.
 */
public final class PasswordUtil {

    private static final int COST = 12;

    private PasswordUtil() {
    }

    public static String hash(String password) {
        return BCrypt.hashpw(password, BCrypt.gensalt(COST));
    }

    public static boolean verify(String password, String stored) {
        if (stored == null || password == null) {
            return false;
        }
        if (stored.startsWith("$2a$") || stored.startsWith("$2b$") || stored.startsWith("$2y$")) {
            try {
                return BCrypt.checkpw(password, stored.replaceFirst("^\\$2[by]\\$", "\\$2a\\$"));
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        return MessageDigest.isEqual(sha256(password).getBytes(StandardCharsets.US_ASCII),
                stored.toLowerCase().getBytes(StandardCharsets.US_ASCII));
    }

    public static boolean needsRehash(String stored) {
        return stored == null || !stored.startsWith("$2");
    }

    /** Rules for new passwords. Returns an error message or null when acceptable. */
    public static String checkStrength(String password, String username) {
        if (password == null || password.length() < 8) {
            return "Password must be at least 8 characters.";
        }
        if (!password.matches(".*[A-Za-z].*") || !password.matches(".*[0-9].*")) {
            return "Password must contain both letters and numbers.";
        }
        if (username != null && password.toLowerCase().contains(username.toLowerCase())) {
            return "Password must not contain the username.";
        }
        String lower = password.toLowerCase();
        for (String weak : new String[]{"admin123", "cashier123", "password", "12345678", "qwerty"}) {
            if (lower.contains(weak)) {
                return "This password is too common. Choose another.";
            }
        }
        return null;
    }

    /** Legacy unsalted SHA-256 (only used to verify old hashes). */
    static String sha256(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
