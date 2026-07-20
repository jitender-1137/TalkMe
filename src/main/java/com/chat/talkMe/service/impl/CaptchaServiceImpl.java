package com.chat.talkMe.service.impl;

import com.chat.talkMe.config.CaptchaProperties;
import com.chat.talkMe.service.CaptchaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class CaptchaServiceImpl implements CaptchaService {

    private static final String VERIFY_URL =
            "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    private final CaptchaProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public boolean verify(String token, String remoteIp) {
        if (!properties.isEnabled()) return true;
        if (token == null || token.isBlank()) return false;

        try {
            StringBuilder form = new StringBuilder()
                    .append("secret=").append(enc(properties.getSecretKey()))
                    .append("&response=").append(enc(token));
            if (remoteIp != null && !remoteIp.isBlank()) {
                form.append("&remoteip=").append(enc(remoteIp));
            }

            HttpRequest request = HttpRequest.newBuilder(URI.create(VERIFY_URL))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(8))
                    .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode node = objectMapper.readTree(response.body());
            boolean success = node.path("success").asBoolean(false);
            if (!success) {
                log.warn("[Captcha] Verification failed: {}", node.path("error-codes"));
            }
            return success;
        } catch (Exception e) {
            // Fail closed — a verification error should not let a bot through.
            log.error("[Captcha] Verification error", e);
            return false;
        }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }
}
