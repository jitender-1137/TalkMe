package com.chat.talkMe.service.impl;

import com.chat.talkMe.storage.MediaStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * <p>The ffmpeg command is unchanged; only the input/output plumbing goes through
 * {@link MediaStorage} so it works whether media lives on disk (local/dev) or in OCI
 * (prod). Every failure path returns null so the caller can gracefully fall back to
 * the "image + separate audio track" behaviour instead of failing the post.
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
            MediaStorage.LocalFile internalAudio = null;
            try {
                internalAudio = mediaStorage.localCopy(audioRef).orElse(null);
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
            } finally {
                if (internalAudio != null) internalAudio.close();
            }
        } catch (Exception e) {
            log.warn("Photo+music mux error: {}", e.getMessage());
            return null;
        } finally {
            deleteQuietly(output);
            deleteQuietly(downloadedAudio);
        }
    }

    /** Run ffmpeg to loop the image over the trimmed audio, producing an iOS-safe H.264/AAC MP4. */
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
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Photo+music mux timed out after {}s", TIMEOUT_SECONDS);
                return false;
            }
            if (process.exitValue() != 0) {
                log.warn("Photo+music mux ffmpeg exited {}", process.exitValue());
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

    /** Download an external audio URL to a temp file for ffmpeg (avoids relying on ffmpeg's network/TLS). */
    private Path downloadToTemp(String url) throws java.io.IOException {
        Path tmp = Files.createTempFile("talkme-music-", ".audio");
        try (InputStream in = URI.create(url).toURL().openStream()) {
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
