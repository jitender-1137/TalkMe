package com.chat.talkMe.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link PwnedPasswordService} — the HIBP Pwned Passwords
 * range API check with k-anonymity.
 *
 * <p>The internal {@link HttpClient} is a private final field built inline; the test swaps a
 * mock over it via {@link ReflectionTestUtils} so the range call is driven deterministically.
 * Key invariants: only the first 5 chars of the SHA-1 hash are sent (k-anonymity), the
 * suffix match is case-insensitive, a count of {@code 0} is a padding row (not a breach), and
 * every failure mode (disabled flag, non-200, transport error) fails <b>OPEN</b> — a broken
 * check never blocks the user.</p>
 *
 * <p>The SHA-1 of {@code "password"} is
 * {@code 5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8} → prefix {@code 5BAA6},
 * suffix {@code 1E4C9B93F3F0682250B6CF8331B7EE68FD8}.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PwnedPasswordService (unit)")
class PwnedPasswordServiceTest {

    private static final String PWD = "password";
    private static final String PREFIX = "5BAA6";
    private static final String SUFFIX = "1E4C9B93F3F0682250B6CF8331B7EE68FD8";

    @Mock private HttpClient http;

    private PwnedPasswordService service;

    @BeforeEach
    void setUp() {
        service = new PwnedPasswordService();
        ReflectionTestUtils.setField(service, "enabled", true);
        // Swap the inline-constructed HttpClient for the mock (non-static final instance field).
        ReflectionTestUtils.setField(service, "http", http);
    }

    @SuppressWarnings("unchecked")
    private void stubResponse(int status, String body) throws Exception {
        HttpResponse<String> resp = mock(HttpResponse.class);
        lenient().when(resp.statusCode()).thenReturn(status);
        lenient().when(resp.body()).thenReturn(body);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);
    }

    @Nested
    @DisplayName("short-circuits (no network)")
    class ShortCircuits {

        @Test
        @DisplayName("disabled flag → not breached, no network call")
        void disabledSkips() throws Exception {
            ReflectionTestUtils.setField(service, "enabled", false);

            assertThat(service.isBreached(PWD)).isFalse();
            verify(http, never()).send(any(), any());
        }

        @Test
        @DisplayName("null password → not breached, no network call")
        void nullPassword() throws Exception {
            assertThat(service.isBreached(null)).isFalse();
            verify(http, never()).send(any(), any());
        }

        @Test
        @DisplayName("empty password → not breached, no network call")
        void emptyPassword() throws Exception {
            assertThat(service.isBreached("")).isFalse();
            verify(http, never()).send(any(), any());
        }
    }

    @Nested
    @DisplayName("range lookup")
    class RangeLookup {

        @Test
        @DisplayName("suffix present with non-zero count → breached")
        void breachedWhenSuffixWithCount() throws Exception {
            stubResponse(200, SUFFIX + ":9999999");

            assertThat(service.isBreached(PWD)).isTrue();
        }

        @Test
        @DisplayName("only the 5-char prefix is sent, with the Add-Padding header (k-anonymity)")
        void sendsPrefixOnlyWithPadding() throws Exception {
            stubResponse(200, SUFFIX + ":42");

            service.isBreached(PWD);

            ArgumentCaptor<HttpRequest> req = ArgumentCaptor.forClass(HttpRequest.class);
            verify(http).send(req.capture(), any());
            assertThat(req.getValue().uri().toString())
                    .isEqualTo("https://api.pwnedpasswords.com/range/" + PREFIX);
            assertThat(req.getValue().uri().toString()).doesNotContain(SUFFIX);
            assertThat(req.getValue().headers().firstValue("Add-Padding")).contains("true");
            assertThat(req.getValue().method()).isEqualTo("GET");
        }

        @Test
        @DisplayName("padding row (count 0) → NOT breached even though the suffix matches")
        void paddingRowNotBreached() throws Exception {
            stubResponse(200, SUFFIX + ":0");

            assertThat(service.isBreached(PWD)).isFalse();
        }

        @Test
        @DisplayName("suffix match is case-insensitive (lowercased response) → breached")
        void caseInsensitiveSuffix() throws Exception {
            stubResponse(200, SUFFIX.toLowerCase() + ":3");

            assertThat(service.isBreached(PWD)).isTrue();
        }

        @Test
        @DisplayName("suffix absent from the range → not breached")
        void notBreachedWhenAbsent() throws Exception {
            stubResponse(200, "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF:5\r\nAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA:2");

            assertThat(service.isBreached(PWD)).isFalse();
        }

        @Test
        @DisplayName("target suffix found among many lines (CRLF + malformed lines skipped)")
        void matchAmongManyLines() throws Exception {
            String body = "\r\n"                                   // blank line
                    + "notacolonline\r\n"                          // no colon → skipped
                    + ":123\r\n"                                   // colon at 0 → skipped
                    + "0000000000000000000000000000000000A:7\r\n"  // non-matching suffix
                    + SUFFIX + ":11";                              // the match
            stubResponse(200, body);

            assertThat(service.isBreached(PWD)).isTrue();
        }
    }

    @Nested
    @DisplayName("fail-open")
    class FailOpen {

        @Test
        @DisplayName("non-200 status → not breached (fail-open)")
        void non200FailsOpen() throws Exception {
            stubResponse(500, "server error");

            assertThat(service.isBreached(PWD)).isFalse();
        }

        @Test
        @DisplayName("429 rate-limited → not breached (fail-open)")
        void rateLimitedFailsOpen() throws Exception {
            stubResponse(429, "");

            assertThat(service.isBreached(PWD)).isFalse();
        }

        @Test
        @DisplayName("transport error (IOException) → not breached (fail-open)")
        void transportErrorFailsOpen() throws Exception {
            when(http.send(any(HttpRequest.class), any())).thenThrow(new IOException("network down"));

            assertThat(service.isBreached(PWD)).isFalse();
        }

        @Test
        @DisplayName("interrupted send → not breached (fail-open)")
        void interruptedFailsOpen() throws Exception {
            when(http.send(any(HttpRequest.class), any())).thenThrow(new InterruptedException("interrupted"));

            assertThat(service.isBreached(PWD)).isFalse();
        }
    }
}
