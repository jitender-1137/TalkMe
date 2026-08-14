package com.neo.chat.service.impl;

import com.neo.chat.domain.User;
import com.neo.chat.dto.response.CompatibilityScore;
import com.neo.chat.enums.Interest;
import com.neo.chat.enums.Language;
import com.neo.chat.enums.Mood;
import com.neo.chat.service.CompatibilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

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

    /**
     * Minimal user; interests/languages/mood left null so shared-signal openers stay empty.
     */
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

        @Test
        void shouldSuggestFollowupStyleForSubstantiveNonQuestion() {
            // Long, non-question, non-greeting → the follow-up bank that keeps a thread alive.
            List<String> replies = wingman.replySuggestions(
                    "I just finished a fascinating novel about deep-space travel", 2);
            assertThat(replies).hasSize(2);
            assertThat(replies.get(0)).isEqualTo("That's really interesting — tell me more about that.");
        }

        @Test
        void shouldSuggestOpenerStyleForGreeting() {
            // A greeting-prefixed message reads as an opener, not a substantive turn.
            List<String> replies = wingman.replySuggestions("hey there friend, welcome!", 2);
            assertThat(replies).hasSize(2);
            assertThat(replies.get(0)).isEqualTo("Hey there! How's your night going?");
        }

        @Test
        void shouldSuggestOpenerStyleForVeryShortMessage() {
            // <= 12 chars is treated as short/opener regardless of content.
            List<String> replies = wingman.replySuggestions("hi", 1);
            assertThat(replies).hasSize(1);
            assertThat(replies.get(0)).isEqualTo("Hey there! How's your night going?");
        }

        @Test
        void shouldNeverExceedBankSize() {
            // ANSWER_STYLE has 4 entries; asking for more just returns all of them.
            List<String> replies = wingman.replySuggestions("Really? What happened?", 99);
            assertThat(replies.size()).isLessThanOrEqualTo(4);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  icebreakers — shared-signal openers (interests / languages / mood)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("icebreakers — shared-signal openers")
    class SharedSignalOpeners {

        @Test
        void shouldDeriveOpenerFromSharedInterest() {
            when(compatibilityService.score(any(), any())).thenReturn(null);
            User a = wingmanUser("alice");
            User b = wingmanUser("bob");
            a.setInterests(Set.of(Interest.MUSIC));
            b.setInterests(Set.of(Interest.MUSIC));

            List<String> openers = wingman.icebreakers(a, b, 5);

            // prettify(MUSIC) → "Music"
            assertThat(openers).anySatisfy(o -> assertThat(o).contains("we both like Music"));
        }

        @Test
        void shouldDeriveOpenerFromSharedLanguage() {
            when(compatibilityService.score(any(), any())).thenReturn(null);
            User a = wingmanUser("alice");
            User b = wingmanUser("bob");
            a.setLanguages(Set.of(Language.EN));
            b.setLanguages(Set.of(Language.EN));

            List<String> openers = wingman.icebreakers(a, b, 5);

            assertThat(openers).anySatisfy(o -> assertThat(o).contains("We both speak En"));
        }

        @Test
        void shouldDeriveOpenerFromSharedMood() {
            when(compatibilityService.score(any(), any())).thenReturn(null);
            User a = wingmanUser("alice");
            User b = wingmanUser("bob");
            a.setMood(Mood.FLIRT);
            b.setMood(Mood.FLIRT);

            List<String> openers = wingman.icebreakers(a, b, 5);

            assertThat(openers).anySatisfy(o -> assertThat(o).contains("Flirt mood tonight"));
        }

        @Test
        void shouldNotEmitSharedOpenersWhenSignalsDiffer() {
            when(compatibilityService.score(any(), any())).thenReturn(null);
            User a = wingmanUser("alice");
            User b = wingmanUser("bob");
            a.setInterests(Set.of(Interest.MUSIC));
            b.setInterests(Set.of(Interest.SPORTS)); // no overlap
            a.setMood(Mood.FLIRT);
            b.setMood(Mood.DATING);                  // different mood

            List<String> openers = wingman.icebreakers(a, b, 6);

            // Falls through entirely to the generic bank.
            assertThat(openers).noneMatch(o -> o.contains("we both like"));
            assertThat(openers).noneMatch(o -> o.contains("mood tonight"));
            assertThat(openers).isNotEmpty();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  icebreakers — highlightToOpener mapping branches
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("icebreakers — highlight-to-opener mapping")
    class HighlightMapping {

        /**
         * Stubs the compatibility score to carry a single highlight, then returns the one opener
         * the wingman derives from it (max=1) so each mapping branch can be asserted in isolation.
         */
        private List<String> openersForHighlight(String highlight) {
            when(compatibilityService.score(any(), any())).thenReturn(
                    CompatibilityScore.builder()
                            .overall(70).bucket("HIGH")
                            .highlights(List.of(highlight))
                            .build());
            return wingman.icebreakers(wingmanUser("alice"), wingmanUser("bob"), 1);
        }

        @Test
        void loveHighlightBecomesWhatGotYouIntoIt() {
            assertThat(openersForHighlight("You both love Music").get(0))
                    .isEqualTo("You both love Music — what got you into it?");
        }

        @Test
        void speakHighlightBecomesSaySomething() {
            assertThat(openersForHighlight("You both speak English").get(0))
                    .isEqualTo("You both speak English. Say something in it!");
        }

        @Test
        void locationHighlightBecomesHiddenGems() {
            assertThat(openersForHighlight("You're both in Berlin").get(0))
                    .isEqualTo("You're both in Berlin — any hidden gems around there?");
        }

        @Test
        void moodHighlightBecomesWavelengthPrompt() {
            assertThat(openersForHighlight("Matching mood").get(0))
                    .isEqualTo("Seems like we're on the same wavelength tonight — what's on your mind?");
        }

        @Test
        void energyHighlightBecomesGoodVibePrompt() {
            assertThat(openersForHighlight("High energy vibes").get(0))
                    .isEqualTo("I get a good vibe from you — what's your ideal way to spend a night?");
        }

        @Test
        void unrecognizedHighlightIsPosedBackAsPrompt() {
            assertThat(openersForHighlight("Two night owls").get(0))
                    .isEqualTo("Two night owls — tell me more?");
        }

        @Test
        void blankHighlightFallsBackToGenericPrompt() {
            assertThat(openersForHighlight("   ").get(0))
                    .isEqualTo("What are you into these days?");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  rewrite — applyTone punctuation / prefix branches
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("rewrite — tone decoration branches")
    class ToneDecoration {

        @Test
        void warmToneAppendsClauseEvenWhenDraftAlreadyEndsInPunctuation() {
            // "warm" has a multi-char suffix clause that is kept even past a trailing "!".
            List<String> variants = wingman.rewrite("see you soon!", "warm", 6);
            assertThat(variants.get(0)).contains("really glad we're talking");
        }

        @Test
        void confidentToneDoesNotDoublePunctuateWhenDraftEndsInPunctuation() {
            // "confident" suffix is a single "." — not re-appended when the body already ends "!".
            List<String> variants = wingman.rewrite("sounds great!", "confident", 6);
            assertThat(variants.get(0)).isEqualTo("Sounds great!");
        }

        @Test
        void playfulTonePrefixLeavesBodyUncapitalizedButPrefixed() {
            // A non-empty prefix template keeps the core as-is (body not capitalized) behind "Okay so… ".
            List<String> variants = wingman.rewrite("thinking about tonight", "playful", 6);
            assertThat(variants.get(0)).startsWith("Okay so… ");
        }

        @Test
        void shouldCollapseInternalWhitespaceRuns() {
            // normalizeCore collapses runs of whitespace before decoration.
            List<String> variants = wingman.rewrite("hello    there    friend", "casual", 6);
            assertThat(variants).allSatisfy(v -> assertThat(v).doesNotContain("  "));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  PHASE 7 branch backfill — negative / edge scenarios
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("replySuggestions — short/greeting classification matrix")
    class ShortOrGreetingMatrix {

        private static final String OPENER0 = "Hey there! How's your night going?";
        private static final String FOLLOWUP0 = "That's really interesting — tell me more about that.";

        @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
        @CsvSource({
                // >12 chars but strips to an exact greeting token (equals sub-conditions).
                "'hi!!!!!!!!!!!!!', OPENER",
                "'hey!!!!!!!!!!!!', OPENER",
                "'hello!!!!!!!!!!', OPENER",
                // startsWith sub-conditions.
                "'hi there friend indeed', OPENER",
                "'hey friend good day now', OPENER",
                "'hello there friend indeed', OPENER",
                "'good morning sunshine!', OPENER",
                "'good evening everyone here', OPENER",
                "'good night everyone here', OPENER",
                // none of the greeting sub-conditions match → follow-up bank.
                "'the weather is quite nice today', FOLLOWUP",
        })
        void classifiesShortOrGreeting(String message, String bank) {
            List<String> replies = wingman.replySuggestions(message, 1);
            assertThat(replies).hasSize(1);
            assertThat(replies.get(0)).isEqualTo("OPENER".equals(bank) ? OPENER0 : FOLLOWUP0);
        }

        @Test
        void nullLastMessageIsTreatedAsOpener() {
            // line 161 ternary: lastMessageText == null → "" → OPENER bank (empty-text branch).
            List<String> replies = wingman.replySuggestions(null, 1);
            assertThat(replies).hasSize(1);
            assertThat(replies.get(0)).isEqualTo(OPENER0);
        }
    }

    @Nested
    @DisplayName("rewrite — endsWithPunctuation terminal-char branches")
    class EndsWithPunctuationBranches {

        @Test
        void draftEndingWithPeriodIsRecognizedAsPunctuated() {
            // line 243: c == '.' → the concise variant must not append a second '.'.
            List<String> variants = wingman.rewrite("i am sure.", "confident", 6);
            assertThat(variants).anySatisfy(v -> assertThat(v).isEqualTo("I am sure."));
        }

        @Test
        void draftEndingWithEllipsisIsRecognizedAsPunctuated() {
            // line 243: c == '…' (the last terminal-char branch).
            List<String> variants = wingman.rewrite("wait for it…", "confident", 6);
            assertThat(variants).anySatisfy(v -> assertThat(v).isEqualTo("Wait for it…"));
        }
    }

    @Nested
    @DisplayName("icebreakers — accumulator / loop-break edge branches")
    class IcebreakersEdgeBranches {

        @Test
        void scoreNonNullButHighlightsNullFallsThrough() {
            // line 74: score != null but getHighlights() == null → highlight loop skipped.
            when(compatibilityService.score(any(), any())).thenReturn(
                    CompatibilityScore.builder().overall(50).bucket("MID").highlights(null).build());

            List<String> openers = wingman.icebreakers(wingmanUser("alice"), wingmanUser("bob"), 3);
            assertThat(openers).isNotEmpty();
        }

        @Test
        void breaksOutOfHighlightLoopWhenMaxReached() {
            // line 76: out.size() >= max mid-loop → break before consuming all highlights.
            when(compatibilityService.score(any(), any())).thenReturn(
                    CompatibilityScore.builder().overall(90).bucket("HIGH")
                            .highlights(List.of("You both love Music", "You both speak English"))
                            .build());

            List<String> openers = wingman.icebreakers(wingmanUser("alice"), wingmanUser("bob"), 1);
            assertThat(openers).hasSize(1);
            assertThat(openers.get(0)).isEqualTo("You both love Music — what got you into it?");
        }

        @Test
        void breaksOutOfSharedSignalLoopWhenMaxReached() {
            // line 83: out.size() >= max while iterating the shared-signal openers → break.
            when(compatibilityService.score(any(), any())).thenReturn(null);
            User a = wingmanUser("alice");
            User b = wingmanUser("bob");
            a.setInterests(Set.of(Interest.MUSIC));
            b.setInterests(Set.of(Interest.MUSIC));
            a.setLanguages(Set.of(Language.EN));
            b.setLanguages(Set.of(Language.EN));
            a.setMood(Mood.FLIRT);
            b.setMood(Mood.FLIRT);

            List<String> openers = wingman.icebreakers(a, b, 1);
            assertThat(openers).hasSize(1);
        }

        @Test
        void nullHighlightElementFallsBackToGenericPrompt() {
            // highlightToOpener line 98: a null entry in the highlights list.
            when(compatibilityService.score(any(), any())).thenReturn(
                    CompatibilityScore.builder().overall(60).bucket("MID")
                            .highlights(Arrays.asList((String) null)).build());

            List<String> openers = wingman.icebreakers(wingmanUser("alice"), wingmanUser("bob"), 1);
            assertThat(openers).hasSize(1);
            assertThat(openers.get(0)).isEqualTo("What are you into these days?");
        }
    }

    @Nested
    @DisplayName("icebreakers — shared-signal predicate edges")
    class SharedSignalPredicateEdges {

        @Test
        void moodOpenerSkippedWhenOtherUserMoodNull() {
            // line 135: a.getMood() != null but b.getMood() == null → second condition false.
            when(compatibilityService.score(any(), any())).thenReturn(null);
            User a = wingmanUser("alice");
            User b = wingmanUser("bob");
            a.setMood(Mood.FLIRT); // b mood stays null

            List<String> openers = wingman.icebreakers(a, b, 6);
            assertThat(openers).noneMatch(o -> o.contains("mood tonight"));
            assertThat(openers).isNotEmpty();
        }

        @Test
        void sharedInterestSkippedWhenOtherUserInterestsNull() {
            // firstShared line 144: a != null but b == null → b == null branch → returns null.
            when(compatibilityService.score(any(), any())).thenReturn(null);
            User a = wingmanUser("alice");
            User b = wingmanUser("bob");
            a.setInterests(Set.of(Interest.MUSIC)); // b interests stay null

            List<String> openers = wingman.icebreakers(a, b, 6);
            assertThat(openers).noneMatch(o -> o.contains("we both like"));
            assertThat(openers).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("rewrite — backfill-loop exhaustion (no early break)")
    class RewriteBackfillExhaustion {

        @Test
        void backfillLoopExhaustsWhenMaxExceedsVariantCount() {
            // line 212: max larger than the distinct-variant count → the backfill loop runs to
            // completion without ever hitting the size>=max break.
            List<String> variants = wingman.rewrite("let us meet up soon", "friendly", 50);
            assertThat(variants).isNotEmpty();
            assertThat(variants.size()).isLessThan(50);
            assertThat(variants).doesNotHaveDuplicates();
        }
    }
}
