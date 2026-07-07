package com.chat.talkMe.service.impl;

import com.chat.talkMe.dto.response.MusicTrackResponse;
import com.chat.talkMe.service.MusicService;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Proxies the free, key-less iTunes Search API and maps it to our track shape.
 * Proxying server-side avoids browser CORS issues; the ~30s preview clips it
 * returns are playable cross-origin from Apple's CDN via an <audio> element.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MusicServiceImpl implements MusicService {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public List<MusicTrackResponse> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int lim = Math.min(Math.max(limit, 1), 50);
        try {
            String url = "https://itunes.apple.com/search?media=music&entity=song&limit=" + lim
                    + "&term=" + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("iTunes search returned status {}", response.statusCode());
                return List.of();
            }
            JsonNode results = objectMapper.readTree(response.body()).path("results");
            List<MusicTrackResponse> out = new ArrayList<>();
            for (JsonNode r : results) {
                String preview = r.path("previewUrl").asText(null);
                if (preview == null || preview.isBlank()) {
                    continue; // only tracks we can actually play
                }
                String artwork = r.path("artworkUrl100").asText(null);
                if (artwork != null) {
                    artwork = artwork.replace("100x100", "300x300");
                }
                out.add(MusicTrackResponse.builder()
                        .id(r.path("trackId").asText())
                        .title(r.path("trackName").asText())
                        .artist(r.path("artistName").asText())
                        .artworkUrl(artwork)
                        .previewUrl(preview)
                        .durationSec(r.path("trackTimeMillis").asInt(0) / 1000)
                        .build());
            }
            return out;
        } catch (Exception e) {
            log.warn("Music search failed for '{}': {}", query, e.toString());
            return List.of();
        }
    }
}
