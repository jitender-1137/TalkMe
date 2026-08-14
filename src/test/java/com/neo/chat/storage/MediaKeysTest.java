package com.neo.chat.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test for {@link MediaKeys} — the static reference/key parsing helpers.
 * Covers both the {@code <mediaRoot>/<key>} plain form and the rewritten
 * {@code ?path=<url-encoded>} form, the traversal-rejection rules of
 * {@link MediaKeys#isSafeKey(String)}, and the extension→MIME table.
 */
@DisplayName("MediaKeys (unit)")
class MediaKeysTest {

    private static final String ROOT = "/media";

    @Nested
    @DisplayName("absolutePath")
    class AbsolutePath {

        @Test
        @DisplayName("null → null")
        void nullReference() {
            assertThat(MediaKeys.absolutePath(null)).isNull();
        }

        @Test
        @DisplayName("blank → null")
        void blankReference() {
            assertThat(MediaKeys.absolutePath("   ")).isNull();
        }

        @Test
        @DisplayName("plain absolute path is returned unchanged")
        void plainAbsolute() {
            assertThat(MediaKeys.absolutePath("/media/conversations/a.jpg"))
                    .isEqualTo("/media/conversations/a.jpg");
        }

        @Test
        @DisplayName("relative path (no leading slash, no query) → null")
        void relativePath() {
            assertThat(MediaKeys.absolutePath("conversations/a.jpg")).isNull();
        }

        @Test
        @DisplayName("path= query param is url-decoded")
        void pathParamDecoded() {
            String ref = "/media/serve?path=%2Fmedia%2Fposts%2Ffoo%20bar.jpg";
            assertThat(MediaKeys.absolutePath(ref)).isEqualTo("/media/posts/foo bar.jpg");
        }

        @Test
        @DisplayName("path= value stops at a trailing & of further params")
        void pathParamTruncatedAtAmpersand() {
            String ref = "/serve?path=%2Fmedia%2Fx.jpg&w=100";
            assertThat(MediaKeys.absolutePath(ref)).isEqualTo("/media/x.jpg");
        }

        @Test
        @DisplayName("absolute path with a non-path query strips the query")
        void absoluteWithQueryStripped() {
            assertThat(MediaKeys.absolutePath("/media/x.jpg?v=2")).isEqualTo("/media/x.jpg");
        }

        @Test
        @DisplayName("query-only relative reference (no path=) → null")
        void relativeWithQuery() {
            assertThat(MediaKeys.absolutePath("serve?v=2")).isNull();
        }
    }

    @Nested
    @DisplayName("key")
    class Key {

        @Test
        @DisplayName("abs under root prefix strips the root")
        void underRoot() {
            assertThat(MediaKeys.key("/media/posts/a.jpg", ROOT)).isEqualTo("posts/a.jpg");
        }

        @Test
        @DisplayName("root with a trailing slash is handled")
        void rootTrailingSlash() {
            assertThat(MediaKeys.key("/media/posts/a.jpg", "/media/")).isEqualTo("posts/a.jpg");
        }

        @Test
        @DisplayName("abs outside root falls back to stripping the leading slash")
        void outsideRoot() {
            assertThat(MediaKeys.key("/other/a.jpg", ROOT)).isEqualTo("other/a.jpg");
        }

        @Test
        @DisplayName("path= form resolves to a key")
        void pathParamForm() {
            String ref = "/serve?path=%2Fmedia%2Fposts%2Fa.jpg";
            assertThat(MediaKeys.key(ref, ROOT)).isEqualTo("posts/a.jpg");
        }

        @Test
        @DisplayName("unresolvable reference → null")
        void unresolvable() {
            assertThat(MediaKeys.key("relative.jpg", ROOT)).isNull();
        }

        @Test
        @DisplayName("resulting key that would traverse upward → null")
        void unsafeResult() {
            assertThat(MediaKeys.key("/media/../secret", ROOT)).isNull();
        }
    }

    @Nested
    @DisplayName("isSafeKey")
    class IsSafeKey {

        @Test
        @DisplayName("a plain relative key is safe")
        void safe() {
            assertThat(MediaKeys.isSafeKey("conversations/uuid/x.mp4")).isTrue();
        }

        @Test
        @DisplayName("null → unsafe")
        void nullKey() {
            assertThat(MediaKeys.isSafeKey(null)).isFalse();
        }

        @Test
        @DisplayName("blank → unsafe")
        void blankKey() {
            assertThat(MediaKeys.isSafeKey("   ")).isFalse();
        }

        @Test
        @DisplayName("leading slash → unsafe")
        void leadingSlash() {
            assertThat(MediaKeys.isSafeKey("/etc/passwd")).isFalse();
        }

        @Test
        @DisplayName("parent traversal .. → unsafe")
        void traversal() {
            assertThat(MediaKeys.isSafeKey("a/../../b")).isFalse();
        }

        @Test
        @DisplayName("backslash → unsafe")
        void backslash() {
            assertThat(MediaKeys.isSafeKey("a\\b")).isFalse();
        }
    }

    @Nested
    @DisplayName("contentTypeGuess")
    class ContentTypeGuess {

        @Test
        @DisplayName("image extensions map to image/* types")
        void images() {
            assertThat(MediaKeys.contentTypeGuess("a.jpg")).isEqualTo("image/jpeg");
            assertThat(MediaKeys.contentTypeGuess("a.jpeg")).isEqualTo("image/jpeg");
            assertThat(MediaKeys.contentTypeGuess("a.png")).isEqualTo("image/png");
            assertThat(MediaKeys.contentTypeGuess("a.gif")).isEqualTo("image/gif");
            assertThat(MediaKeys.contentTypeGuess("a.webp")).isEqualTo("image/webp");
            assertThat(MediaKeys.contentTypeGuess("a.avif")).isEqualTo("image/avif");
            assertThat(MediaKeys.contentTypeGuess("a.bmp")).isEqualTo("image/bmp");
            assertThat(MediaKeys.contentTypeGuess("a.svg")).isEqualTo("image/svg+xml");
            assertThat(MediaKeys.contentTypeGuess("a.heic")).isEqualTo("image/heic");
            assertThat(MediaKeys.contentTypeGuess("a.heif")).isEqualTo("image/heic");
        }

        @Test
        @DisplayName("video extensions map to video/* types")
        void videos() {
            assertThat(MediaKeys.contentTypeGuess("a.mp4")).isEqualTo("video/mp4");
            assertThat(MediaKeys.contentTypeGuess("a.m4v")).isEqualTo("video/mp4");
            assertThat(MediaKeys.contentTypeGuess("a.webm")).isEqualTo("video/webm");
            assertThat(MediaKeys.contentTypeGuess("a.mov")).isEqualTo("video/quicktime");
            assertThat(MediaKeys.contentTypeGuess("a.ogv")).isEqualTo("video/ogg");
        }

        @Test
        @DisplayName("audio and pdf extensions map correctly")
        void audioAndPdf() {
            assertThat(MediaKeys.contentTypeGuess("a.mp3")).isEqualTo("audio/mpeg");
            assertThat(MediaKeys.contentTypeGuess("a.m4a")).isEqualTo("audio/mp4");
            assertThat(MediaKeys.contentTypeGuess("a.aac")).isEqualTo("audio/aac");
            assertThat(MediaKeys.contentTypeGuess("a.ogg")).isEqualTo("audio/ogg");
            assertThat(MediaKeys.contentTypeGuess("a.opus")).isEqualTo("audio/ogg");
            assertThat(MediaKeys.contentTypeGuess("a.wav")).isEqualTo("audio/wav");
            assertThat(MediaKeys.contentTypeGuess("a.pdf")).isEqualTo("application/pdf");
        }

        @Test
        @DisplayName("extension match is case-insensitive")
        void caseInsensitive() {
            assertThat(MediaKeys.contentTypeGuess("PHOTO.JPG")).isEqualTo("image/jpeg");
        }

        @Test
        @DisplayName("unknown extension → null")
        void unknownExtension() {
            assertThat(MediaKeys.contentTypeGuess("a.xyz")).isNull();
        }

        @Test
        @DisplayName("no extension → null")
        void noExtension() {
            assertThat(MediaKeys.contentTypeGuess("noextension")).isNull();
        }

        @Test
        @DisplayName("null → null")
        void nullName() {
            assertThat(MediaKeys.contentTypeGuess(null)).isNull();
        }
    }
}
