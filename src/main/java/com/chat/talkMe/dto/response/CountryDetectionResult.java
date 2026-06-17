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
}
