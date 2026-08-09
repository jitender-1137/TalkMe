package com.chat.talkMe.service;

import com.chat.talkMe.dto.response.CountryDetectionResult;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves a request's country/location from proxy headers or IP geolocation.
 */
public interface CountryDetectionService {
    CountryDetectionResult detectCountry(HttpServletRequest request);
}
