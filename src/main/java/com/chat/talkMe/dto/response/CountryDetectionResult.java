package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CountryDetectionResult {
    private String country;
    private String source; // "Cloudflare Header", "Proxy Header", "GeoIP", "Unknown"
    private String clientIp;

    // Finer-grained location (best-effort; only the GeoIP branch fills these — header-
    // based detection gives country only). Used for the "closest location / area" of a
    // user's activity: stored on the session + user and shown on the admin dashboard,
    // and included in the new-sign-in security email.
    private String city;
    private String region; // state / province
    private String countryCode;
    private Double lat;
    private Double lon;

    /**
     * Human-readable "City, Region, Country" (drops the blanks), e.g. "Pune, Maharashtra,
     * India". Returns null when nothing usable is known (so callers can omit it cleanly).
     */
    public String getDisplayLocation() {
        StringBuilder sb = new StringBuilder();
        appendPart(sb, city);
        appendPart(sb, region);
        if (country != null && !country.isBlank() && !"Unknown".equalsIgnoreCase(country)) {
            appendPart(sb, country);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void appendPart(StringBuilder sb, String part) {
        if (part == null || part.isBlank()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(", ");
        }
        sb.append(part.trim());
    }
}
