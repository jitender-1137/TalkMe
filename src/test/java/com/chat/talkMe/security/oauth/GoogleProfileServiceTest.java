package com.chat.talkMe.security.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link GoogleProfileService} — the best-effort People-API lookup that
 * enriches a social login with age (from birthday) and normalized gender. The private
 * {@code parseAge}/{@code parseGender} helpers hold the real logic and are exercised via
 * reflection; the public {@code fetch} contract (null/blank token guards, null body, and
 * the happy-path mapping through a stubbed {@link RestClient}) is covered directly.
 */
@DisplayName("GoogleProfileService (unit)")
class GoogleProfileServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private GoogleProfileService service;

    @BeforeEach
    void setUp() {
        service = new GoogleProfileService();
    }

    private JsonNode json(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    @DisplayName("fetch")
    class Fetch {

        @Test
        @DisplayName("null token → Extended(null, null) without any HTTP call")
        void nullToken() {
            GoogleProfileService.Extended ext = service.fetch(null);
            assertThat(ext.age()).isNull();
            assertThat(ext.gender()).isNull();
        }

        @Test
        @DisplayName("blank token → Extended(null, null)")
        void blankToken() {
            GoogleProfileService.Extended ext = service.fetch("   ");
            assertThat(ext.age()).isNull();
            assertThat(ext.gender()).isNull();
        }

        // Explicit RestClient fluent chain — deep stubs mishandle the varargs .header(String, String...).
        @SuppressWarnings({"rawtypes", "unchecked"})
        private RestClient.ResponseSpec stubRestClientChain() {
            RestClient rc = mock(RestClient.class);
            RestClient.RequestHeadersUriSpec uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
            RestClient.RequestHeadersSpec headersSpec = mock(RestClient.RequestHeadersSpec.class);
            RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
            when(rc.get()).thenReturn(uriSpec);
            when(uriSpec.uri(anyString())).thenReturn(headersSpec);
            when(headersSpec.header(anyString(), any())).thenReturn(headersSpec);
            when(headersSpec.retrieve()).thenReturn(responseSpec);
            ReflectionTestUtils.setField(service, "restClient", rc);
            return responseSpec;
        }

        @Test
        @DisplayName("null response body → Extended(null, null)")
        void nullBody() {
            when(stubRestClientChain().body(JsonNode.class)).thenReturn(null);

            GoogleProfileService.Extended ext = service.fetch("token");

            assertThat(ext.age()).isNull();
            assertThat(ext.gender()).isNull();
        }

        @Test
        @DisplayName("full body → maps age from birthday and normalized gender")
        void happyPath() {
            int year = LocalDate.now().minusYears(30).getYear();
            String body = "{\"birthdays\":[{\"date\":{\"year\":" + year + ",\"month\":6,\"day\":15}}],"
                    + "\"genders\":[{\"value\":\"female\"}]}";
            when(stubRestClientChain().body(JsonNode.class)).thenReturn(json(body));

            GoogleProfileService.Extended ext = service.fetch("token");

            assertThat(ext.age()).isEqualTo(30);
            assertThat(ext.gender()).isEqualTo("female");
        }

        @Test
        @DisplayName("RestClient throws (missing scopes / network) → non-fatal Extended(null, null)")
        void downstreamFailureNonFatal() {
            when(stubRestClientChain().body(JsonNode.class))
                    .thenThrow(new RuntimeException("403 Forbidden"));

            GoogleProfileService.Extended ext = service.fetch("token");

            assertThat(ext.age()).isNull();
            assertThat(ext.gender()).isNull();
        }
    }

    @Nested
    @DisplayName("parseAge")
    class ParseAge {

        private Integer parseAge(JsonNode node) {
            return (Integer) ReflectionTestUtils.invokeMethod(service, "parseAge", node);
        }

        @Test
        @DisplayName("complete birthday with year → age in whole years")
        void validBirthday() {
            int year = LocalDate.now().minusYears(25).getYear();
            JsonNode node = json("[{\"date\":{\"year\":" + year + ",\"month\":1,\"day\":1}}]");
            assertThat(parseAge(node)).isEqualTo(25);
        }

        @Test
        @DisplayName("skips year-less entries and uses the first dated one")
        void skipsYearlessEntries() {
            int year = LocalDate.now().minusYears(40).getYear();
            JsonNode node = json("[{\"date\":{\"month\":5,\"day\":9}},"
                    + "{\"date\":{\"year\":" + year + ",\"month\":5,\"day\":9}}]");
            assertThat(parseAge(node)).isEqualTo(40);
        }

        @Test
        @DisplayName("no year present anywhere → null")
        void noYear() {
            JsonNode node = json("[{\"date\":{\"month\":5,\"day\":9}}]");
            assertThat(parseAge(node)).isNull();
        }

        @Test
        @DisplayName("empty array → null")
        void emptyArray() {
            assertThat(parseAge(json("[]"))).isNull();
        }

        @Test
        @DisplayName("not an array (missing path node) → null")
        void notArray() {
            JsonNode node = json("{}").path("birthdays"); // MissingNode
            assertThat(parseAge(node)).isNull();
        }

        @Test
        @DisplayName("implausible age (>=120) → null")
        void ageOutOfRange() {
            JsonNode node = json("[{\"date\":{\"year\":1800,\"month\":1,\"day\":1}}]");
            assertThat(parseAge(node)).isNull();
        }

        @Test
        @DisplayName("invalid date parts (month 13) → skipped → null")
        void invalidDate() {
            int year = LocalDate.now().minusYears(20).getYear();
            JsonNode node = json("[{\"date\":{\"year\":" + year + ",\"month\":13,\"day\":40}}]");
            assertThat(parseAge(node)).isNull();
        }
    }

    @Nested
    @DisplayName("parseGender")
    class ParseGender {

        private String parseGender(JsonNode node) {
            return (String) ReflectionTestUtils.invokeMethod(service, "parseGender", node);
        }

        @Test
        @DisplayName("\"male\" → male")
        void male() {
            assertThat(parseGender(json("[{\"value\":\"male\"}]"))).isEqualTo("male");
        }

        @Test
        @DisplayName("mixed case is lowercased → female")
        void femaleMixedCase() {
            assertThat(parseGender(json("[{\"value\":\"FeMale\"}]"))).isEqualTo("female");
        }

        @Test
        @DisplayName("\"unspecified\"/other → null (leave unset)")
        void unspecified() {
            assertThat(parseGender(json("[{\"value\":\"unspecified\"}]"))).isNull();
            assertThat(parseGender(json("[{\"value\":\"other\"}]"))).isNull();
        }

        @Test
        @DisplayName("blank value → null")
        void blankValue() {
            assertThat(parseGender(json("[{\"value\":\"\"}]"))).isNull();
        }

        @Test
        @DisplayName("missing value field → null")
        void missingValue() {
            assertThat(parseGender(json("[{}]"))).isNull();
        }

        @Test
        @DisplayName("empty array → null")
        void emptyArray() {
            assertThat(parseGender(json("[]"))).isNull();
        }

        @Test
        @DisplayName("not an array (missing node) → null")
        void notArray() {
            assertThat(parseGender(json("{}").path("genders"))).isNull();
        }
    }

    @Nested
    @DisplayName("Extended record")
    class ExtendedRecord {

        @Test
        @DisplayName("holds the age and gender it was built with")
        void holdsValues() {
            GoogleProfileService.Extended ext = new GoogleProfileService.Extended(33, "male");
            assertThat(ext.age()).isEqualTo(33);
            assertThat(ext.gender()).isEqualTo("male");
        }
    }
}
