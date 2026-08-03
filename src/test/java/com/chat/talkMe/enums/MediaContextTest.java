package com.chat.talkMe.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test for {@link MediaContext} — the destination context recorded on every
 * upload. Its only behaviour is the tolerant {@link MediaContext#fromCategory(String)}
 * storage-folder parser and the {@link MediaContext#isAnonymousToPeer()} predicate that
 * flags stranger uploads. Exercised with no Spring context.
 */
@DisplayName("MediaContext (unit)")
class MediaContextTest {

    @Nested
    @DisplayName("fromCategory")
    class FromCategory {

        @Test
        @DisplayName("maps each known storage folder to its context (note the singular/plural mismatch)")
        void mapsKnownFolders() {
            assertThat(MediaContext.fromCategory("strangers")).isEqualTo(MediaContext.STRANGER);
            assertThat(MediaContext.fromCategory("lobby")).isEqualTo(MediaContext.LOBBY);
            assertThat(MediaContext.fromCategory("conversations")).isEqualTo(MediaContext.CONVERSATION);
            assertThat(MediaContext.fromCategory("profiles")).isEqualTo(MediaContext.PROFILE);
            assertThat(MediaContext.fromCategory("posts")).isEqualTo(MediaContext.POST);
            assertThat(MediaContext.fromCategory("stories")).isEqualTo(MediaContext.STORY);
        }

        @ParameterizedTest
        @ValueSource(strings = {"STRANGERS", "Strangers", "sTrAnGeRs"})
        void isCaseInsensitive(String category) {
            assertThat(MediaContext.fromCategory(category)).isEqualTo(MediaContext.STRANGER);
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", "  ", "other", "others", "unknown", "stranger", "conversation", "42"})
        void unknownOrNullFallsBackToOther(String category) {
            // Singular forms ("stranger"/"conversation") and any unmapped folder must fall
            // back to OTHER — only the exact plural folder names map to a real context.
            assertThat(MediaContext.fromCategory(category)).isEqualTo(MediaContext.OTHER);
        }
    }

    @Nested
    @DisplayName("isAnonymousToPeer")
    class IsAnonymousToPeer {

        @Test
        @DisplayName("only STRANGER hides the uploader from the peer")
        void onlyStrangerIsAnonymous() {
            assertThat(MediaContext.STRANGER.isAnonymousToPeer()).isTrue();
        }

        @ParameterizedTest
        @EnumSource(value = MediaContext.class, names = "STRANGER", mode = EnumSource.Mode.EXCLUDE)
        void everyOtherContextIsNotAnonymous(MediaContext ctx) {
            assertThat(ctx.isAnonymousToPeer()).isFalse();
        }
    }
}
