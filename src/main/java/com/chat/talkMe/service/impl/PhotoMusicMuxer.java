package com.chat.talkMe.service.impl;

import com.chat.talkMe.storage.MediaStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Merges a still photo + a trimmed music clip into an autoplaying MP4 — the way
 * Instagram turns a photo-with-music post/story into a video whose sound plays
 * automatically. Reuses the server's already-installed ffmpeg (see
 * {@code media.ffmpeg-path}), so no new dependency.
 *
 * <p>The ffmpeg command is unchanged; only the input/output plumbing goes through
 * {@link MediaStorage} so it works whether media lives on disk (local/dev) or in OCI
 * (prod). Every failure path returns null so the caller can gracefully fall back to
 * the "image + separate audio track" behavior instead of failing the post.
 */
@Slf4j
@Component
public class PhotoMusicMuxer {

    private static final long TIMEOUT_SECONDS = 120;
    private static final int DEFAULT_CLIP_SECONDS = 15;

    private final FfmpegSupport ffmpeg;
    private final MediaStorage mediaStorage;
    private final com.chat.talkMe.storage.StorageProperties storageProperties;

    public PhotoMusicMuxer(FfmpegSupport ffmpeg, MediaStorage mediaStorage,
                           com.chat.talkMe.storage.StorageProperties storageProperties) {
        this.ffmpeg = ffmpeg;
        this.mediaStorage = mediaStorage;
        this.storageProperties = storageProperties;
    }

    /**
     * @param imageRef the stored image (storage reference)
     * @param audioRef the soundtrack (internal storage reference OR external preview URL)
     * @param startSec offset into the audio to start from
     * @param clipSec  length of the clip / resulting video
     * @return storage reference of the produced .mp4, or null on any failure.
     */
    public String muxPhotoWithMusic(String imageRef, String audioRef, int startSec, int clipSec) {
        Path output = null;
        Path downloadedAudio = null;
        try (MediaStorage.LocalFile image = mediaStorage.localCopy(imageRef).orElse(null)) {
            if (image == null || !Files.exists(image.path())) {
                log.warn("Photo+music mux skipped: image not resolvable ({})", imageRef);
                return null;
            }

            // Audio: an internal stored file, or an external http(s) preview URL.
            Path audioPath;
            try (MediaStorage.LocalFile internalAudio = mediaStorage.localCopy(audioRef).orElse(null)) {
                if (internalAudio != null && Files.exists(internalAudio.path())) {
                    audioPath = internalAudio.path();
                } else if (audioRef != null && audioRef.startsWith("http")) {
                    downloadedAudio = downloadToTemp(audioRef);
                    audioPath = downloadedAudio;
                } else {
                    log.warn("Photo+music mux skipped: audio not resolvable ({})", audioRef);
                    return null;
                }

                int clip = clipSec > 0 ? clipSec : DEFAULT_CLIP_SECONDS;
                int start = Math.max(0, startSec);

                // Detect the audio duration and validate the selected window is inside
                // the track (the frontend also clamps; this is the server-side safety
                // net that rejects an impossible/out-of-range selection). A probe
                // failure (duration <= 0) is non-fatal — we defer to -t/-shortest.
                int duration = probeDurationSeconds(audioPath);
                if (duration > 0) {
                    if (start >= duration) {
                        log.warn("Photo+music mux skipped: start {}s is beyond audio duration {}s",
                                start, duration);
                        return null;
                    }
                    if (start + clip > duration) {
                        if (duration < clip) {
                            log.warn("Photo+music mux skipped: audio {}s shorter than the {}s clip",
                                    duration, clip);
                            return null;
                        }
                        // Window runs off the end — pull the start back so a full clip fits.
                        start = duration - clip;
                    }
                }

                output = Files.createTempFile("talkme-mux-", ".mp4");

                if (!runFfmpeg(image.path(), audioPath, start, clip, output)) {
                    return null;
                }
                if (!Files.exists(output) || Files.size(output) == 0) {
                    log.warn("Photo+music mux produced no output");
                    return null;
                }

                // File the muxed output alongside the source image, so it inherits the
                // same category folder (posts/<uid>, stories/<uid>, …).
                String key = siblingKey(imageRef, UUID.randomUUID() + ".mp4");
                String ref = mediaStorage.store(output, key, "video/mp4");
                log.info("Photo+music muxed → {}", ref);
                return ref;
            }
        } catch (Exception e) {
            log.warn("Photo+music mux error: {}", e.getMessage());
            return null;
        } finally {
            deleteQuietly(output);
            deleteQuietly(downloadedAudio);
        }
    }

    /**
     * Run ffmpeg to loop the image over the trimmed audio, producing an iOS-safe H.264/AAC MP4.
     */
    private boolean runFfmpeg(Path image, Path audio, int start, int clip, Path output) {
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
                    .start();
            // Drain stderr on a daemon thread: (1) so the OS pipe buffer never fills
            // and deadlocks waitFor(), and (2) so we can log ffmpeg's REAL error on
            // failure instead of a bare exit code (the old DISCARD hid every cause).
            final Process running = process;
            final StringBuilder err = new StringBuilder();
            Thread drain = new Thread(() -> {
                try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                        running.getErrorStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (err.length() < 8000) err.append(line).append('\n');
                    }
                } catch (java.io.IOException ignored) {
                    // stream closed on process exit
                }
            }, "ffmpeg-stderr");
            drain.setDaemon(true);
            drain.start();

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                drain.join(2000);
                log.warn("Photo+music mux timed out after {}s. ffmpeg: {}", TIMEOUT_SECONDS, tail(err));
                return false;
            }
            drain.join(2000); // stderr hits EOF once the process exits
            if (process.exitValue() != 0) {
                log.warn("Photo+music mux ffmpeg exited {}: {}", process.exitValue(), tail(err));
                return false;
            }
            return true;
        } catch (java.io.IOException e) {
            log.warn("Photo+music mux failed to run ffmpeg ('{}'): {}", ffmpeg.path(), e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            return false;
        }
    }

    /** Last ~500 chars of captured stderr — enough to see the real ffmpeg error. */
    private static String tail(StringBuilder sb) {
        String s = sb.toString().strip();
        if (s.isEmpty()) return "(no stderr captured)";
        return s.length() > 500 ? "…" + s.substring(s.length() - 500) : s;
    }

    /**
     * Detect the audio duration in whole seconds from ffmpeg's stderr banner
     * ({@code Duration: HH:MM:SS.xx}). Returns -1 when it can't be determined —
     * callers treat that as "unknown" and skip range validation.
     */
    private int probeDurationSeconds(Path audio) {
        Process p = null;
        try {
            // `ffmpeg -i <audio>` with no output prints the media info then exits
            // non-zero ("no output file") — we only care about the printed Duration.
            p = new ProcessBuilder(ffmpeg.path(), "-hide_banner", "-i", audio.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            final Process running = p;
            final StringBuilder err = new StringBuilder();
            Thread drain = new Thread(() -> {
                try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                        running.getErrorStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (err.length() < 8000) err.append(line).append('\n');
                    }
                } catch (java.io.IOException ignored) {
                    // stream closed on process exit
                }
            }, "ffmpeg-probe");
            drain.setDaemon(true);
            drain.start();
            p.waitFor(20, TimeUnit.SECONDS);
            drain.join(2000);
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("Duration:\\s*(\\d+):(\\d+):(\\d+)").matcher(err);
            if (m.find()) {
                return Integer.parseInt(m.group(1)) * 3600
                        + Integer.parseInt(m.group(2)) * 60
                        + Integer.parseInt(m.group(3));
            }
        } catch (java.io.IOException e) {
            log.debug("Audio duration probe could not run ffmpeg: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (p != null) p.destroyForcibly();
        }
        return -1;
    }

    /**
     * Download an external audio URL to a temp file for ffmpeg (avoids relying on ffmpeg's network/TLS).
     */
    private Path downloadToTemp(String url) throws java.io.IOException {
        // SSRF guard: the only legitimate external audio is a public preview URL.
        // Reject anything resolving to loopback/link-local (incl. cloud metadata)/
        // private hosts, and don't follow redirects into them.
        Path tmp = Files.createTempFile("talkme-music-", ".audio");
        try (InputStream in = com.chat.talkMe.util.SsrfGuard.openStream(url)) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        return tmp;
    }

    /**
     * Build a key in the same folder as the source image, with a fresh filename.
     * Falls back to {@code others/} when the image reference carries no derivable folder.
     */
    private String siblingKey(String imageRef, String newName) {
        String imageKey = com.chat.talkMe.storage.MediaKeys.key(imageRef, storageProperties.getMediaRoot());
        if (imageKey == null) return "others/" + newName;
        int slash = imageKey.lastIndexOf('/');
        return slash >= 0 ? imageKey.substring(0, slash + 1) + newName : newName;
    }

    private void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (java.io.IOException ignored) {
            // best-effort temp cleanup
        }
    }
}
