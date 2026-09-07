package com.neo.chat.storage;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

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

    private MediaKeys() {
    }

    /**
     * The absolute path a reference points at (decoding {@code ?path=} if present), or null.
     */
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

    /**
     * Top-level media categories (see {@code UploadController.resolveSubdivide}). Used as a
     * last-resort net to recover the key from a reference written under an <em>unconfigured</em>
     * previous root — object keys always begin {@code <category>/…} (except a few legacy flat
     * files, which the configured legacy-root strip handles).
     */
    private static final Set<String> CATEGORIES = Set.of(
            "conversations", "profiles", "posts", "stories", "lobby", "strangers", "others");

    /**
     * The object key (path under {@code mediaRoot}) for a reference, or null if unsafe/unknown.
     */
    public static String key(String reference, String mediaRoot) {
        return key(reference, mediaRoot, List.of());
    }

    /**
     * The object key for a reference, tolerant of a renamed media-root. Object keys never contain
     * the root (see {@code MediaStorage.store}), so a reference written under a PREVIOUS root points
     * at the same object once that root is stripped. Resolution order: strip the current root; else
     * strip the first matching {@code legacyRoots} entry; else recover from a known category segment;
     * else fall back to the bare path (leading slash removed).
     *
     * @param reference   the stored reference (raw {@code <root>/<key>} or a {@code ?path=} URL)
     * @param mediaRoot   the current media-root
     * @param legacyRoots previously-used media-roots to also strip (may be empty/null)
     * @return the safe object key, or null if unsafe/unknown
     */
    public static String key(String reference, String mediaRoot, List<String> legacyRoots) {
        String abs = absolutePath(reference);
        if (abs == null) return null;

        String key = stripRoot(abs, mediaRoot);
        if (key == null && legacyRoots != null) {
            for (String legacy : legacyRoots) {
                key = stripRoot(abs, legacy);
                if (key != null) break;
            }
        }
        if (key == null) key = keyFromCategory(abs);
        if (key == null) key = abs.startsWith("/") ? abs.substring(1) : abs;

        return isSafeKey(key) ? key : null;
    }

    /** The path under {@code root} (root prefix removed), or null if {@code abs} isn't under it. */
    private static String stripRoot(String abs, String root) {
        if (root == null || root.isBlank()) return null;
        String rootPrefix = root.endsWith("/") ? root : root + "/";
        return abs.startsWith(rootPrefix) ? abs.substring(rootPrefix.length()) : null;
    }

    /**
     * Recover a key from the first known {@code <category>/} segment in {@code abs}, so a
     * category-foldered reference under an unrecognised old root still resolves. Null when no
     * category segment is present.
     */
    private static String keyFromCategory(String abs) {
        int best = -1;
        for (String cat : CATEGORIES) {
            int i = abs.indexOf("/" + cat + "/");
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        return best >= 0 ? abs.substring(best + 1) : null;
    }

    /**
     * A safe object key is relative and never traverses upward.
     */
    public static boolean isSafeKey(String key) {
        if (key == null || key.isBlank()) return false;
        return !key.startsWith("/") && !key.contains("..") && !key.contains("\\");
    }

    /**
     * Best-effort MIME guess from a file name/key extension (null if unknown).
     */
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
