package com.neo.chat.service;

import com.neo.chat.dto.response.CountryDetectionResult;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves a request's country/location from proxy headers or IP geolocation.
 */
public interface CountryDetectionService {
    CountryDetectionResult detectCountry(HttpServletRequest request);
}
