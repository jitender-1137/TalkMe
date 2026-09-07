package com.neo.chat.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Keeps human-identifiable usernames out of production logs.
 *
 * <p>Preferred approach at a log site is to log the user's pseudonymous {@code uuid}
 * ({@code user.getUuid()}) when a {@code User} entity is in scope. Where only a raw
 * username {@code String} is available (e.g. resolved from a STOMP principal or a Redis
 * key, with no {@code User} to hand), wrap it in {@link #mask(String)} for WARN/ERROR
 * logs that must stay visible in prod — it yields a stable, non-reversible token so the
 * same user's repeated failures still correlate, without exposing the handle.
 */
public final class LogSanitizer {

    private LogSanitizer() {
    }

    /**
     * Returns a stable, non-reversible token for {@code username} — {@code "u#" + } the first
     * 8 hex chars of its SHA-256. Same input always maps to the same token (so log lines about
     * one user correlate), but the original handle cannot be recovered. Null/blank → {@code "u#anon"}.
     *
     * @param username the raw username/handle, possibly null/blank
     * @return a non-identifying, correlatable token safe for production logs
     */
    public static String mask(String username) {
        if (username == null || username.isBlank()) {
            return "u#anon";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(username.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("u#");
            for (int i = 0; i < 4; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available on a standard JRE; fall back to a length tag.
            return "u#len" + username.length();
        }
    }
}
