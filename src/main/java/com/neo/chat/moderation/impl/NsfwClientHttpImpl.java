package com.neo.chat.moderation.impl;

import com.neo.chat.moderation.FrameExtractor;
import com.neo.chat.moderation.NsfwClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Calls the free self-hosted NSFW sidecar over HTTP, sending raw image bytes (so it
 * works whether the sidecar is local or containerized — no shared volume needed).
 * Any failure returns {@code Optional.empty()} so callers apply their fail policy.
 */
@Slf4j
@Component
public class NsfwClientHttpImpl implements NsfwClient {

    private final FrameExtractor frameExtractor;
    private final ObjectMapper objectMapper;

    private final String baseUrl;

    private final boolean nsfwEnabled;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public NsfwClientHttpImpl(FrameExtractor frameExtractor,
                              ObjectMapper objectMapper,
                              @Value("${moderation.nsfw.base-url:http://localhost:8081}") String baseUrl,
                              @Value("${moderation.nsfw.enabled:true}") boolean nsfwEnabled) {
        this.frameExtractor = frameExtractor;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.nsfwEnabled = nsfwEnabled;
    }

    /**
     * Classifies a stored file: for video it extracts frames and returns NSFW if ANY frame
     * is flagged (empty only if no frame could be classified); for images it classifies the
     * bytes directly. Returns empty when disabled, unreadable, or on any error (fail policy
     * left to the caller). Extracted video frames are always cleaned up.
     *
     * @param storedFile the java.nio.file.Path of the media file to classify
     * @param isVideo    true to treat the file as a video (frame-sample), false for an image
     * @return java.util.Optional of java.lang.Boolean — true=NSFW, false=clean, empty=unknown
     */
    @Override
    public Optional<Boolean> classify(Path storedFile, boolean isVideo) {
        if (!nsfwEnabled || storedFile == null || !Files.isReadable(storedFile)) {
            return Optional.empty();
        }
        try {
            if (isVideo) {
                List<Path> frames = frameExtractor.extract(storedFile);
                try {
                    boolean anyKnown = false;
                    for (Path frame : frames) {
                        Optional<Boolean> v = classifyBytes(Files.readAllBytes(frame));
                        if (v.isPresent()) {
                            anyKnown = true;
                            if (v.get()) return Optional.of(true);
                        }
                    }
                    return anyKnown ? Optional.of(false) : Optional.empty();
                } finally {
                    frameExtractor.cleanup(frames);
                }
            }
            return classifyBytes(Files.readAllBytes(storedFile));
        } catch (Exception e) {
            log.warn("NSFW classify failed for {}: {}", storedFile, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * POSTs the raw bytes to the sidecar's {@code /classify} endpoint and reads the boolean
     * {@code nsfw} field of the JSON response; returns empty on non-200 status or any error.
     *
     * @param bytes the raw image bytes to send
     * @return java.util.Optional of java.lang.Boolean — true=NSFW, false=clean, empty=unknown
     */
    private Optional<Boolean> classifyBytes(byte[] bytes) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/classify"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return Optional.empty();
            }
            JsonNode node = objectMapper.readTree(resp.body());
            return Optional.of(node.path("nsfw").asBoolean(false));
        } catch (Exception e) {
            log.warn("NSFW sidecar call failed: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
