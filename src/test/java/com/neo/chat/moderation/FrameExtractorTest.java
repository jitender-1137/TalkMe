package com.neo.chat.moderation;

import com.neo.chat.util.FfmpegSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link FrameExtractor}. It shells out to ffmpeg via {@link FfmpegSupport},
 * so the extractor binary is stubbed: a fake shell script that produces .jpg files exercises
 * the happy path, a nonexistent binary exercises the fail-soft (never-throw) path, and
 * {@code cleanup} is verified against real temp files.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FrameExtractor (unit)")
class FrameExtractorTest {

    @Mock
    private FfmpegSupport ffmpeg;

    private FrameExtractor extractor;

    @TempDir
    Path tmp;

    @BeforeEach
    void setUp() {
        extractor = new FrameExtractor(ffmpeg);
    }

    @Nested
    @DisplayName("extract")
    class Extract {

        /**
         * Writes a POSIX shell script that mimics ffmpeg: it derives the output directory
         * from the last CLI argument (the {@code …-%03d.jpg} pattern) and drops two jpgs there.
         */
        private Path fakeFfmpegProducingFrames() throws IOException {
            Path script = tmp.resolve("fake-ffmpeg.sh");
            Files.writeString(script, """
                    #!/bin/sh
                    last=""
                    for a in "$@"; do last="$a"; done
                    dir=$(dirname "$last")
                    : > "$dir/frame-001.jpg"
                    : > "$dir/frame-002.jpg"
                    """);
            Files.setPosixFilePermissions(script, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
            return script;
        }

        @Test
        @DisabledOnOs(OS.WINDOWS)
        @DisplayName("returns the .jpg frames the extractor produced")
        void returnsProducedFrames() throws IOException {
            when(ffmpeg.path()).thenReturn(fakeFfmpegProducingFrames().toString());
            Path video = Files.writeString(tmp.resolve("clip.mp4"), "fakevideo");

            List<Path> frames = extractor.extract(video);

            try {
                assertThat(frames).hasSize(2);
                assertThat(frames).allSatisfy(f -> assertThat(f.toString()).endsWith(".jpg"));
                assertThat(frames).allSatisfy(f -> assertThat(Files.exists(f)).isTrue());
            } finally {
                extractor.cleanup(frames);
            }
        }

        @Test
        @DisplayName("returns an empty list (never throws) when the binary cannot be launched")
        void emptyWhenBinaryMissing() {
            when(ffmpeg.path()).thenReturn(tmp.resolve("no-such-ffmpeg-binary").toString());
            Path video = tmp.resolve("clip.mp4");

            List<Path> frames = extractor.extract(video);

            assertThat(frames).isEmpty();
            verify(ffmpeg).path();
        }

        @Test
        @DisabledOnOs(OS.WINDOWS)
        @DisplayName("returns empty when the process succeeds but produces no jpg")
        void emptyWhenNoFramesProduced() {
            when(ffmpeg.path()).thenReturn("/bin/echo"); // exits 0, writes nothing
            Path video = tmp.resolve("clip.mp4");

            assertThat(extractor.extract(video)).isEmpty();
        }
    }

    @Nested
    @DisplayName("cleanup")
    class Cleanup {

        @Test
        @DisplayName("deletes each frame and its parent directory")
        void deletesFramesAndParent() throws IOException {
            Path dir = Files.createTempDirectory(tmp, "frames-");
            Path a = Files.writeString(dir.resolve("a.jpg"), "x");
            Path b = Files.writeString(dir.resolve("b.jpg"), "y");

            // Both frames share one parent; deleting them empties it so the parent goes too.
            extractor.cleanup(List.of(a, b));

            assertThat(Files.exists(a)).isFalse();
            assertThat(Files.exists(b)).isFalse();
            assertThat(Files.exists(dir)).isFalse();
        }

        @Test
        @DisplayName("keeps a non-empty parent directory")
        void keepsNonEmptyParent() throws IOException {
            Path dir = Files.createTempDirectory(tmp, "frames-");
            Path a = Files.writeString(dir.resolve("a.jpg"), "x");
            Files.writeString(dir.resolve("keep.txt"), "z"); // makes the dir non-empty

            extractor.cleanup(List.of(a));

            assertThat(Files.exists(a)).isFalse();
            assertThat(Files.exists(dir)).as("parent with remaining files is not deleted").isTrue();
        }

        @Test
        @DisplayName("is a no-op for a missing path (never throws)")
        void noopForMissing() {
            extractor.cleanup(List.of(tmp.resolve("ghost.jpg")));
        }

        @Test
        @DisplayName("is a no-op for an empty list")
        void noopForEmpty() {
            extractor.cleanup(new ArrayList<>());
        }
    }
}
