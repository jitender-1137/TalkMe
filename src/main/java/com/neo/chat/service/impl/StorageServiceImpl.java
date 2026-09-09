package com.neo.chat.service.impl;

import com.neo.chat.util.FfmpegSupport;
import com.neo.chat.exception.FileStorageException;
import com.neo.chat.service.StorageService;
import com.neo.chat.storage.MediaStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Stores uploads via the pluggable {@link MediaStorage} backend (filesystem in
 * local/dev, OCI Object Storage in prod). The video transcode pipeline is unchanged —
 * ffmpeg produces the same compact H.264/AAC MP4 as before, into a temp file, and only
 * the finished bytes are handed to the storage backend. The returned reference shape
 * ({@code <MEDIA_ROOT>/<subdir>/<uuid>.<ext>}) is identical to the original.
 */
@Slf4j
@Service
public class StorageServiceImpl implements StorageService {

    private final MediaStorage mediaStorage;

    /**
     * Resolves the ffmpeg binary (bundled with the app by default).
     */
    private final FfmpegSupport ffmpeg;

    /**
     * Max wall-clock time for a single transcode before we give up.
     */
    private static final long TRANSCODE_TIMEOUT_MINUTES = 5;

    public StorageServiceImpl(MediaStorage mediaStorage, FfmpegSupport ffmpeg) {
        this.mediaStorage = mediaStorage;
        this.ffmpeg = ffmpeg;
    }

    /**
     * Stores the upload at the media root (no subfolder); delegates to the three-arg overload.
     *
     * @param file the uploaded file
     * @param type category hint ("video" forces the transcode path)
     * @return the storage reference of the stored object
     * @throws com.neo.chat.exception.FileStorageException if the file cannot be stored
     */
    @Override
    public String storeFile(MultipartFile file, String type) {
        return storeFile(file, type, "");
    }

    /**
     * Stores the upload under the normalized {@code subdir} with a random UUID filename. Videos
     * (by {@code type} or content-type) go through the compress-then-store path; other files are
     * staged to a temp file and handed to the backend as-is. The temp file is always cleaned up.
     *
     * @param file   the uploaded file
     * @param type   category hint ("video" forces the transcode path)
     * @param subdir target subdirectory; normalized and rejected if it attempts traversal; blank ⇒ root
     * @return the storage reference of the stored object
     * @throws com.neo.chat.exception.FileStorageException on invalid subdir or an I/O failure while storing
     */
    @Override
    public String storeFile(MultipartFile file, String type, String subdir) {
        String cleanSubdir = normalizeSubdir(subdir);

        String originalFileName = file.getOriginalFilename();
        String extension = safeExtension(originalFileName);

        String contentType = file.getContentType();
        boolean isVideo = "video".equalsIgnoreCase(type)
                || (contentType != null && contentType.startsWith("video/"));

        if (isVideo) {
            return storeCompressedVideo(file, extension, cleanSubdir);
        }

        // Non-video: stage to a temp file, then hand to the storage backend as-is
        // (images are already compressed on the client).
        Path temp = null;
        try {
            temp = Files.createTempFile("talkme-upload-", extension.isEmpty() ? ".tmp" : extension);
            Files.copy(file.getInputStream(), temp, StandardCopyOption.REPLACE_EXISTING);
            String key = buildKey(cleanSubdir, UUID.randomUUID() + extension);
            String ref = mediaStorage.store(temp, key, contentType);
            log.info("File stored successfully: {}", ref);
            return ref;
        } catch (IOException e) {
            throw new FileStorageException("Could not store file: " + e);
        } finally {
            deleteQuietly(temp);
        }
    }

    /**
     * Store a video, transcoding it to a compact H.264/AAC MP4 with ffmpeg — the exact
     * same command as before. The upload is written to a temp file, then ffmpeg
     * downscales to ≤720p and re-encodes. If ffmpeg is unavailable, fails, times out, or
     * the result is not actually smaller, the original is stored untouched — uploads
     * must never fail because compression failed.
     */
    private String storeCompressedVideo(MultipartFile file, String extension, String subdir) {
        extension = safeExtension(extension);
        Path tempInput = null;
        Path compressed = null;
        try {
            tempInput = Files.createTempFile("talkme-upload-", extension.isEmpty() ? ".tmp" : extension);
            Files.copy(file.getInputStream(), tempInput, StandardCopyOption.REPLACE_EXISTING);
            long originalSize = Files.size(tempInput);

            compressed = Files.createTempFile("talkme-transcode-", ".mp4");
            boolean transcode = transcodeVideo(tempInput, compressed);

            if (transcode
                    && Files.exists(compressed)
                    && Files.size(compressed) > 0
                    && Files.size(compressed) < originalSize) {
                long compressedSize = Files.size(compressed);
                log.info("Video compressed: {} bytes -> {} bytes ({}% smaller)",
                        originalSize, compressedSize,
                        Math.round((1 - (double) compressedSize / originalSize) * 100));
                String key = buildKey(subdir, UUID.randomUUID() + ".mp4");
                return mediaStorage.store(compressed, key, "video/mp4");
            }

            // Compression unavailable or not beneficial → keep the original.
            String key = buildKey(subdir, UUID.randomUUID() + extension);
            log.info("Video stored without compression");
            return mediaStorage.store(tempInput, key, file.getContentType());
        } catch (IOException e) {
            throw new FileStorageException("Could not store video file: " + e);
        } finally {
            deleteQuietly(tempInput);
            deleteQuietly(compressed);
        }
    }

    /**
     * Normalize the subdir: trim, drop leading/trailing slashes, reject traversal.
     */
    private String normalizeSubdir(String subdir) {
        if (subdir == null || subdir.isBlank()) return "";
        String s = subdir.trim().replaceAll("^/+", "").replaceAll("/+$", "");
        if (s.contains("..") || s.contains("\\")) {
            throw new FileStorageException("Invalid media subdirectory: " + subdir);
        }
        return s;
    }

    private String buildKey(String subdir, String fileName) {
        return subdir.isEmpty() ? fileName : subdir + "/" + fileName;
    }

    private void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best-effort temp cleanup
        }
    }

    /**
     * A safe stored file extension. SECURITY: the original filename is client-controlled, so a
     * scriptable extension ({@code .html}, {@code .svg}, {@code .xhtml}, {@code .js}, …) must never
     * become the stored object's extension — the media serve endpoint derives the response
     * Content-Type from it, and a text/html or image/svg+xml object on our origin is a stored-XSS
     * primitive. Only a small allow-list of real media/document extensions is kept; anything else
     * (including no extension) is stored as {@code .bin} (served as application/octet-stream).
     *
     * @param nameOrExt the original filename or an extension (with or without a leading dot)
     * @return a safe extension beginning with '.', never a scriptable one
     */
    static String safeExtension(String nameOrExt) {
        if (nameOrExt == null) return ".bin";
        int dot = nameOrExt.lastIndexOf('.');
        String ext = dot >= 0 ? nameOrExt.substring(dot + 1) : nameOrExt;
        ext = ext.trim().toLowerCase(java.util.Locale.ROOT);
        return SAFE_EXTENSIONS.contains(ext) ? "." + ext : ".bin";
    }

    private static final java.util.Set<String> SAFE_EXTENSIONS = java.util.Set.of(
            // images
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif",
            // video
            "mp4", "mov", "webm", "mkv", "avi", "m4v", "3gp",
            // audio
            "mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac",
            // documents
            "pdf");

    /**
     * Run ffmpeg to transcode {@code input} into a web-friendly MP4 at {@code output}.
     * Returns true only on a clean (exit code 0) completion within the timeout.
     */
    private boolean transcodeVideo(Path input, Path output) {
        // -vf scale=-2:'min(720,ih)'  → cap height at 720p, width auto-even, never upscale.
        // libopenh264 (bundled, cross-platform) — libx264 isn't in the bundled build.
        // openh264 targets a bitrate rather than -crf/-preset; ~1.5 Mbps @ ≤720p is a
        // good size/quality balance. +faststart moves the moov atom up for instant play.
        List<String> command = List.of(
                ffmpeg.path(), "-y", "-nostdin",
                // SECURITY: the input is attacker-supplied bytes. Restrict ffmpeg's demuxer to
                // local file/pipe protocols so a crafted playlist (HLS/concat) cannot make ffmpeg
                // fetch http(s)/file segments (SSRF / local-file read). Strip all input metadata
                // (GPS/device/timestamps) from the transcoded output (privacy).
                "-protocol_whitelist", "file,pipe",
                "-i", input.toString(),
                "-map_metadata", "-1",
                "-vf", "scale=-2:'min(720,ih)'",
                "-c:v", "libopenh264",
                "-b:v", "1500k",
                "-pix_fmt", "yuv420p",
                "-c:a", "aac",
                "-b:a", "128k",
                "-movflags", "+faststart",
                output.toString()
        );

        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();

            boolean finished = process.waitFor(TRANSCODE_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Video transcode timed out after {} min; using original.", TRANSCODE_TIMEOUT_MINUTES);
                return false;
            }
            int exit = process.exitValue();
            if (exit != 0) {
                log.warn("ffmpeg exited with code {}; using original.", exit);
                return false;
            }
            return true;
        } catch (IOException e) {
            // ffmpeg not installed / not on PATH — degrade gracefully.
            log.warn("ffmpeg not available ('{}'); storing video uncompressed. {}", ffmpeg.path(), e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            log.warn("Video transcode interrupted; using original.");
            return false;
        }
    }
}
