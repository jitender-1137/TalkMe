package com.chat.talkMe.service;

import com.chat.talkMe.dto.response.CountryDetectionResult;
import com.chat.talkMe.service.impl.CountryDetectionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class CountryDetectionServiceTest {

    @InjectMocks
    private CountryDetectionServiceImpl countryDetectionService;

    @Mock
    private RestTemplate mockRestTemplate;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        ReflectionTestUtils.setField(countryDetectionService, "restTemplate", mockRestTemplate);
        ReflectionTestUtils.setField(countryDetectionService, "proxyCountryHeader", "X-Country-Code");
    }

    @Test
    void testDetectCountry_withCloudflareHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("CF-IPCountry", "IN");
        request.setRemoteAddr("103.21.244.5"); // Cloudflare range public IP

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("India", result.getCountry());
        assertEquals("Cloudflare Header", result.getSource());
    }

    @Test
    void testDetectCountry_withProxyHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Country-Code", "GB");
        request.setRemoteAddr("8.8.8.8");

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("United Kingdom", result.getCountry());
        assertEquals("Proxy Header", result.getSource());
    }

    @Test
    void testDetectCountry_withLocalIpBypass() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("Unknown", result.getCountry());
        assertEquals("Unknown", result.getSource());
        assertEquals("127.0.0.1", result.getClientIp());
    }

    @Test
    void testDetectCountry_withGeoIpSuccess() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("8.8.8.8");

        Map<String, Object> geoIpResponse = new HashMap<>();
        geoIpResponse.put("status", "success");
        geoIpResponse.put("country", "United States");
        geoIpResponse.put("countryCode", "US");

        when(mockRestTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(geoIpResponse);

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("United States", result.getCountry());
        assertEquals("GeoIP", result.getSource());
        assertEquals("8.8.8.8", result.getClientIp());
    }

    @Test
    void testDetectCountry_withGeoIpFailure() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("8.8.8.8");

        when(mockRestTemplate.getForObject(anyString(), eq(Map.class)))
                .thenThrow(new RuntimeException("API error or timeout"));

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("Unknown", result.getCountry());
        assertEquals("Unknown", result.getSource());
        assertEquals("8.8.8.8", result.getClientIp());
    }
}
