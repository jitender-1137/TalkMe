package com.chat.talkMe.service.impl;

import com.chat.talkMe.config.CaptchaProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Pure Mockito unit test for {@link CaptchaServiceImpl} — Cloudflare Turnstile verification.
 *
 * <p>The internal {@link HttpClient} is a private final field created inline; the test injects a
 * mock over it via {@link ReflectionTestUtils} so the network call can be driven deterministically.
 * A REAL {@link ObjectMapper} parses the mocked response body. Key invariants: the master switch
 * short-circuits to {@code true}; blank/null tokens are rejected pre-flight; the endpoint's
 * {@code success} flag is the verdict; and every failure mode (missing flag, transport error,
 * unparseable body) fails CLOSED ({@code false}) so a broken check never lets a bot through.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CaptchaServiceImpl (unit)")
class CaptchaServiceImplTest {

    private static final String VERIFY_URL =
            "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    @Mock
    private CaptchaProperties properties;
    @Mock
    private HttpClient httpClient;

    private CaptchaServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CaptchaServiceImpl(properties, new ObjectMapper());
        // Swap the inline-constructed HttpClient for the mock (non-static final instance field).
        ReflectionTestUtils.setField(service, "httpClient", httpClient);
    }

    @SuppressWarnings("unchecked")
    private void stubResponse(String body) throws Exception {
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.body()).thenReturn(body);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);
    }

    @Nested
    @DisplayName("verify — short-circuits")
    class ShortCircuits {

        @Test
        @DisplayName("CAPTCHA disabled → returns true without any network call")
        void disabledSkipsVerification() throws Exception {
            when(properties.isEnabled()).thenReturn(false);

            assertThat(service.verify("any-token", "1.2.3.4")).isTrue();
            verify(httpClient, never()).send(any(), any());
        }

        @Test
        @DisplayName("null token (enabled) → false, no network call")
        void nullTokenRejected() throws Exception {
            when(properties.isEnabled()).thenReturn(true);

            assertThat(service.verify(null, "1.2.3.4")).isFalse();
            verify(httpClient, never()).send(any(), any());
        }

        @Test
        @DisplayName("blank token (enabled) → false, no network call")
        void blankTokenRejected() throws Exception {
            when(properties.isEnabled()).thenReturn(true);

            assertThat(service.verify("   ", "1.2.3.4")).isFalse();
            verify(httpClient, never()).send(any(), any());
        }
    }

    @Nested
    @DisplayName("verify — remote verification")
    class RemoteVerification {

        @BeforeEach
        void enable() {
            when(properties.isEnabled()).thenReturn(true);
            lenient().when(properties.getSecretKey()).thenReturn("secret-key");
        }

        @Test
        @DisplayName("siteverify success:true → true, POSTed to the Turnstile endpoint")
        void successTrue() throws Exception {
            stubResponse("{\"success\":true}");

            boolean result = service.verify("tok", "9.9.9.9");

            assertThat(result).isTrue();
            ArgumentCaptor<HttpRequest> req = ArgumentCaptor.forClass(HttpRequest.class);
            verify(httpClient).send(req.capture(), any());
            assertThat(req.getValue().uri().toString()).isEqualTo(VERIFY_URL);
            assertThat(req.getValue().method()).isEqualTo("POST");
        }

        @Test
        @DisplayName("siteverify success:false → false")
        void successFalse() throws Exception {
            stubResponse("{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}");

            assertThat(service.verify("tok", "9.9.9.9")).isFalse();
        }

        @Test
        @DisplayName("response body missing the success field → false (defaults to false)")
        void missingSuccessField() throws Exception {
            stubResponse("{\"messages\":[]}");

            assertThat(service.verify("tok", "9.9.9.9")).isFalse();
        }

        @Test
        @DisplayName("no remoteIp (null) → still verifies and honours the success flag")
        void nullRemoteIpStillVerifies() throws Exception {
            stubResponse("{\"success\":true}");

            assertThat(service.verify("tok", null)).isTrue();
            verify(httpClient).send(any(), any());
        }

        @Test
        @DisplayName("blank remoteIp → the remoteip form param is skipped, still verifies")
        void blankRemoteIpSkipped() throws Exception {
            stubResponse("{\"success\":true}");

            assertThat(service.verify("tok", "  ")).isTrue();
            verify(httpClient).send(any(), any());
        }

        @Test
        @DisplayName("null secret key → encoded as empty, verification still runs")
        void nullSecretKeyEncodedEmpty() throws Exception {
            when(properties.getSecretKey()).thenReturn(null);
            stubResponse("{\"success\":true}");

            assertThat(service.verify("tok", "9.9.9.9")).isTrue();
        }

        @Test
        @DisplayName("transport error (send throws IOException) → fails CLOSED (false)")
        void transportErrorFailsClosed() throws Exception {
            when(httpClient.send(any(HttpRequest.class), any())).thenThrow(new IOException("network down"));

            assertThat(service.verify("tok", "9.9.9.9")).isFalse();
        }

        @Test
        @DisplayName("interrupted send → fails CLOSED (false)")
        void interruptedFailsClosed() throws Exception {
            when(httpClient.send(any(HttpRequest.class), any())).thenThrow(new InterruptedException("interrupted"));

            assertThat(service.verify("tok", "9.9.9.9")).isFalse();
        }

        @Test
        @DisplayName("unparseable response body → fails CLOSED (false)")
        void unparseableBodyFailsClosed() throws Exception {
            stubResponse("this is <not> json");

            assertThat(service.verify("tok", "9.9.9.9")).isFalse();
        }
    }
}
