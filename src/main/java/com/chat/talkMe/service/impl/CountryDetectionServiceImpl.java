package com.chat.talkMe.service.impl;

import com.chat.talkMe.dto.response.CountryDetectionResult;
import com.chat.talkMe.service.CountryDetectionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
public class CountryDetectionServiceImpl implements CountryDetectionService {

    @Value("${app.country-header:X-Country-Code}")
    private String proxyCountryHeader;

    // Dev convenience: when the client IP is local/private (e.g. on localhost),
    // geolocate the SERVER's own public IP instead of bailing out to "Unknown",
    // so country detection works during local development. MUST stay false in
    // production — there a private IP means a misconfigured proxy, not the dev's
    // machine, and we don't want to attribute the server's location to a user.
    @Value("${app.geo.geolocate-local-ip:false}")
    private boolean geolocateLocalIp;

    private final RestTemplate restTemplate;

    public CountryDetectionServiceImpl() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(1000);
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public CountryDetectionResult detectCountry(HttpServletRequest request) {
        if (request == null) {
            return CountryDetectionResult.builder()
                    .country("Unknown")
                    .source("Unknown")
                    .clientIp("unknown")
                    .build();
        }

        String clientIp = resolveIp(request);

        // 1. Cloudflare Country Header
        String cfCountryCode = request.getHeader("CF-IPCountry");
        if (cfCountryCode != null && !cfCountryCode.isBlank() && !cfCountryCode.equalsIgnoreCase("XX")) {
            String countryName = getCountryNameFromCode(cfCountryCode.trim());
            log.debug("Country detected via Cloudflare header: {} -> {}", cfCountryCode, countryName);
            return CountryDetectionResult.builder()
                    .country(countryName)
                    .source("Cloudflare Header")
                    .clientIp(clientIp)
                    .build();
        }

        // 2. Reverse Proxy Country Header (e.g. X-Country-Code)
        String proxyCountryCode = request.getHeader(proxyCountryHeader);
        if (proxyCountryCode == null || proxyCountryCode.isBlank()) {
            // Check common fallbacks if custom configured is missing
            proxyCountryCode = request.getHeader("X-Country-Code");
            if (proxyCountryCode == null || proxyCountryCode.isBlank()) {
                proxyCountryCode = request.getHeader("X-Country");
            }
        }
        if (proxyCountryCode != null && !proxyCountryCode.isBlank()) {
            String countryName = getCountryNameFromCode(proxyCountryCode.trim());
            log.debug("Country detected via Proxy header: {} -> {}", proxyCountryCode, countryName);
            return CountryDetectionResult.builder()
                    .country(countryName)
                    .source("Proxy Header")
                    .clientIp(clientIp)
                    .build();
        }

        // 3. IP Geolocation Lookup
        boolean localIp = isLocalOrPrivateIp(clientIp);
        if (localIp && !geolocateLocalIp) {
            log.debug("Bypassing GeoIP lookup for local/private IP: {}", clientIp);
            return CountryDetectionResult.builder()
                    .country("Unknown")
                    .source("Unknown")
                    .clientIp(clientIp)
                    .build();
        }

        try {
            // For a local IP in dev mode, omit the IP so ip-api geolocates the
            // requester (this server's public IP) — the developer's location.
            String lookupIp = localIp ? "" : clientIp;
            String url = "http://ip-api.com/json/" + lookupIp;
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            if (response != null && "success".equals(response.get("status"))) {
                String country = (String) response.get("country");
                if (country != null && !country.isBlank()) {
                    log.debug("Country detected via GeoIP lookup for IP {}: {}", clientIp, country);
                    return CountryDetectionResult.builder()
                            .country(country)
                            .source("GeoIP")
                            .clientIp(clientIp)
                            .build();
                }
            }
        } catch (Exception e) {
            log.warn("GeoIP lookup failed or timed out for IP: {}. Error: {}", clientIp, e.getMessage());
        }

        return CountryDetectionResult.builder()
                .country("Unknown")
                .source("Unknown")
                .clientIp(clientIp)
                .build();
    }

    private String resolveIp(HttpServletRequest request) {
        // Correctly resolve client IP when behind Cloudflare, Nginx, Load balancers, Proxies
        String ip = request.getHeader("CF-Connecting-IP");
        if (ip != null && !ip.isBlank()) {
            return ip.trim();
        }

        ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank()) {
            return ip.split(",")[0].trim();
        }

        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isBlank()) {
            return ip.trim();
        }

        return request.getRemoteAddr();
    }

    private String getCountryNameFromCode(String countryCode) {
        try {
            Locale locale = new Locale("", countryCode);
            String country = locale.getDisplayCountry(Locale.ENGLISH);
            if (country != null && !country.isBlank() && !country.equalsIgnoreCase(countryCode)) {
                return country;
            }
        } catch (Exception e) {
            // Ignore locale lookup failure and fallback
        }
        return countryCode;
    }

    private boolean isLocalOrPrivateIp(String ip) {
        if (ip == null || ip.equals("127.0.0.1") || ip.equals("0:0:0:0:0:0:0:1") || ip.equalsIgnoreCase("localhost") || ip.equalsIgnoreCase("::1")) {
            return true;
        }
        // Private IPv4 ranges: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16
        if (ip.startsWith("10.") || ip.startsWith("192.168.")) {
            return true;
        }
        if (ip.startsWith("172.")) {
            try {
                String[] parts = ip.split("\\.");
                if (parts.length >= 2) {
                    int secondOctet = Integer.parseInt(parts[1]);
                    return secondOctet >= 16 && secondOctet <= 31;
                }
            } catch (Exception e) {
                // Ignore
            }
        }
        return false;
    }
}
