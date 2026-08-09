package com.chat.talkMe.util;

import com.chat.talkMe.exception.ServiceException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure unit test for {@link UploadValidator} — magic-byte content sniffing that rejects a
 * declared category whose real bytes don't match. Every {@code MultipartFile} is a Mockito
 * mock whose {@code getInputStream()} yields a FRESH stream on each call (the validator reads
 * the head once and, for SVG, the whole body again). Covers each format signature, the SVG
 * active-content scan, the audio-in-a-video-container allowance, and the mismatch/empty codes
 * TM_493 / TM_494 / TM_495.
 */
@DisplayName("UploadValidator (unit)")
class UploadValidatorTest {

    // ---- magic-byte fixtures ----
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0};
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] GIF = {0x47, 0x49, 0x46, 0x38, 0x39, 0x61};
    private static final byte[] BMP = {0x42, 0x4D, 0, 0, 0, 0};
    private static final byte[] WEBP = riff("WEBP");
    private static final byte[] AVI = riff("AVI ");
    private static final byte[] WAV = riff("WAVE");
    private static final byte[] WEBM = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0, 0, 0, 0};
    private static final byte[] MP3_ID3 = {0x49, 0x44, 0x33, 4, 0, 0, 0, 0};
    private static final byte[] MP3_SYNC = {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x00};
    private static final byte[] OGG = {0x4F, 0x67, 0x67, 0x53, 0, 0, 0, 0};
    private static final byte[] FLAC = {0x66, 0x4C, 0x61, 0x43, 0, 0, 0, 0};
    private static final byte[] PDF = {0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E};
    private static final byte[] HEIC = ftyp("heic");
    private static final byte[] AVIF = ftyp("avif");
    private static final byte[] M4A = ftyp("M4A ");
    private static final byte[] MP4_ISOM = ftyp("isom");
    private static final byte[] RANDOM = {0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07};

    private static byte[] riff(String form) {
        byte[] b = new byte[12];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, b, 0, 4);
        System.arraycopy(form.getBytes(StandardCharsets.US_ASCII), 0, b, 8, 4);
        return b;
    }

    private static byte[] ftyp(String brand) {
        byte[] b = new byte[16];
        // b[0..3] = size (any); b[4..7] = "ftyp"; b[8..11] = brand
        System.arraycopy("ftyp".getBytes(StandardCharsets.US_ASCII), 0, b, 4, 4);
        System.arraycopy(brand.getBytes(StandardCharsets.US_ASCII), 0, b, 8, 4);
        return b;
    }

    /**
     * A MultipartFile whose getInputStream() returns a new stream over {@code bytes} each call.
     */
    private static MultipartFile fileOf(byte[] bytes) {
        MultipartFile f = mock(MultipartFile.class);
        try {
            when(f.getInputStream()).thenAnswer((Answer<ByteArrayInputStream>) inv -> new ByteArrayInputStream(bytes));
        } catch (IOException ignored) {
            // never for a mock stub
        }
        return f;
    }

    private static MultipartFile textFile(String content) {
        return fileOf(content.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertRejected(MultipartFile file, String type, String code) {
        assertThatThrownBy(() -> UploadValidator.validate(file, type))
                .isInstanceOfSatisfying(ServiceException.class, ex -> {
                    Assertions.assertThat(ex.getMessageCode()).isEqualTo(code);
                    Assertions.assertThat(ex.getStatus()).isEqualTo(415);
                });
    }

    @Nested
    @DisplayName("un-policed / permissive categories")
    class Unpoliced {

        @Test
        @DisplayName("null declared type returns without reading the file")
        void shouldSkipWhenTypeNull() {
            MultipartFile f = mock(MultipartFile.class);
            assertThatCode(() -> UploadValidator.validate(f, null)).doesNotThrowAnyException();
            verifyNoInteractions(f);
        }

        @Test
        @DisplayName("unrecognized declared type is left permissive")
        void shouldSkipWhenTypeUnknown() {
            MultipartFile f = mock(MultipartFile.class);
            assertThatCode(() -> UploadValidator.validate(f, "hologram")).doesNotThrowAnyException();
            verifyNoInteractions(f);
        }
    }

    @Nested
    @DisplayName("empty / unreadable file → TM_493")
    class EmptyFile {

        @Test
        @DisplayName("zero-byte file is rejected")
        void shouldRejectEmptyFile() {
            assertRejected(fileOf(new byte[0]), "image", "TM_493");
        }

        @Test
        @DisplayName("unreadable stream (IOException) is rejected")
        void shouldRejectUnreadableFile() throws IOException {
            MultipartFile f = mock(MultipartFile.class);
            when(f.getInputStream()).thenThrow(new IOException("disk gone"));
            assertRejected(f, "video", "TM_493");
        }
    }

    @Nested
    @DisplayName("image category")
    class Images {

        @Test
        @DisplayName("JPEG passes")
        void jpeg() {
            assertThatCode(() -> UploadValidator.validate(fileOf(JPEG), "image")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("PNG passes")
        void png() {
            assertThatCode(() -> UploadValidator.validate(fileOf(PNG), "image")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("GIF passes")
        void gif() {
            assertThatCode(() -> UploadValidator.validate(fileOf(GIF), "image")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("BMP passes")
        void bmp() {
            assertThatCode(() -> UploadValidator.validate(fileOf(BMP), "image")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("WebP (RIFF/WEBP) passes")
        void webp() {
            assertThatCode(() -> UploadValidator.validate(fileOf(WEBP), "image")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("HEIC (ftyp brand) passes")
        void heic() {
            assertThatCode(() -> UploadValidator.validate(fileOf(HEIC), "image")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("AVIF (ftyp brand) passes")
        void avif() {
            assertThatCode(() -> UploadValidator.validate(fileOf(AVIF), "image")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("'sticker' maps to image and passes for a PNG")
        void stickerMapsToImage() {
            assertThatCode(() -> UploadValidator.validate(fileOf(PNG), "sticker")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("audio bytes declared as image → TM_495 mismatch")
        void mismatchAudioAsImage() {
            assertRejected(fileOf(MP3_ID3), "image", "TM_495");
        }

        @Test
        @DisplayName("unrecognized bytes declared as image → TM_495 mismatch")
        void mismatchUnknownAsImage() {
            assertRejected(fileOf(RANDOM), "image", "TM_495");
        }
    }

    @Nested
    @DisplayName("SVG special-casing (image only)")
    class Svg {

        @Test
        @DisplayName("clean <svg> passes")
        void cleanSvg() {
            assertThatCode(() -> UploadValidator.validate(textFile("<svg xmlns='x'><rect/></svg>"), "image"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("clean SVG with <?xml prolog passes")
        void cleanSvgWithProlog() {
            assertThatCode(() -> UploadValidator.validate(
                    textFile("<?xml version='1.0'?><svg><circle/></svg>"), "image"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("SVG with <script → TM_494")
        void scriptSvg() {
            assertRejected(textFile("<svg><script>alert(1)</script></svg>"), "image", "TM_494");
        }

        @Test
        @DisplayName("SVG with javascript: URI → TM_494")
        void javascriptUriSvg() {
            assertRejected(textFile("<svg><a href='javascript:evil()'/></svg>"), "image", "TM_494");
        }

        @Test
        @DisplayName("SVG with <foreignObject → TM_494")
        void foreignObjectSvg() {
            assertRejected(textFile("<svg><foreignObject></foreignObject></svg>"), "image", "TM_494");
        }

        @Test
        @DisplayName("SVG with an on* event handler → TM_494")
        void onHandlerSvg() {
            assertRejected(textFile("<svg onload='evil()'></svg>"), "image", "TM_494");
        }

        @Test
        @DisplayName("SVG whose body becomes unreadable on the second read is treated as unsafe → TM_494")
        void unreadableBodyTreatedUnsafe() throws IOException {
            byte[] svgHead = "<svg><rect/></svg>".getBytes(StandardCharsets.UTF_8);
            MultipartFile f = mock(MultipartFile.class);
            // 1st call (readHead) succeeds; 2nd call (active-content scan) throws → unsafe
            when(f.getInputStream())
                    .thenReturn(new ByteArrayInputStream(svgHead))
                    .thenThrow(new IOException("stream gone"));
            assertRejected(f, "image", "TM_494");
        }
    }

    @Nested
    @DisplayName("video category")
    class Videos {

        @Test
        @DisplayName("WebM/Matroska (EBML) passes")
        void webm() {
            assertThatCode(() -> UploadValidator.validate(fileOf(WEBM), "video")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("AVI (RIFF/AVI ) passes")
        void avi() {
            assertThatCode(() -> UploadValidator.validate(fileOf(AVI), "video")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("MP4 (ftyp isom → video brand) passes")
        void mp4() {
            assertThatCode(() -> UploadValidator.validate(fileOf(MP4_ISOM), "video")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("PDF declared as video → TM_495 mismatch")
        void mismatchPdfAsVideo() {
            assertRejected(fileOf(PDF), "video", "TM_495");
        }
    }

    @Nested
    @DisplayName("audio category")
    class Audios {

        @Test
        @DisplayName("MP3 (ID3) passes")
        void mp3Id3() {
            assertThatCode(() -> UploadValidator.validate(fileOf(MP3_ID3), "audio")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("MP3 (frame sync) passes")
        void mp3Sync() {
            assertThatCode(() -> UploadValidator.validate(fileOf(MP3_SYNC), "audio")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("OGG passes")
        void ogg() {
            assertThatCode(() -> UploadValidator.validate(fileOf(OGG), "audio")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("FLAC passes")
        void flac() {
            assertThatCode(() -> UploadValidator.validate(fileOf(FLAC), "audio")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("WAV (RIFF/WAVE) passes")
        void wav() {
            assertThatCode(() -> UploadValidator.validate(fileOf(WAV), "audio")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("M4A (ftyp audio brand) passes")
        void m4a() {
            assertThatCode(() -> UploadValidator.validate(fileOf(M4A), "audio")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("'voice' maps to audio and passes for OGG")
        void voiceMapsToAudio() {
            assertThatCode(() -> UploadValidator.validate(fileOf(OGG), "voice")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("MediaRecorder audio in a WebM (video) container is accepted for audio")
        void webmContainerAcceptedAsAudio() {
            assertThatCode(() -> UploadValidator.validate(fileOf(WEBM), "audio")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Safari audio in an ISO-BMFF (video-brand) container is accepted for audio")
        void isobmffContainerAcceptedAsAudio() {
            assertThatCode(() -> UploadValidator.validate(fileOf(MP4_ISOM), "audio")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("JPEG declared as audio → TM_495 mismatch (image is not a media container)")
        void mismatchImageAsAudio() {
            assertRejected(fileOf(JPEG), "audio", "TM_495");
        }
    }

    @Nested
    @DisplayName("document category")
    class Documents {

        @Test
        @DisplayName("PDF passes")
        void pdf() {
            assertThatCode(() -> UploadValidator.validate(fileOf(PDF), "document")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("'file' maps to document and passes for a PDF")
        void fileMapsToDocument() {
            assertThatCode(() -> UploadValidator.validate(fileOf(PDF), "file")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("JPEG declared as document → TM_495 mismatch")
        void mismatchImageAsDocument() {
            assertRejected(fileOf(JPEG), "document", "TM_495");
        }
    }

    @Nested
    @DisplayName("declared-type casing")
    class Casing {

        @Test
        @DisplayName("declared type is matched case-insensitively")
        void shouldMatchTypeCaseInsensitively() {
            assertThatCode(() -> UploadValidator.validate(fileOf(JPEG), "IMAGE")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the input stream really is consulted for a policed category")
        void shouldReadStreamForPolicedCategory() {
            MultipartFile f = fileOf(JPEG);
            UploadValidator.validate(f, "image");
            try {
                verify(f).getInputStream();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }
    }
}
