package com.chat.talkMe.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Merges a still photo + a trimmed music clip into an auto-playing MP4 — the way
 * Instagram turns a photo-with-music post/story into a video whose sound plays
 * automatically. Reuses the server's already-installed ffmpeg (see
 * {@code media.ffmpeg-path}), so no new dependency.
 *
 * ffmpeg loops the image for the clip length, seeks into the audio at the chosen
 * start, and encodes H.264 (yuv420p, even dims) + AAC — the combination that
 * plays everywhere, iOS included (WebM/Opus would not autoplay on iOS).
 *
 * Every failure path returns null so the caller can gracefully fall back to the
 * old "image + separate audio track" behaviour instead of failing the post.
 */
@Slf4j
@Component
public class PhotoMusicMuxer {

    private static final Path STORAGE_PATH = Paths.get("/opt/media/talkMe");
    private static final long TIMEOUT_SECONDS = 120;
    private static final int DEFAULT_CLIP_SECONDS = 15;

    private final FfmpegSupport ffmpeg;

    public PhotoMusicMuxer(FfmpegSupport ffmpeg) {
        this.ffmpeg = ffmpeg;
    }

    /**
     * @param imageUrlOrPath the stored image (disk path or /uploads/media?path= URL)
     * @param audioUrlOrPath the soundtrack (internal stored file OR external preview URL)
     * @param startSec       offset into the audio to start from
     * @param clipSec        length of the clip / resulting video
     * @return absolute disk path of the produced .mp4, or null on any failure.
     */
    public String muxPhotoWithMusic(String imageUrlOrPath, String audioUrlOrPath, int startSec, int clipSec) {
        Path image = resolveLocalMedia(imageUrlOrPath);
        if (image == null || !Files.exists(image)) {
            log.warn("Photo+music mux skipped: image not resolvable ({})", imageUrlOrPath);
            return null;
        }

        Path audio = null;
        Path downloadedTemp = null;
        try {
            Path internal = resolveLocalMedia(audioUrlOrPath);
            if (internal != null && Files.exists(internal)) {
                audio = internal;
            } else if (audioUrlOrPath != null && audioUrlOrPath.startsWith("http")) {
                downloadedTemp = downloadToTemp(audioUrlOrPath);
                audio = downloadedTemp;
            }
            if (audio == null) {
                log.warn("Photo+music mux skipped: audio not resolvable ({})", audioUrlOrPath);
                return null;
            }

            int clip = clipSec > 0 ? clipSec : DEFAULT_CLIP_SECONDS;
            int start = Math.max(0, startSec);
            Path output = image.getParent().resolve(UUID.randomUUID() + ".mp4");

            List<String> command = List.of(
                    ffmpeg.path(), "-y",
                    "-loop", "1", "-i", image.toString(),
                    "-ss", String.valueOf(start), "-i", audio.toString(),
                    "-t", String.valueOf(clip),
                    // libopenh264 (bundled, cross-platform, BSD) — libx264 is not in the
                    // bundled build. Produces standard H.264 (avc1) that plays on iOS.
                    // openh264 uses target bitrate, not -crf/-preset/-tune.
                    "-c:v", "libopenh264",
                    "-b:v", "2000k",
                    "-pix_fmt", "yuv420p",
                    "-r", "24",
                    "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2",
                    "-c:a", "aac",
                    "-b:a", "128k",
                    "-movflags", "+faststart",
                    "-shortest",
                    output.toString()
            );

            Process process = null;
            try {
                process = new ProcessBuilder(command)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    log.warn("Photo+music mux timed out after {}s", TIMEOUT_SECONDS);
                    return null;
                }
                if (process.exitValue() != 0) {
                    log.warn("Photo+music mux ffmpeg exited {}", process.exitValue());
                    return null;
                }
                if (!Files.exists(output) || Files.size(output) == 0) {
                    log.warn("Photo+music mux produced no output");
                    return null;
                }
                log.info("Photo+music muxed → {}", output);
                return output.toString();
            } catch (java.io.IOException e) {
                log.warn("Photo+music mux failed to run ffmpeg ('{}'): {}", ffmpeg.path(), e.getMessage());
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (process != null) process.destroyForcibly();
                return null;
            }
        } catch (Exception e) {
            log.warn("Photo+music mux error: {}", e.getMessage());
            return null;
        } finally {
            if (downloadedTemp != null) {
                try {
                    Files.deleteIfExists(downloadedTemp);
                } catch (java.io.IOException ignored) {
                    // best-effort temp cleanup
                }
            }
        }
    }

    /** Download an external audio URL to a temp file for ffmpeg (avoids relying on ffmpeg's network/TLS). */
    private Path downloadToTemp(String url) throws java.io.IOException {
        Path tmp = Files.createTempFile("talkme-music-", ".audio");
        try (InputStream in = URI.create(url).toURL().openStream()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        return tmp;
    }

    /**
     * Resolve a stored-media reference to an on-disk path UNDER the media root
     * (path-traversal safe). Accepts a raw absolute path, or a serve URL carrying
     * {@code ?path=<url-encoded absolute path>}. Returns null for anything else
     * (e.g. an external http URL).
     */
    private Path resolveLocalMedia(String urlOrPath) {
        if (urlOrPath == null || urlOrPath.isBlank()) return null;
        String candidate = urlOrPath;
        int idx = urlOrPath.indexOf("path=");
        if (idx >= 0) {
            String raw = urlOrPath.substring(idx + "path=".length());
            int amp = raw.indexOf('&');
            if (amp >= 0) raw = raw.substring(0, amp);
            candidate = URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } else if (!urlOrPath.startsWith("/opt/media/")) {
            return null;
        }
        try {
            Path p = Paths.get(candidate).normalize();
            if (!p.startsWith(STORAGE_PATH)) return null;
            return p;
        } catch (Exception e) {
            return null;
        }
    }
}
