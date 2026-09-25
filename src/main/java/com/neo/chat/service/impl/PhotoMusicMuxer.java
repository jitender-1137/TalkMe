package com.neo.chat.service.impl;

import com.neo.chat.util.FfmpegSupport;
import com.neo.chat.storage.MediaKeys;
import com.neo.chat.storage.MediaStorage;
import com.neo.chat.storage.StorageProperties;
import com.neo.chat.util.SsrfGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final StorageProperties storageProperties;
    private final Executor drainExecutor;

    /**
     * @param drainExecutor shared executor used to drain ffmpeg's stderr concurrently with
     *                      {@code waitFor} (the application task pool, so no ad-hoc threads)
     */
    public PhotoMusicMuxer(FfmpegSupport ffmpeg, MediaStorage mediaStorage,
                           StorageProperties storageProperties,
                           @Qualifier("applicationTaskExecutor") Executor drainExecutor) {
        this.ffmpeg = ffmpeg;
        this.mediaStorage = mediaStorage;
        this.storageProperties = storageProperties;
        this.drainExecutor = drainExecutor;
    }

    /**
     * Mux a still image with a trimmed audio clip into an MP4 stored beside the source image;
     * validates the window against the probed audio duration and returns null on any failure.
     *
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
        List<String> command = new ArrayList<>(List.of(
                ffmpeg.path(), "-y", "-nostdin",
                // SECURITY: restrict demuxer protocols to local file/pipe so a crafted image/audio
                // container cannot make ffmpeg fetch remote/file segments (SSRF/LFI).
                "-protocol_whitelist", "file,pipe",
                "-loop", "1", "-i", image.toString(),
                "-ss", String.valueOf(start), "-i", audio.toString(),
                "-t", String.valueOf(clip),
                "-map_metadata", "-1"));
        // H.264 encoder is chosen from what THIS ffmpeg actually has (libx264 / libopenh264 /
        // mpeg4 fallback) — a hardcoded encoder was silently failing on servers whose ffmpeg
        // lacked libopenh264, causing the post to fall back to image + separate audio.
        command.addAll(ffmpeg.h264VideoArgs());
        command.addAll(List.of(
                "-pix_fmt", "yuv420p",
                "-r", "24",
                "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2",
                "-c:a", "aac",
                "-b:a", "128k",
                "-movflags", "+faststart",
                "-shortest",
                output.toString()
        ));

        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            // Drain stderr on the shared task executor: (1) so the OS pipe buffer never
            // fills and deadlocks waitFor(), and (2) so we can log FFMpeg's REAL error on
            // failure instead of a bare exit code (the old DISCARD hid every cause).
            final Process running = process;
            final StringBuilder err = new StringBuilder();
            CompletableFuture<Void> drain = drainStderr(running, err);

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                awaitQuietly(drain, 2000);
                log.warn("Photo+music mux timed out after {}s. ffmpeg: {}", TIMEOUT_SECONDS, tail(err));
                return false;
            }
            awaitQuietly(drain, 2000); // stderr hits EOF once the process exits
            if (process.exitValue() != 0) {
                log.warn("Photo+music mux ffmpeg exited {}: {}", process.exitValue(), tail(err));
                return false;
            }
            return true;
        } catch (IOException e) {
            log.warn("Photo+music mux failed to run ffmpeg ('{}'): {}", ffmpeg.path(), e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return false;
        }
    }

    /**
     * Drains the process's stderr into {@code err} (capped) on the shared executor, so the pipe
     * never blocks the child while the caller waits on it.
     */
    private CompletableFuture<Void> drainStderr(Process running, StringBuilder err) {
        return CompletableFuture.runAsync(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    running.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (err.length() < 8000) err.append(line).append('\n');
                }
            } catch (IOException ignored) {
                // stream closed on process exit
            }
        }, drainExecutor);
    }

    /**
     * Waits up to {@code millis} for the drain to finish (best-effort: a timeout or drain failure
     * just means a shorter stderr tail). Interrupts propagate to the caller.
     */
    private static void awaitQuietly(CompletableFuture<?> drain, long millis) throws InterruptedException {
        try {
            drain.get(millis, TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException ignored) {
            // best-effort stderr capture
        }
    }

    /**
     * Last ~500 chars of captured stderr — enough to see the real ffmpeg error.
     */
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
            p = new ProcessBuilder(ffmpeg.path(), "-hide_banner", "-nostdin",
                    "-protocol_whitelist", "file,pipe", "-i", audio.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            final Process running = p;
            final StringBuilder err = new StringBuilder();
            CompletableFuture<Void> drain = drainStderr(running, err);
            p.waitFor(20, TimeUnit.SECONDS);
            awaitQuietly(drain, 2000);
            Matcher m =
                    Pattern.compile("Duration:\\s*(\\d+):(\\d+):(\\d+)").matcher(err);
            if (m.find()) {
                return Integer.parseInt(m.group(1)) * 3600
                        + Integer.parseInt(m.group(2)) * 60
                        + Integer.parseInt(m.group(3));
            }
        } catch (IOException e) {
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
    private Path downloadToTemp(String url) throws IOException {
        // SSRF guard: the only legitimate external audio is a public preview URL.
        // Reject anything resolving to loopback/link-local (incl. cloud metadata)/
        // private hosts, and don't follow redirects into them.
        Path tmp = Files.createTempFile("talkme-music-", ".audio");
        try (InputStream in = SsrfGuard.openStream(url)) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        return tmp;
    }

    /**
     * Build a key in the same folder as the source image, with a fresh filename.
     * Falls back to {@code others/} when the image reference carries no derivable folder.
     */
    private String siblingKey(String imageRef, String newName) {
        String imageKey = MediaKeys.key(imageRef, storageProperties.getMediaRoot(),
                storageProperties.getLegacyMediaRoots());
        if (imageKey == null) return "others/" + newName;
        int slash = imageKey.lastIndexOf('/');
        return slash >= 0 ? imageKey.substring(0, slash + 1) + newName : newName;
    }

    private void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best-effort temp cleanup
        }
    }
}
