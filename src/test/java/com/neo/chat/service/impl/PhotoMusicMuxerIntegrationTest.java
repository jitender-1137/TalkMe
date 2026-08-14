package com.neo.chat.service.impl;

import com.neo.chat.storage.MediaStorage;
import com.neo.chat.storage.StorageProperties;
import org.bytedeco.ffmpeg.ffmpeg;
import org.bytedeco.javacpp.Loader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * REAL end-to-end integration test for {@link PhotoMusicMuxer} — runs the bundled
 * ffmpeg (with the OpenH264 native lib) to prove the image+audio → MP4 pipeline
 * actually produces a valid, playable, exactly-20s H.264/AAC MP4.
 *
 * <p>This is the test the feature was missing: the existing service tests MOCK the
 * muxer, so the runtime OpenH264 defect (`-c:v libopenh264` with no native lib →
 * silent failure) shipped unnoticed. Here we execute the command for real.
 *
 * <p>Gracefully skips (JUnit assumption) if the bundled ffmpeg can't be resolved on
 * the running platform, so it never breaks CI on an unsupported arch.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("PhotoMusicMuxer (real ffmpeg integration)")
class PhotoMusicMuxerIntegrationTest {

    private String ffmpeg;
    private Path workDir;
    private Path imagePng;
    private Path audio25s;
    private Path audio10s;
    private PhotoMusicMuxer muxer;

    @BeforeAll
    void setUp() throws Exception {
        ffmpeg = resolveFfmpeg();
        assumeThat(ffmpeg).as("bundled ffmpeg must resolve on this platform").isNotNull();

        workDir = Files.createTempDirectory("muxer-it-");
        imagePng = workDir.resolve("image.png");
        audio25s = workDir.resolve("audio25.wav");
        audio10s = workDir.resolve("audio10.wav");

        // Generate deterministic inputs with ffmpeg's built-in sources (no fixtures).
        run(List.of(ffmpeg, "-y", "-f", "lavfi", "-i", "color=c=blue:s=320x240",
                "-frames:v", "1", imagePng.toString()), 30);
        run(List.of(ffmpeg, "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=25",
                "-ac", "1", audio25s.toString()), 30);
        run(List.of(ffmpeg, "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=10",
                "-ac", "1", audio10s.toString()), 30);

        assumeThat(Files.exists(imagePng) && Files.size(imagePng) > 0).isTrue();
        assumeThat(Files.exists(audio25s) && Files.size(audio25s) > 0).isTrue();

        FfmpegSupport support = new FfmpegSupport();
        // Not a Spring context — pin the resolver to the binary we already found.
        var f = FfmpegSupport.class.getDeclaredField("resolved");
        f.setAccessible(true);
        f.set(support, ffmpeg);

        StorageProperties props = new StorageProperties();
        muxer = new PhotoMusicMuxer(support, new LocalFsStorage(workDir), props);
    }

    @AfterAll
    void tearDown() {
        deleteRecursively(workDir);
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("produces a valid 20s H.264/AAC MP4 from image + audio window")
    void producesValidMp4() throws Exception {
        String ref = muxer.muxPhotoWithMusic(imagePng.toString(), audio25s.toString(), 5, 20);

        assertThat(ref).as("mux must return a stored reference").isNotNull();
        Path out = Path.of(ref);
        assertThat(Files.exists(out)).isTrue();
        assertThat(Files.size(out)).isGreaterThan(1024L);

        // Probe the output: exactly ~20s, H.264 video, AAC audio, mp4 container.
        String info = probe(out);
        assertThat(info).contains("Duration: 00:00:20");
        assertThat(info).containsIgnoringCase("h264");
        assertThat(info).containsIgnoringCase("aac");
    }

    // ── Failure scenarios ───────────────────────────────────────────────────────

    @Test
    @DisplayName("returns null when the image cannot be resolved")
    void nullWhenImageMissing() {
        String ref = muxer.muxPhotoWithMusic(workDir.resolve("nope.png").toString(),
                audio25s.toString(), 0, 20);
        assertThat(ref).isNull();
    }

    @Test
    @DisplayName("returns null when the audio cannot be resolved")
    void nullWhenAudioMissing() {
        String ref = muxer.muxPhotoWithMusic(imagePng.toString(),
                workDir.resolve("nope.wav").toString(), 0, 20);
        assertThat(ref).isNull();
    }

    @Test
    @DisplayName("rejects a start offset beyond the audio duration")
    void nullWhenStartBeyondDuration() {
        // 25s audio, start at 40s → out of range → rejected.
        String ref = muxer.muxPhotoWithMusic(imagePng.toString(), audio25s.toString(), 40, 20);
        assertThat(ref).isNull();
    }

    @Test
    @DisplayName("rejects audio shorter than the requested clip")
    void nullWhenAudioShorterThanClip() {
        // 10s audio, 20s clip → can't make an exact 20s clip → rejected.
        String ref = muxer.muxPhotoWithMusic(imagePng.toString(), audio10s.toString(), 0, 20);
        assertThat(ref).isNull();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static String resolveFfmpeg() {
        try {
            try {
                Loader.load(Class.forName("org.bytedeco.openh264.global.openh264"));
            } catch (Throwable ignored) {
                // ffmpeg preset resolves openh264 transitively
            }
            return Loader.load(ffmpeg.class);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Run a command, drain stderr, fail the assumption if it can't complete.
     */
    private static String run(List<String> cmd, int timeoutSec) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        StringBuilder err = new StringBuilder();
        Thread drain = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) err.append(line).append('\n');
            } catch (IOException ignored) {
            }
        });
        drain.setDaemon(true);
        drain.start();
        p.waitFor(timeoutSec, TimeUnit.SECONDS);
        p.destroyForcibly();
        drain.join(2000);
        return err.toString();
    }

    /**
     * `ffmpeg -i <file>` prints media info (Duration + codecs) to stderr.
     */
    private String probe(Path file) throws Exception {
        return run(List.of(ffmpeg, "-hide_banner", "-i", file.toString()), 20);
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    /**
     * Minimal filesystem-backed {@link MediaStorage} for the test: references ARE
     * absolute local paths. {@code store} copies the muxer's temp output to a stable
     * file (before the muxer deletes the temp) and returns that path as the reference.
     */
    private static final class LocalFsStorage implements MediaStorage {
        private final Path outDir;

        LocalFsStorage(Path outDir) {
            this.outDir = outDir;
        }

        @Override
        public String store(Path source, String key, String contentType) {
            try {
                Path dest = outDir.resolve("out-" + System.nanoTime() + ".mp4");
                Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
                return dest.toString();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public Optional<MediaContent> open(String reference) {
            return Optional.empty();
        }

        @Override
        public Optional<LocalFile> localCopy(String reference) {
            Path path = Path.of(reference);
            return Optional.of(new LocalFile() {
                @Override
                public Path path() {
                    return path;
                }

                @Override
                public void close() {
                    // in-place file — nothing to delete
                }
            });
        }

        @Override
        public void delete(String reference) {
            // no-op
        }

        @Override
        public List<StoredObject> list(String prefix) {
            return List.of();
        }
    }
}
