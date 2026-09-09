package com.neo.chat.moderation.impl;

import com.neo.chat.moderation.FrameExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link NsfwClientHttpImpl}. The JDK {@link HttpClient} is
 * mocked and injected over the private {@code http} field; a real {@link ObjectMapper}
 * parses sidecar responses. Covers the enable/readability guard, single-image classify
 * over each HTTP outcome (200 true/false/missing-field, non-200, transport failure, bad
 * JSON — all fail-open to {@code Optional.empty()}), and the video frame-aggregation logic
 * (short-circuit on the first NSFW frame, all-clean, all-unknown, no-frames) with cleanup.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NsfwClientHttpImpl (unit)")
class NsfwClientHttpImplTest {

    @Mock
    private FrameExtractor frameExtractor;
    @Mock
    private HttpClient httpClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private NsfwClientHttpImpl client;

    @TempDir
    Path tmp;

    @BeforeEach
    void setUp() {
        client = build(true);
    }

    private NsfwClientHttpImpl build(boolean nsfwEnabled) {
        NsfwClientHttpImpl c = new NsfwClientHttpImpl(frameExtractor, objectMapper, "http://localhost:8081", nsfwEnabled);
        // Swap the inline-constructed HttpClient for the mock.
        ReflectionTestUtils.setField(c, "http", httpClient);
        return c;
    }

    private Path fileWithBytes(String name, String content) throws IOException {
        return Files.writeString(tmp.resolve(name), content);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> httpResp(int code, String body) {
        HttpResponse<String> r = mock(HttpResponse.class);
        lenient().when(r.statusCode()).thenReturn(code);
        lenient().when(r.body()).thenReturn(body);
        return r;
    }

    private void stubSend(HttpResponse<String> resp) throws IOException, InterruptedException {
        when(httpClient.<String>send(any(HttpRequest.class), any())).thenReturn(resp);
    }

    @Nested
    @DisplayName("guard")
    class Guard {

        @Test
        @DisplayName("disabled → empty, no HTTP and no frame extraction")
        void disabled() {
            client = build(false);

            assertThat(client.classify(tmp.resolve("any.jpg"), false)).isEmpty();
            verifyNoInteractions(httpClient, frameExtractor);
        }

        @Test
        @DisplayName("null file → empty")
        void nullFile() {
            assertThat(client.classify(null, false)).isEmpty();
            verifyNoInteractions(httpClient, frameExtractor);
        }

        @Test
        @DisplayName("unreadable/missing file → empty")
        void missingFile() {
            assertThat(client.classify(tmp.resolve("does-not-exist.jpg"), false)).isEmpty();
            verifyNoInteractions(httpClient, frameExtractor);
        }
    }

    @Nested
    @DisplayName("classify image")
    class ClassifyImage {

        @Test
        @DisplayName("200 with nsfw:true → Optional.of(true)")
        void nsfwTrue() throws Exception {
            Path img = fileWithBytes("a.jpg", "bytes");
            stubSend(httpResp(200, "{\"nsfw\":true}"));

            assertThat(client.classify(img, false)).contains(true);
            verify(httpClient).send(any(HttpRequest.class), any());
        }

        @Test
        @DisplayName("200 with nsfw:false → Optional.of(false)")
        void nsfwFalse() throws Exception {
            Path img = fileWithBytes("a.jpg", "bytes");
            stubSend(httpResp(200, "{\"nsfw\":false}"));

            assertThat(client.classify(img, false)).contains(false);
        }

        @Test
        @DisplayName("200 with the nsfw field absent defaults to false")
        void missingFieldDefaultsFalse() throws Exception {
            Path img = fileWithBytes("a.jpg", "bytes");
            stubSend(httpResp(200, "{}"));

            assertThat(client.classify(img, false)).contains(false);
        }

        @Test
        @DisplayName("non-200 → empty (fail-open)")
        void nonOk() throws Exception {
            Path img = fileWithBytes("a.jpg", "bytes");
            stubSend(httpResp(500, "error"));

            assertThat(client.classify(img, false)).isEmpty();
        }

        @Test
        @DisplayName("transport failure → empty (fail-open)")
        void transportFailure() throws Exception {
            Path img = fileWithBytes("a.jpg", "bytes");
            when(httpClient.<String>send(any(HttpRequest.class), any()))
                    .thenThrow(new IOException("sidecar down"));

            assertThat(client.classify(img, false)).isEmpty();
        }

        @Test
        @DisplayName("malformed JSON body → empty (fail-open)")
        void malformedJson() throws Exception {
            Path img = fileWithBytes("a.jpg", "bytes");
            stubSend(httpResp(200, "not-json"));

            assertThat(client.classify(img, false)).isEmpty();
        }
    }

    @Nested
    @DisplayName("classify video")
    class ClassifyVideo {

        private Path video;
        private Path f1;
        private Path f2;

        @BeforeEach
        void frames() throws IOException {
            video = fileWithBytes("clip.mp4", "video");
            f1 = fileWithBytes("f1.jpg", "frame1");
            f2 = fileWithBytes("f2.jpg", "frame2");
        }

        @Test
        @DisplayName("short-circuits to true on the first NSFW frame and cleans up")
        void shortCircuitsOnNsfwFrame() throws Exception {
            when(frameExtractor.extract(video)).thenReturn(List.of(f1, f2));
            stubSend(httpResp(200, "{\"nsfw\":true}"));

            assertThat(client.classify(video, true)).contains(true);

            verify(httpClient, times(1)).send(any(HttpRequest.class), any()); // stopped after f1
            verify(frameExtractor).cleanup(List.of(f1, f2));
        }

        @Test
        @DisplayName("all frames clean → Optional.of(false) and cleans up")
        void allFramesClean() throws Exception {
            when(frameExtractor.extract(video)).thenReturn(List.of(f1, f2));
            stubSend(httpResp(200, "{\"nsfw\":false}"));

            assertThat(client.classify(video, true)).contains(false);

            verify(httpClient, times(2)).send(any(HttpRequest.class), any());
            verify(frameExtractor).cleanup(List.of(f1, f2));
        }

        @Test
        @DisplayName("all frames unknown (non-200) → empty and cleans up")
        void allFramesUnknown() throws Exception {
            when(frameExtractor.extract(video)).thenReturn(List.of(f1));
            stubSend(httpResp(503, "down"));

            assertThat(client.classify(video, true)).isEmpty();

            verify(frameExtractor).cleanup(List.of(f1));
        }

        @Test
        @DisplayName("no frames extracted → empty, no HTTP, still cleans up")
        void noFrames() throws Exception {
            when(frameExtractor.extract(video)).thenReturn(List.of());

            assertThat(client.classify(video, true)).isEmpty();

            verify(httpClient, never()).send(any(HttpRequest.class), any());
            verify(frameExtractor).cleanup(List.of());
        }
    }
}
