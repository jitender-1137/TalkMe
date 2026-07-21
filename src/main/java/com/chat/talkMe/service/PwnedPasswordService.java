package com.chat.talkMe.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Checks a candidate password against Have I Been Pwned's Pwned Passwords range
 * API using k-anonymity: only the first 5 chars of the password's SHA-1 hash are
 * sent, and the full password never leaves the server. Used to reject known
 * breached passwords at signup / reset / change.
 *
 * <p>Fail-OPEN: if HIBP is unreachable or slow, we do NOT block the user — the
 * password policy (length/complexity) still applies.
 */
@Slf4j
@Service
public class PwnedPasswordService {

    private static final String RANGE_API = "https://api.pwnedpasswords.com/range/";

    @Value("${app.auth.breach-check.enabled:true}")
    private boolean enabled;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** @return true only if the password is known-breached (so the caller should reject it). */
    public boolean isBreached(String password) {
        if (!enabled || password == null || password.isEmpty()) {
            return false;
        }
        try {
            String sha1 = sha1Hex(password).toUpperCase();
            String prefix = sha1.substring(0, 5);
            String suffix = sha1.substring(5);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(RANGE_API + prefix))
                    .timeout(Duration.ofSeconds(4))
                    .header("Add-Padding", "true") // response padding hides the real match count
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return false; // fail-open
            }
            for (String line : response.body().split("\\r?\\n")) {
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String candidate = line.substring(0, colon).trim();
                if (candidate.equalsIgnoreCase(suffix)) {
                    // A non-zero count means it appeared in a breach (padding rows are count 0).
                    String count = line.substring(colon + 1).trim();
                    return !"0".equals(count);
                }
            }
            return false;
        } catch (Exception e) {
            log.debug("Breach check failed (fail-open): {}", e.getMessage());
            return false;
        }
    }

    private static String sha1Hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
    }
}
