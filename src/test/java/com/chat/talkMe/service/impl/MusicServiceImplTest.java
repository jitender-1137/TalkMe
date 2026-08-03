package com.chat.talkMe.service.impl;

import com.chat.talkMe.dto.response.MusicTrackResponse;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Pure Mockito unit test for {@link MusicServiceImpl} — the server-side proxy over the free iTunes
 * Search API. The internal {@link HttpClient} is swapped for a mock via reflection so the mapping,
 * filtering, limit clamping and fail-open behaviour can be exercised without network I/O. A real
 * {@link ObjectMapper} parses the crafted JSON bodies.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MusicServiceImpl (unit)")
class MusicServiceImplTest {

    @Mock private HttpClient httpClient;

    private MusicServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MusicServiceImpl(new ObjectMapper());
        // Replace the inline-constructed final HttpClient with our mock.
        ReflectionTestUtils.setField(service, "httpClient", httpClient);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status, String body) {
        HttpResponse<String> resp = mock(HttpResponse.class);
        doReturn(status).when(resp).statusCode();
        // body() is not read on the non-200 fail-open path — keep it lenient.
        lenient().doReturn(body).when(resp).body();
        return resp;
    }

    private void stubHttp(HttpResponse<String> resp) throws Exception {
        doReturn(resp).when(httpClient).send(any(HttpRequest.class), any());
    }

    @Nested
    @DisplayName("search")
    class Search {

        @Test
        @DisplayName("null query → empty list, no HTTP call")
        void nullQuery() {
            assertThat(service.search(null, 10)).isEmpty();
            verifyNoInteractions(httpClient);
        }

        @Test
        @DisplayName("blank query → empty list, no HTTP call")
        void blankQuery() {
            assertThat(service.search("   ", 10)).isEmpty();
            verifyNoInteractions(httpClient);
        }

        @Test
        @DisplayName("non-200 response → empty list (fail-open)")
        void nonOkStatus() throws Exception {
            stubHttp(response(503, ""));

            assertThat(service.search("adele", 10)).isEmpty();
        }

        @Test
        @DisplayName("HTTP client throws → empty list (fail-open), never propagates")
        void clientThrows() throws Exception {
            doThrow(new IOException("network down")).when(httpClient).send(any(HttpRequest.class), any());

            assertThat(service.search("adele", 10)).isEmpty();
        }

        @Test
        @DisplayName("maps playable tracks; upgrades artwork; skips tracks without a preview")
        void mapsResults() throws Exception {
            String json = "{\"results\":["
                    + "{\"trackId\":123,\"trackName\":\"Song A\",\"artistName\":\"Artist A\","
                    + "\"artworkUrl100\":\"http://x/100x100bb.jpg\",\"previewUrl\":\"http://x/prev.m4a\","
                    + "\"trackTimeMillis\":210000},"
                    + "{\"trackId\":456,\"trackName\":\"No Preview\",\"artistName\":\"B\","
                    + "\"artworkUrl100\":\"http://y/100x100.jpg\",\"trackTimeMillis\":100000},"
                    + "{\"trackId\":789,\"trackName\":\"No Art\",\"artistName\":\"C\","
                    + "\"previewUrl\":\"http://z/p.m4a\"}"
                    + "]}";
            stubHttp(response(200, json));

            List<MusicTrackResponse> out = service.search("songs", 25);

            assertThat(out).hasSize(2); // 456 dropped (no preview)

            MusicTrackResponse a = out.get(0);
            assertThat(a.getId()).isEqualTo("123");
            assertThat(a.getTitle()).isEqualTo("Song A");
            assertThat(a.getArtist()).isEqualTo("Artist A");
            assertThat(a.getArtworkUrl()).isEqualTo("http://x/300x300bb.jpg"); // 100x100 → 300x300
            assertThat(a.getPreviewUrl()).isEqualTo("http://x/prev.m4a");
            assertThat(a.getDurationSec()).isEqualTo(210); // millis / 1000

            MusicTrackResponse c = out.get(1);
            assertThat(c.getId()).isEqualTo("789");
            assertThat(c.getArtworkUrl()).isNull();      // no artworkUrl100
            assertThat(c.getDurationSec()).isZero();      // no trackTimeMillis → default 0
        }

        @Test
        @DisplayName("empty results array → empty list")
        void emptyResults() throws Exception {
            stubHttp(response(200, "{\"results\":[]}"));
            assertThat(service.search("nothing", 10)).isEmpty();
        }

        @Test
        @DisplayName("limit below 1 is clamped to 1 in the request URL")
        void clampsLimitLow() throws Exception {
            stubHttp(response(200, "{\"results\":[]}"));

            service.search("x", 0);

            assertThat(capturedUri()).contains("limit=1");
        }

        @Test
        @DisplayName("limit above 50 is clamped to 50 in the request URL")
        void clampsLimitHigh() throws Exception {
            stubHttp(response(200, "{\"results\":[]}"));

            service.search("x", 999);

            assertThat(capturedUri()).contains("limit=50");
        }

        @Test
        @DisplayName("query is trimmed and URL-encoded in the request")
        void trimsAndEncodesTerm() throws Exception {
            stubHttp(response(200, "{\"results\":[]}"));

            service.search("  hello world  ", 10);

            assertThat(capturedUri()).contains("term=hello+world");
        }
    }

    private String capturedUri() throws Exception {
        ArgumentCaptor<HttpRequest> cap = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(cap.capture(), any());
        return cap.getValue().uri().toString();
    }
}
