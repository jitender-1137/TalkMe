package com.neo.chat.util;

import java.util.regex.Pattern;

/**
 * Guards for user-supplied media references (a story's or post's {@code mediaUrl}).
 *
 * <p>SECURITY: these fields are free strings on the request DTO. If an EXTERNAL URL is accepted
 * and then rendered as the media {@code src} for every viewer, the attacker learns each viewer's
 * IP/User-Agent, can swap the bytes to explicit content AFTER publication (bypassing moderation),
 * and can embed tracking. Legitimate references are always the value returned by the upload
 * endpoint: an absolute path under the media root ({@code /opt/media/...}, {@code /media/...}) or a
 * bare object key ({@code stories/<uuid>/...}) — never a {@code scheme://} URL. This guard rejects
 * anything carrying a URL scheme (http, https, ftp, data, blob, …).
 */
public final class MediaReferences {

    // A leading "<scheme>://" or "data:"/"javascript:" style scheme marks an external/absolute URL.
    private static final Pattern EXTERNAL_SCHEME =
            Pattern.compile("^\\s*[a-zA-Z][a-zA-Z0-9+.-]*:", Pattern.CASE_INSENSITIVE);

    private MediaReferences() {
    }

    /**
     * Whether {@code ref} is an external URL (carries a scheme) rather than an internal storage
     * reference. A {@code null}/blank value is treated as not-external (callers gate on presence).
     *
     * @param ref the candidate media reference
     * @return {@code true} if the value carries a URL scheme
     */
    public static boolean isExternalUrl(String ref) {
        if (ref == null || ref.isBlank()) return false;
        return EXTERNAL_SCHEME.matcher(ref).find();
    }
}
