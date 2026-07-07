package com.chat.talkMe.service.impl;

import com.chat.talkMe.exception.FileStorageException;
import com.chat.talkMe.service.StorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class StorageServiceImpl implements StorageService {

    private static final Path STORAGE_PATH = Paths.get("/opt/media/talkMe");

    /** Resolves the ffmpeg binary (bundled with the app by default). */
    private final FfmpegSupport ffmpeg;

    /** Max wall-clock time for a single transcode before we give up. */
    private static final long TRANSCODE_TIMEOUT_MINUTES = 5;

    public StorageServiceImpl(FfmpegSupport ffmpeg) {
        this.ffmpeg = ffmpeg;
        try {
            Files.createDirectories(STORAGE_PATH);
        } catch (IOException e) {
            throw new FileStorageException(
                    "Could not create storage directory: " + STORAGE_PATH + e);
        }
    }

    @Override
    public String storeFile(MultipartFile file, String type) {
        return storeFile(file, type, "");
    }

    @Override
    public String storeFile(MultipartFile file, String type, String subdir) {
        Path targetDir = resolveTargetDir(subdir);

        String originalFileName = file.getOriginalFilename();
        String extension = "";
        if (originalFileName != null && originalFileName.contains(".")) {
            extension = originalFileName.substring(originalFileName.lastIndexOf("."));
        }

        String contentType = file.getContentType();
        boolean isVideo = "video".equalsIgnoreCase(type)
                || (contentType != null && contentType.startsWith("video/"));

        if (isVideo) {
            return storeCompressedVideo(file, extension, targetDir);
        }

        // Non-video: store as-is (images are already compressed on the client).
        String fileName = UUID.randomUUID() + extension;
        try {
            Path targetLocation = targetDir.resolve(fileName);
            Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);
            log.info("File stored successfully: {}", targetLocation);
            return targetLocation.toString();
        } catch (IOException e) {
            throw new FileStorageException("Could not store file " + fileName + e);
        }
    }

    /**
     * Resolve a relative subdir under the media root, creating it on demand and
     * guaranteeing the result can never escape the root (defense-in-depth — callers
     * already build the subdir from a fixed category + a validated UUID). A blank
     * subdir stores at the root.
     */
    private Path resolveTargetDir(String subdir) {
        Path dir = (subdir == null || subdir.isBlank())
                ? STORAGE_PATH
                : STORAGE_PATH.resolve(subdir).normalize();
        if (!dir.startsWith(STORAGE_PATH)) {
            throw new FileStorageException("Invalid media subdirectory: " + subdir);
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new FileStorageException("Could not create media directory: " + dir + " " + e);
        }
        return dir;
    }

    /**
     * Store a video, transcoding it to a compact H.264/AAC MP4 with ffmpeg.
     *
     * The upload is first written to a temp file, then ffmpeg downscales it to
     * ≤720p and re-encodes at CRF 28 (good quality / much smaller). If ffmpeg is
     * unavailable, fails, times out, or the result is not actually smaller, we
     * fall back to storing the original file untouched — uploads must never fail
     * because compression failed.
     */
    private String storeCompressedVideo(MultipartFile file, String extension, Path targetDir) {
        Path tempInput = null;
        try {
            tempInput = Files.createTempFile("talkme-upload-", extension.isEmpty() ? ".tmp" : extension);
            Files.copy(file.getInputStream(), tempInput, StandardCopyOption.REPLACE_EXISTING);
            long originalSize = Files.size(tempInput);

            Path compressedTarget = targetDir.resolve(UUID.randomUUID() + ".mp4");
            boolean transcoded = transcodeVideo(tempInput, compressedTarget);

            if (transcoded
                    && Files.exists(compressedTarget)
                    && Files.size(compressedTarget) > 0
                    && Files.size(compressedTarget) < originalSize) {
                long compressedSize = Files.size(compressedTarget);
                log.info("Video compressed: {} bytes -> {} bytes ({}% smaller)",
                        originalSize, compressedSize,
                        Math.round((1 - (double) compressedSize / originalSize) * 100));
                return compressedTarget.toString();
            }

            // Compression unavailable or not beneficial → keep the original.
            Files.deleteIfExists(compressedTarget);
            Path originalTarget = targetDir.resolve(UUID.randomUUID() + extension);
            Files.move(tempInput, originalTarget, StandardCopyOption.REPLACE_EXISTING);
            tempInput = null; // moved
            log.info("Video stored without compression: {}", originalTarget);
            return originalTarget.toString();

        } catch (IOException e) {
            throw new FileStorageException("Could not store video file: " + e);
        } finally {
            if (tempInput != null) {
                try {
                    Files.deleteIfExists(tempInput);
                } catch (IOException ignored) {
                    // best-effort temp cleanup
                }
            }
        }
    }

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
                ffmpeg.path(), "-y",
                "-i", input.toString(),
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
            if (process != null) process.destroyForcibly();
            log.warn("Video transcode interrupted; using original.");
            return false;
        }
    }
}
