package com.chat.talkMe.moderation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Extracts a few evenly-spaced frames from a video using ffmpeg (already installed). */
@Slf4j
@Component
public class FrameExtractor {

    @Value("${media.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;

    private static final int FRAME_COUNT = 5;
    private static final long TIMEOUT_SECONDS = 60;

    /** Returns paths to extracted JPEG frames (caller must delete them via {@link #cleanup}). */
    public List<Path> extract(Path video) {
        List<Path> frames = new ArrayList<>();
        try {
            Path dir = Files.createTempDirectory("nsfw-frames-");
            Path pattern = dir.resolve(UUID.randomUUID() + "-%03d.jpg");
            // One frame every few seconds, capped at FRAME_COUNT, scaled small for speed.
            List<String> cmd = List.of(
                    ffmpegPath, "-y",
                    "-i", video.toString(),
                    "-vf", "fps=1/2,scale=224:-1",
                    "-frames:v", String.valueOf(FRAME_COUNT),
                    pattern.toString());
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("ffmpeg frame extraction timed out for {}", video);
                return frames;
            }
            try (var stream = Files.list(dir)) {
                stream.filter(f -> f.toString().endsWith(".jpg")).forEach(frames::add);
            }
        } catch (Exception e) {
            log.warn("Frame extraction failed for {}: {}", video, e.getMessage());
        }
        return frames;
    }

    public void cleanup(List<Path> frames) {
        for (Path f : frames) {
            try {
                Files.deleteIfExists(f);
                Path parent = f.getParent();
                if (parent != null) Files.deleteIfExists(parent);
            } catch (Exception ignored) {
            }
        }
    }
}
