package com.chat.talkMe.storage;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Parsing helpers shared by the {@link MediaStorage} implementations. Consolidates
 * the near-identical path resolvers that previously lived in {@code MessageServiceImpl},
 * {@code PostServiceImpl} and {@code PhotoMusicMuxer}.
 *
 * <p>A stored reference is normally {@code <mediaRoot>/<key>}, but the web client may
 * hand back the rewritten form {@code …?path=<url-encoded absolute path>}; both are
 * handled here.
 */
public final class MediaKeys {

    private MediaKeys() {}

    /** The absolute path a reference points at (decoding {@code ?path=} if present), or null. */
    public static String absolutePath(String reference) {
        if (reference == null || reference.isBlank()) return null;
        int idx = reference.indexOf("path=");
        if (idx >= 0) {
            String raw = reference.substring(idx + "path=".length());
            int amp = raw.indexOf('&');
            if (amp >= 0) raw = raw.substring(0, amp);
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        }
        int q = reference.indexOf('?');
        String p = q >= 0 ? reference.substring(0, q) : reference;
        return p.startsWith("/") ? p : null;
    }

    /** The object key (path under {@code mediaRoot}) for a reference, or null if unsafe/unknown. */
    public static String key(String reference, String mediaRoot) {
        String abs = absolutePath(reference);
        if (abs == null) return null;
        String rootPrefix = mediaRoot.endsWith("/") ? mediaRoot : mediaRoot + "/";
        String key = abs.startsWith(rootPrefix)
                ? abs.substring(rootPrefix.length())
                : (abs.startsWith("/") ? abs.substring(1) : abs);
        return isSafeKey(key) ? key : null;
    }

    /** A safe object key is relative and never traverses upward. */
    public static boolean isSafeKey(String key) {
        if (key == null || key.isBlank()) return false;
        return !key.startsWith("/") && !key.contains("..") && !key.contains("\\");
    }

    /** Best-effort MIME guess from a file name/key extension (null if unknown). */
    public static String contentTypeGuess(String keyOrName) {
        if (keyOrName == null) return null;
        int dot = keyOrName.lastIndexOf('.');
        if (dot < 0) return null;
        return switch (keyOrName.substring(dot + 1).toLowerCase()) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "avif" -> "image/avif";
            case "bmp" -> "image/bmp";
            case "svg" -> "image/svg+xml";
            case "heic", "heif" -> "image/heic";
            case "mp4", "m4v" -> "video/mp4";
            case "webm" -> "video/webm";
            case "mov" -> "video/quicktime";
            case "ogv" -> "video/ogg";
            case "mp3" -> "audio/mpeg";
            case "m4a" -> "audio/mp4";
            case "aac" -> "audio/aac";
            case "ogg", "opus" -> "audio/ogg";
            case "wav" -> "audio/wav";
            case "pdf" -> "application/pdf";
            default -> null;
        };
    }
}
