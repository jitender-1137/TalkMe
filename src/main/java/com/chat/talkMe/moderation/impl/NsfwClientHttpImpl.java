package com.chat.talkMe.moderation.impl;

import com.chat.talkMe.moderation.FrameExtractor;
import com.chat.talkMe.moderation.NsfwClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class NsfwClientHttpImpl implements NsfwClient {

    private final FrameExtractor frameExtractor;
    private final ObjectMapper objectMapper;

    @Value("${moderation.nsfw.base-url:http://localhost:8081}")
    private String baseUrl;

    @Value("${moderation.nsfw.enabled:true}")
    private boolean nsfwEnabled;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

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
