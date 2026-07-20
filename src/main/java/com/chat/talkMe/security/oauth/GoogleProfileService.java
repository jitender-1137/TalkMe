package com.chat.talkMe.security.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.Period;

/**
 * Best-effort fetch of the extra Google profile bits (birthday → age, gender) that
 * the standard OIDC {@code profile} scope does NOT include. Requires the caller to
 * have granted the People-API scopes
 * ({@code .../auth/user.birthday.read}, {@code .../auth/user.gender.read}); without
 * them the People API returns 403 and we simply fall back to nulls — social login
 * never fails because of this.
 */
@Slf4j
@Service
public class GoogleProfileService {

    private static final String PEOPLE_API =
            "https://people.googleapis.com/v1/people/me?personFields=birthdays,genders";

    private final RestClient restClient = RestClient.create();

    /** Age (from birthday) + gender, either of which may be null when unavailable. */
    public record Extended(Integer age, String gender) {}

    public Extended fetch(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return new Extended(null, null);
        }
        try {
            JsonNode body = restClient.get()
                    .uri(PEOPLE_API)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null) {
                return new Extended(null, null);
            }
            return new Extended(parseAge(body.path("birthdays")), parseGender(body.path("genders")));
        } catch (Exception e) {
            // Missing scopes (403), network hiccup, unexpected shape — non-fatal.
            log.debug("Google People API lookup skipped: {}", e.getMessage());
            return new Extended(null, null);
        }
    }

    /** First birthday entry that carries a year → age in whole years. */
    private Integer parseAge(JsonNode birthdays) {
        if (birthdays == null || !birthdays.isArray()) return null;
        for (JsonNode b : birthdays) {
            JsonNode date = b.path("date");
            int year = date.path("year").asInt(0);
            int month = date.path("month").asInt(0);
            int day = date.path("day").asInt(0);
            if (year > 0 && month > 0 && day > 0) {
                try {
                    int age = Period.between(LocalDate.of(year, month, day), LocalDate.now()).getYears();
                    if (age > 0 && age < 120) return age;
                } catch (Exception ignore) {
                    // invalid date parts — skip
                }
            }
        }
        return null;
    }

    /** Normalize Google's gender ("male"/"female"/…) to the app's lowercase value. */
    private String parseGender(JsonNode genders) {
        if (genders == null || !genders.isArray() || genders.isEmpty()) return null;
        String value = genders.get(0).path("value").asText(null);
        if (value == null || value.isBlank()) return null;
        value = value.toLowerCase();
        if (value.equals("male") || value.equals("female")) return value;
        return null; // "unspecified" / "other" → leave unset
    }
}
