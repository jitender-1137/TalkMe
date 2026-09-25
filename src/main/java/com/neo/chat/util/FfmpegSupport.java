package com.neo.chat.util;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.ffmpeg;
import org.bytedeco.javacpp.Loader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Resolves the ffmpeg executable to use across the app (muxing, video transcoding,
 * frame moderation).
 * <p>
 * By default, it returns the ffmpeg binary BUNDLED with the app via Bytedeco
 * ({@code org.bytedeco:ffmpeg}) — extracted to the JavaCPP cache on first use — so
 * ffmpeg works everywhere with no manual installation. An explicit
 * {@code media.ffmpeg-path} (anything other than the bare "ffmpeg" default) always
 * wins, letting an operator point at a system ffmpeg. If the bundled binary can't
 * load for some reason, it falls back to "ffmpeg" on PATH.
 *
 * <p>Lives in the {@code util} slice, not {@code service}: it is a dependency-free ffmpeg
 * process wrapper (infrastructure). Keeping it low lets {@code moderation.FrameExtractor}
 * use it without a {@code moderation -> service} package cycle (BootUI ARCH-PKG-001).
 */
@Slf4j
@Component
public class FfmpegSupport {

    private final String configuredPath;

    private volatile String resolved;
    private volatile List<String> h264Args;

    public FfmpegSupport(@Value("${media.ffmpeg-path:ffmpeg}") String configuredPath) {
        this.configuredPath = configuredPath;
    }

    /**
     * Resolves the ffmpeg executable path, caching the result (double-checked locking). An operator-set
     * {@code media.ffmpeg-path} wins; otherwise the Bytedeco-bundled binary is loaded, falling back to
     * "ffmpeg" on PATH if that fails.
     *
     * @return the ffmpeg executable path or command to invoke
     */
    public String path() {
        String cached = resolved;
        if (cached != null) return cached;
        synchronized (this) {
            if (resolved != null) return resolved;
            // An operator-provided path (not the bare default) takes precedence.
            if (configuredPath != null && !configuredPath.isBlank() && !"ffmpeg".equals(configuredPath)) {
                log.info("Using configured ffmpeg at {}", configuredPath);
                resolved = configuredPath;
                return resolved;
            }
            try {
                resolved = Loader.load(ffmpeg.class);
                log.info("Using bundled ffmpeg at {}", resolved);
            } catch (Throwable t) {
                log.warn("Bundled ffmpeg unavailable ({}); falling back to '{}' on PATH",
                        t.getMessage(), configuredPath);
                resolved = (configuredPath == null || configuredPath.isBlank()) ? "ffmpeg" : configuredPath;
            }
            return resolved;
        }
    }

    /**
     * Video-encoder arguments (codec + rate control) for an iOS-safe H.264/avc1 MP4, chosen
     * from what the resolved ffmpeg ACTUALLY provides — cached after the first probe.
     * <p>
     * A hardcoded encoder was the cause of photo+music muxing silently failing on some
     * servers: the bundled Bytedeco build ships {@code libopenh264}, but most distro ffmpeg
     * builds ship {@code libx264} instead and lack {@code libopenh264} (and vice versa). We
     * probe {@code ffmpeg -encoders} once and prefer libx264, then libopenh264, then a
     * universal {@code mpeg4} fallback so the mux never fails just because of the codec name.
     *
     * @return an immutable arg list starting with {@code -c:v <encoder>} plus its rate-control flags
     */
    public List<String> h264VideoArgs() {
        List<String> cached = h264Args;
        if (cached != null) return cached;
        synchronized (this) {
            if (h264Args != null) return h264Args;
            String encoders = probeEncoders();
            if (encoders.isBlank()) {
                // The probe couldn't even run ffmpeg (e.g. no ffmpeg installed yet). DON'T cache
                // this — otherwise installing ffmpeg later wouldn't take effect without a restart.
                // Return a safe default for this call and re-probe on the next one.
                log.warn("ffmpeg encoder probe returned nothing; deferring encoder choice (will retry)");
                return List.of("-c:v", "mpeg4", "-q:v", "5");
            }
            List<String> args;
            if (encoders.contains("libx264")) {
                // libx264: CRF rate control (widely available in distro builds).
                args = List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "23");
            } else if (encoders.contains("libopenh264")) {
                // libopenh264 (bundled Bytedeco build): target bitrate, not -crf/-preset.
                args = List.of("-c:v", "libopenh264", "-b:v", "2000k");
            } else {
                // ffmpeg exists but genuinely has no H.264 encoder — mpeg4 always exists, so
                // muxing still succeeds. This IS cached (a real, stable capability of this ffmpeg).
                log.warn("No H.264 encoder (libx264/libopenh264) available in ffmpeg; using mpeg4 fallback");
                args = List.of("-c:v", "mpeg4", "-q:v", "5");
            }
            log.info("Resolved H.264 video encoder args: {}", args);
            h264Args = args;
            return h264Args;
        }
    }

    /** Capture {@code ffmpeg -encoders} (stdout+stderr) so we can see which video encoders exist. */
    private String probeEncoders() {
        Process p = null;
        try {
            p = new ProcessBuilder(path(), "-hide_banner", "-encoders")
                    .redirectErrorStream(true)
                    .start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor(10, TimeUnit.SECONDS);
            return sb.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (Exception e) {
            log.warn("Could not probe ffmpeg encoders ({}): {}", path(), e.getMessage());
            return "";
        } finally {
            if (p != null) p.destroy();
        }
    }
}
