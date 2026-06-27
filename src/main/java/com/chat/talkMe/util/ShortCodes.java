package com.chat.talkMe.util;

import java.security.SecureRandom;
import java.util.function.Predicate;

/**
 * Generates short, opaque, URL-safe codes for shareable post links
 * (Instagram-style, e.g. /post/Cx3aB7kQ2p). Codes are random base62 so they're
 * non-sequential and not enumerable.
 */
public final class ShortCodes {

    private static final String ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int DEFAULT_LENGTH = 10;

    private ShortCodes() {}

    /** A single random base62 code of the given length. */
    public static String random(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /**
     * A random code that satisfies {@code isUnique} (i.e. not already taken).
     * Retries a bounded number of times before failing.
     */
    public static String unique(Predicate<String> isUnique) {
        for (int attempt = 0; attempt < 12; attempt++) {
            String code = random(DEFAULT_LENGTH);
            if (isUnique.test(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Unable to generate a unique short code");
    }
}
