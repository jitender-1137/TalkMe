package com.chat.talkMe.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.ffmpeg;
import org.bytedeco.javacpp.Loader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
 */
@Slf4j
@Component
public class FfmpegSupport {

    @Value("${media.ffmpeg-path:ffmpeg}")
    private String configuredPath;

    private volatile String resolved;

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
}
