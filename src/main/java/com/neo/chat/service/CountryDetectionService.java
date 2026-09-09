package com.neo.chat.service;

import com.neo.chat.dto.response.CountryDetectionResult;
import com.neo.chat.util.ClientRequestInfo;

/**
 * Resolves a request's country/location from proxy headers or IP geolocation.
 */
public interface CountryDetectionService {
    /**
     * Resolves the country/location for a request snapshot (proxy headers first, then GeoIP).
     *
     * @param request the request snapshot built in the web layer (maybe null)
     * @return the detected country/location and its source; never null
     */
    CountryDetectionResult detectCountry(ClientRequestInfo request);
}
