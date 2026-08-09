package com.chat.talkMe.service.impl;

import com.chat.talkMe.exception.FileStorageException;
import com.chat.talkMe.storage.MediaStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link StorageServiceImpl}. The ffmpeg transcode is driven to
 * its graceful-degradation branch by pointing {@link FfmpegSupport#path()} at a non-existent
 * binary (ProcessBuilder.start() → IOException → returns false → the ORIGINAL bytes are stored),
 * which keeps the video path deterministic without a real ffmpeg. The "compressed result is
 * smaller" branch is intentionally not unit-testable without a real transcoder and is left N/A.
 *
 * <p>Covered: non-video staging, extension derivation (present / absent / null filename),
 * subdir normalisation + traversal rejection (TM_170), key shape, contentType passthrough,
 * video passthrough-when-compression-unavailable, and IOException wrapping for both paths.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StorageServiceImpl (unit)")
class StorageServiceImplTest {

    @Mock
    private MediaStorage mediaStorage;
    @Mock
    private FfmpegSupport ffmpeg;
    @Mock
    private MultipartFile file;

    private StorageServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new StorageServiceImpl(mediaStorage, ffmpeg);
    }

    private void streams(byte[] bytes) throws IOException {
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(bytes));
    }

    private void stubStore() {
        // store(...) returns the reference; echo the key back so the returned ref is inspectable.
        lenient().when(mediaStorage.store(any(Path.class), anyString(), any()))
                .thenAnswer(inv -> "/media/" + inv.getArgument(1, String.class));
    }

    @Nested
    @DisplayName("storeFile (non-video)")
    class StoreNonVideo {

        @Test
        @DisplayName("nominal image → stages, stores under a uuid+ext key, passes contentType through")
        void nominalImage() throws IOException {
            streams("img-bytes".getBytes());
            when(file.getOriginalFilename()).thenReturn("photo.jpg");
            when(file.getContentType()).thenReturn("image/jpeg");
            stubStore();

            String ref = service.storeFile(file, "image");

            ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> ct = ArgumentCaptor.forClass(String.class);
            verify(mediaStorage).store(any(Path.class), key.capture(), ct.capture());
            assertThat(key.getValue()).endsWith(".jpg").doesNotContain("/");
            assertThat(ct.getValue()).isEqualTo("image/jpeg");
            assertThat(ref).isEqualTo("/media/" + key.getValue());
        }

        @Test
        @DisplayName("subdir → key is prefixed with the normalised subdir")
        void withSubdir() throws IOException {
            streams("x".getBytes());
            when(file.getOriginalFilename()).thenReturn("a.png");
            when(file.getContentType()).thenReturn("image/png");
            stubStore();

            service.storeFile(file, "image", "posts/42");

            ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(mediaStorage).store(any(Path.class), key.capture(), eq("image/png"));
            assertThat(key.getValue()).startsWith("posts/42/").endsWith(".png");
        }

        @Test
        @DisplayName("leading/trailing slashes on the subdir are stripped")
        void subdirSlashesStripped() throws IOException {
            streams("x".getBytes());
            when(file.getOriginalFilename()).thenReturn("a.png");
            when(file.getContentType()).thenReturn("image/png");
            stubStore();

            service.storeFile(file, "image", "/posts/42/");

            ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(mediaStorage).store(any(Path.class), key.capture(), any());
            assertThat(key.getValue()).startsWith("posts/42/").doesNotContain("//");
        }

        @Test
        @DisplayName("filename without an extension → key has no extension suffix")
        void noExtension() throws IOException {
            streams("x".getBytes());
            when(file.getOriginalFilename()).thenReturn("noext");
            when(file.getContentType()).thenReturn("application/octet-stream");
            stubStore();

            service.storeFile(file, "file");

            ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(mediaStorage).store(any(Path.class), key.capture(), eq("application/octet-stream"));
            assertThat(key.getValue()).doesNotContain(".");
        }

        @Test
        @DisplayName("null original filename → no extension, no NPE")
        void nullFilename() throws IOException {
            streams("x".getBytes());
            when(file.getOriginalFilename()).thenReturn(null);
            when(file.getContentType()).thenReturn("image/webp");
            stubStore();

            service.storeFile(file, "image");

            verify(mediaStorage).store(any(Path.class), anyString(), eq("image/webp"));
        }

        @Test
        @DisplayName("null contentType (non-video) → stored with a null contentType, still non-video path")
        void nullContentType() throws IOException {
            streams("x".getBytes());
            when(file.getOriginalFilename()).thenReturn("a.bin");
            when(file.getContentType()).thenReturn(null);
            stubStore();

            service.storeFile(file, "file");

            verify(mediaStorage).store(any(Path.class), anyString(), eq((String) null));
        }

        @Test
        @DisplayName("IOException reading the upload → FileStorageException (TM_170), nothing stored")
        void ioErrorWrapped() throws IOException {
            when(file.getOriginalFilename()).thenReturn("a.jpg");
            when(file.getContentType()).thenReturn("image/jpeg");
            when(file.getInputStream()).thenThrow(new IOException("boom"));

            assertThatThrownBy(() -> service.storeFile(file, "image"))
                    .isInstanceOfSatisfying(FileStorageException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_170"));
            verify(mediaStorage, never()).store(any(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("subdir normalisation")
    class SubdirNormalisation {

        @Test
        @DisplayName("path traversal (..) → FileStorageException (TM_170) before any storage")
        void traversalRejected() {
            assertThatThrownBy(() -> service.storeFile(file, "image", "../secrets"))
                    .isInstanceOfSatisfying(FileStorageException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_170"));
            verifyNoInteractions(mediaStorage);
        }

        @Test
        @DisplayName("backslash in subdir → FileStorageException (TM_170)")
        void backslashRejected() {
            assertThatThrownBy(() -> service.storeFile(file, "image", "foo\\bar"))
                    .isInstanceOfSatisfying(FileStorageException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_170"));
            verifyNoInteractions(mediaStorage);
        }

        @Test
        @DisplayName("blank subdir → stored at the root (no prefix)")
        void blankSubdirIsRoot() throws IOException {
            streams("x".getBytes());
            when(file.getOriginalFilename()).thenReturn("a.png");
            when(file.getContentType()).thenReturn("image/png");
            stubStore();

            service.storeFile(file, "image", "   ");

            ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(mediaStorage).store(any(Path.class), key.capture(), any());
            assertThat(key.getValue()).doesNotContain("/");
        }

        @Test
        @DisplayName("null subdir → normalised to root (line 124 subdir == null)")
        void nullSubdirIsRoot() throws IOException {
            streams("x".getBytes());
            when(file.getOriginalFilename()).thenReturn("a.png");
            when(file.getContentType()).thenReturn("image/png");
            stubStore();

            service.storeFile(file, "image", null);

            ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(mediaStorage).store(any(Path.class), key.capture(), any());
            assertThat(key.getValue()).doesNotContain("/");
        }
    }

    @Nested
    @DisplayName("storeFile (video)")
    class StoreVideo {

        @Test
        @DisplayName("type=video, ffmpeg unavailable → original stored untouched with its own contentType")
        void videoByTypeStoresOriginalWhenCompressionUnavailable() throws IOException {
            streams("video-bytes".getBytes());
            when(file.getOriginalFilename()).thenReturn("clip.mov");
            when(file.getContentType()).thenReturn("video/quicktime");
            when(ffmpeg.path()).thenReturn("definitely-not-a-real-ffmpeg-binary-xyz");
            stubStore();

            String ref = service.storeFile(file, "video");

            ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> ct = ArgumentCaptor.forClass(String.class);
            verify(mediaStorage).store(any(Path.class), key.capture(), ct.capture());
            assertThat(key.getValue()).endsWith(".mov"); // original extension retained
            assertThat(ct.getValue()).isEqualTo("video/quicktime");
            assertThat(ref).isNotNull();
        }

        @Test
        @DisplayName("video detected via contentType (type=image) → routed to the video path")
        void videoByContentType() throws IOException {
            streams("v".getBytes());
            when(file.getOriginalFilename()).thenReturn("a.mp4");
            when(file.getContentType()).thenReturn("video/mp4");
            when(ffmpeg.path()).thenReturn("definitely-not-a-real-ffmpeg-binary-xyz");
            stubStore();

            service.storeFile(file, "image", "conversations/7");

            ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(mediaStorage).store(any(Path.class), key.capture(), eq("video/mp4"));
            assertThat(key.getValue()).startsWith("conversations/7/").endsWith(".mp4");
        }

        @Test
        @DisplayName("IOException reading the upload → FileStorageException (TM_170) from the video path")
        void videoIoErrorWrapped() throws IOException {
            when(file.getOriginalFilename()).thenReturn("a.mp4");
            when(file.getContentType()).thenReturn("video/mp4");
            when(file.getInputStream()).thenThrow(new IOException("boom"));

            assertThatThrownBy(() -> service.storeFile(file, "video"))
                    .isInstanceOfSatisfying(FileStorageException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_170"));
            verify(mediaStorage, never()).store(any(), anyString(), any());
        }

        @Test
        @DisplayName("video with no filename extension → temp staged as .tmp (line 91), original stored")
        void videoWithoutExtension() throws IOException {
            // originalFilename has no dot → extension is empty → storeCompressedVideo's temp-file
            // name falls back to ".tmp" (line 91). ffmpeg unavailable → original bytes stored.
            streams("v".getBytes());
            when(file.getOriginalFilename()).thenReturn("clipnoext");
            when(file.getContentType()).thenReturn("video/mp4");
            when(ffmpeg.path()).thenReturn("definitely-not-a-real-ffmpeg-binary-xyz");
            stubStore();

            service.storeFile(file, "video");

            ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(mediaStorage).store(any(Path.class), key.capture(), eq("video/mp4"));
            // empty extension → uuid-only key, no dot suffix
            assertThat(key.getValue()).doesNotContain(".");
        }
    }
}
