package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.CompatibilityScore;
import com.chat.talkMe.service.CompatibilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure unit test for {@link HeuristicWingmanServiceImpl} — the heuristic (no-LLM) AI Wingman.
 *
 * <p>Only collaborator is {@link CompatibilityService}, which is mocked. The service performs no
 * I/O of its own, so every path is exercised deterministically. Focus is the {@code rewrite}
 * draft-polishing path (feature #11 "rewrite my message"), with lighter sanity coverage of
 * {@code icebreakers} and {@code replySuggestions}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HeuristicWingmanServiceImpl (unit)")
class HeuristicWingmanServiceImplTest {

    @Mock
    private CompatibilityService compatibilityService;

    private HeuristicWingmanServiceImpl wingman;

    @BeforeEach
    void setUp() {
        wingman = new HeuristicWingmanServiceImpl(compatibilityService);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Minimal user; interests/languages/mood left null so shared-signal openers stay empty. */
    private static User wingmanUser(String username) {
        User u = User.builder()
                .username(username).email(username + "@e.com").name("User " + username)
                .isGuest(false)
                .build();
        return u;
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  rewrite(draft, tone, max)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("rewrite")
    class Rewrite {

        @Test
        void shouldReturnNonEmptyVariantsForNormalDraft() {
            List<String> variants = wingman.rewrite("want to grab a coffee sometime", "friendly", 5);

            assertThat(variants).isNotEmpty();
            // No variant is null/blank, and none echoes an empty core.
            assertThat(variants).allSatisfy(v -> assertThat(v).isNotBlank());
            // CompatibilityService is never consulted by the rewrite path.
            verifyNoInteractions(compatibilityService);
        }

        @Test
        void shouldReturnEmptyListForBlankDraft() {
            assertThat(wingman.rewrite("   ", "friendly", 5)).isEmpty();
        }

        @Test
        void shouldReturnEmptyListForNullDraft() {
            assertThat(wingman.rewrite(null, "friendly", 5)).isEmpty();
        }

        @Test
        void shouldReturnEmptyListWhenMaxIsZero() {
            assertThat(wingman.rewrite("perfectly good draft", "friendly", 0)).isEmpty();
        }

        @Test
        void shouldReturnEmptyListWhenMaxIsNegative() {
            assertThat(wingman.rewrite("perfectly good draft", "friendly", -3)).isEmpty();
        }

        @Test
        void shouldHonorKnownFlirtyToneAsFirstVariant() {
            List<String> variants = wingman.rewrite("want to grab a coffee sometime", "flirty", 5);

            assertThat(variants).isNotEmpty();
            // The flirty template appends the winking emoji and carries no "Hey! " (friendly) prefix.
            assertThat(variants.get(0)).contains("😉"); // 😉
            assertThat(variants.get(0)).doesNotStartWith("Hey! ");
        }

        @Test
        void shouldBeCaseInsensitiveOnTone() {
            // "FLIRTY" must resolve to the same template as "flirty".
            List<String> variants = wingman.rewrite("want to grab a coffee sometime", "FLIRTY", 5);
            assertThat(variants.get(0)).contains("😉"); // 😉
        }

        @Test
        void shouldFallBackToFriendlyWhenToneIsNull() {
            List<String> variants = wingman.rewrite("want to grab a coffee sometime", null, 5);
            // The friendly template prefixes "Hey! ".
            assertThat(variants.get(0)).startsWith("Hey! ");
        }

        @Test
        void shouldStillReturnVariantsForUnknownTone() {
            // Unknown tone → the requested-tone step is skipped but the softened/concise variants remain.
            List<String> variants = wingman.rewrite("want to grab a coffee sometime", "sarcastic", 5);
            assertThat(variants).isNotEmpty();
        }

        @Test
        void shouldRespectMaxSizeExactly() {
            assertThat(wingman.rewrite("let us meet up soon", "friendly", 3)).hasSize(3);
            assertThat(wingman.rewrite("let us meet up soon", "friendly", 2)).hasSize(2);
            assertThat(wingman.rewrite("let us meet up soon", "friendly", 1)).hasSize(1);
        }

        @Test
        void shouldNeverExceedMax() {
            List<String> variants = wingman.rewrite("let us meet up soon", "flirty", 4);
            assertThat(variants.size()).isLessThanOrEqualTo(4);
        }

        @Test
        void shouldPreserveEmojiAndUnicodeInDraft() {
            // Note: the leading word is capitalized by the rewriter ("café" → "Café"), but the
            // mid-string emoji and CJK text must survive verbatim in every variant.
            List<String> variants = wingman.rewrite("café ☕ 日本語 plans?", "confident", 4);
            assertThat(variants).isNotEmpty();
            assertThat(variants).allSatisfy(v -> assertThat(v).contains("☕").contains("日本語"));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  icebreakers(a, b, max)  — light sanity
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("icebreakers")
    class Icebreakers {

        @Test
        void shouldReturnOpenersDerivedFromCompatibilityHighlights() {
            when(compatibilityService.score(any(), any())).thenReturn(
                    CompatibilityScore.builder()
                            .overall(80)
                            .bucket("HIGH")
                            .highlights(List.of("You both love Music"))
                            .build());

            List<String> openers = wingman.icebreakers(wingmanUser("alice"), wingmanUser("bob"), 5);

            assertThat(openers).isNotEmpty();
            assertThat(openers.size()).isLessThanOrEqualTo(5);
            assertThat(openers.get(0)).contains("You both love Music");
        }

        @Test
        void shouldBackfillGenericOpenersWhenScoreIsNull() {
            when(compatibilityService.score(any(), any())).thenReturn(null);

            List<String> openers = wingman.icebreakers(wingmanUser("alice"), wingmanUser("bob"), 3);

            assertThat(openers).isNotEmpty();
            assertThat(openers).hasSize(3);
        }

        @Test
        void shouldReturnEmptyWhenMaxIsZero() {
            // Early return before the collaborator is ever touched.
            assertThat(wingman.icebreakers(wingmanUser("alice"), wingmanUser("bob"), 0)).isEmpty();
            verifyNoInteractions(compatibilityService);
        }

        @Test
        void shouldReturnEmptyWhenEitherUserIsNull() {
            assertThat(wingman.icebreakers(null, wingmanUser("bob"), 5)).isEmpty();
            assertThat(wingman.icebreakers(wingmanUser("alice"), null, 5)).isEmpty();
            verifyNoInteractions(compatibilityService);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  replySuggestions(lastMessage, max)  — light sanity
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("replySuggestions")
    class ReplySuggestions {

        @Test
        void shouldSuggestAnswerStyleWhenLastMessageIsAQuestion() {
            List<String> replies = wingman.replySuggestions("What are you into these days?", 3);
            assertThat(replies).isNotEmpty();
            assertThat(replies).hasSize(3);
            verifyNoInteractions(compatibilityService);
        }

        @Test
        void shouldSuggestOpenersForEmptyLastMessage() {
            List<String> replies = wingman.replySuggestions("", 4);
            assertThat(replies).isNotEmpty();
        }

        @Test
        void shouldReturnEmptyWhenMaxIsZero() {
            assertThat(wingman.replySuggestions("anything", 0)).isEmpty();
        }
    }
}
