package com.neo.chat.service.impl;

import com.neo.chat.dto.response.CountryDetectionResult;
import com.neo.chat.service.CountryDetectionService;
import com.neo.chat.util.ClientRequestInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Locale;
import java.util.Map;

/**
 * Detects a request's country/location, preferring proxy-provided headers (Cloudflare {@code CF-IPCountry},
 * then a configurable proxy header) and falling back to an ip-api.com GeoIP lookup (1s connect/read timeout).
 * <p>
 * Private/local client IPs are normally reported as "Unknown"; in dev ({@code app.geo.geolocate-local-ip})
 * the lookup instead geolocates the server's own public IP so detection works on localhost.
 */
@Slf4j
@Service
public class CountryDetectionServiceImpl implements CountryDetectionService {

    private final String proxyCountryHeader;

    // Dev convenience: when the client IP is local/private (e.g. on localhost),
    // geolocate the SERVER's own public IP instead of bailing out to "Unknown",
    // so country detection works during local development. MUST stay false in
    // production — there a private IP means a misconfigured proxy, not the dev's
    // machine, and we don't want to attribute the server's location to a user.
    private final boolean geolocateLocalIp;

    private final RestTemplate restTemplate;

    public CountryDetectionServiceImpl(@Value("${app.country-header:X-Country-Code}") String proxyCountryHeader,
                                       @Value("${app.geo.geolocate-local-ip:false}") boolean geolocateLocalIp) {
        this.proxyCountryHeader = proxyCountryHeader;
        this.geolocateLocalIp = geolocateLocalIp;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(1000);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * Resolves the request's location by trying, in order: the Cloudflare country header, a proxy country
     * header (configured or common fallbacks), then an ip-api.com GeoIP lookup on the resolved client IP.
     * Returns an "Unknown" result when nothing resolves, on error/timeout, or for local IPs outside dev mode.
     *
     * @param request the request snapshot built in the web layer (maybe null)
     * @return the detected country/location and its source; never null
     */
    @Override
    public CountryDetectionResult detectCountry(ClientRequestInfo request) {
        if (request == null) {
            return CountryDetectionResult.builder()
                    .country("Unknown")
                    .source("Unknown")
                    .clientIp("unknown")
                    .build();
        }

        String clientIp = request.clientIp();

        // 1. Cloudflare Country Header
        String cfCountryCode = request.header("CF-IPCountry");
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
        String proxyCountryCode = request.header(proxyCountryHeader);
        if (proxyCountryCode == null || proxyCountryCode.isBlank()) {
            // Check common fallbacks if custom configured is missing
            proxyCountryCode = request.header("X-Country-Code");
            if (proxyCountryCode == null || proxyCountryCode.isBlank()) {
                proxyCountryCode = request.header("X-Country");
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
            // Request the finer-grained fields too (city/region/lat/lon) so callers can
            // record the "closest location / area", not just the country.
            String url = "http://ip-api.com/json/" + lookupIp
                    + "?fields=status,country,countryCode,regionName,city,lat,lon,timezone";
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            if (response != null && "success".equals(response.get("status"))) {
                String country = (String) response.get("country");
                if (country != null && !country.isBlank()) {
                    log.debug("Location detected via GeoIP for IP {}: {}, {}, {}",
                            clientIp, response.get("city"), response.get("regionName"), country);
                    return CountryDetectionResult.builder()
                            .country(country)
                            .source("GeoIP")
                            .clientIp(clientIp)
                            .city((String) response.get("city"))
                            .region((String) response.get("regionName"))
                            .countryCode((String) response.get("countryCode"))
                            .lat(asDouble(response.get("lat")))
                            .lon(asDouble(response.get("lon")))
                            .timezone((String) response.get("timezone"))
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

    /**
     * ip-api returns lat/lon as JSON numbers, which Jackson may map to Double or Integer.
     */
    private static Double asDouble(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        return null;
    }

    /**
     * Converts an ISO country code to its English display name, returning the code itself when it can't
     * be resolved to a distinct name.
     *
     * @param countryCode the ISO-3166 country code
     * @return the English country name, or the code on failure
     */
    private String getCountryNameFromCode(String countryCode) {
        try {
            Locale locale = Locale.of("", countryCode);
            String country = locale.getDisplayCountry(Locale.ENGLISH);
            if (!country.isBlank() && !country.equalsIgnoreCase(countryCode)) {
                return country;
            }
        } catch (Exception e) {
            // Ignore locale lookup failure and fallback
        }
        return countryCode;
    }

    /**
     * True when the IP is loopback/localhost or in a private IPv4 range (10/8, 172.16/12, 192.168/16).
     *
     * @param ip the client IP
     * @return whether the IP is local or private
     */
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
