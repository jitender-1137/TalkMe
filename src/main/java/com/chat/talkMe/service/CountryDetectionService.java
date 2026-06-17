package com.chat.talkMe.service;

import com.chat.talkMe.dto.response.CountryDetectionResult;
import jakarta.servlet.http.HttpServletRequest;

public interface CountryDetectionService {
    CountryDetectionResult detectCountry(HttpServletRequest request);
}
