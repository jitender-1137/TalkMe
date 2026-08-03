package com.chat.talkMe.util;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.enums.ConversationEnergy;
import com.chat.talkMe.enums.Interest;
import com.chat.talkMe.enums.Language;
import com.chat.talkMe.enums.LookingForTag;
import com.chat.talkMe.enums.Mood;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test for {@link ProfileCompletion#compute(User)} — the weighted 0–100 checklist.
 * Weights: profileImage 20, bio 15, interests(≥3) 15, mood 10, conversationEnergy 10,
 * languages(non-empty) 10, lookingFor(non-empty) 10, voiceIntro 10 → sum 100. Each weight is
 * asserted in isolation, plus the blank-string guard, the interests ≥3 boundary, the null-user
 * short-circuit and the fully-complete 100 total.
 */
@DisplayName("ProfileCompletion (unit)")
class ProfileCompletionTest {

    private static final Set<Interest> THREE_INTERESTS =
            Set.of(Interest.SPORTS, Interest.MUSIC, Interest.MOVIES);

    @Nested
    @DisplayName("edge inputs")
    class EdgeInputs {

        @Test
        @DisplayName("null user scores 0")
        void nullUserIsZero() {
            assertThat(ProfileCompletion.compute(null)).isZero();
        }

        @Test
        @DisplayName("brand-new user (empty defaults) scores 0")
        void emptyUserIsZero() {
            assertThat(ProfileCompletion.compute(User.builder().build())).isZero();
        }
    }

    @Nested
    @DisplayName("individual weight contributions")
    class IndividualWeights {

        @Test
        @DisplayName("profile image adds 20")
        void profileImage() {
            assertThat(ProfileCompletion.compute(User.builder().profileImage("a.jpg").build())).isEqualTo(20);
        }

        @Test
        @DisplayName("bio adds 15")
        void bio() {
            assertThat(ProfileCompletion.compute(User.builder().bio("hi there").build())).isEqualTo(15);
        }

        @Test
        @DisplayName("exactly 3 interests adds 15 (boundary)")
        void threeInterests() {
            assertThat(ProfileCompletion.compute(User.builder().interests(THREE_INTERESTS).build())).isEqualTo(15);
        }

        @Test
        @DisplayName("fewer than 3 interests adds nothing (below boundary)")
        void twoInterestsNoCredit() {
            assertThat(ProfileCompletion.compute(
                    User.builder().interests(Set.of(Interest.SPORTS, Interest.MUSIC)).build())).isZero();
        }

        @Test
        @DisplayName("more than 3 interests still adds 15")
        void fourInterests() {
            assertThat(ProfileCompletion.compute(User.builder()
                    .interests(Set.of(Interest.SPORTS, Interest.MUSIC, Interest.MOVIES, Interest.GAMING))
                    .build())).isEqualTo(15);
        }

        @Test
        @DisplayName("mood adds 10")
        void mood() {
            assertThat(ProfileCompletion.compute(User.builder().mood(Mood.FLIRT).build())).isEqualTo(10);
        }

        @Test
        @DisplayName("conversation energy adds 10")
        void conversationEnergy() {
            assertThat(ProfileCompletion.compute(
                    User.builder().conversationEnergy(ConversationEnergy.FRIENDLY).build())).isEqualTo(10);
        }

        @Test
        @DisplayName("non-empty languages adds 10")
        void languages() {
            assertThat(ProfileCompletion.compute(
                    User.builder().languages(Set.of(Language.EN)).build())).isEqualTo(10);
        }

        @Test
        @DisplayName("non-empty lookingFor adds 10")
        void lookingFor() {
            assertThat(ProfileCompletion.compute(
                    User.builder().lookingFor(Set.of(LookingForTag.FRIENDS)).build())).isEqualTo(10);
        }

        @Test
        @DisplayName("voice intro adds 10")
        void voiceIntro() {
            assertThat(ProfileCompletion.compute(
                    User.builder().voiceIntroUrl("intro.mp3").build())).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("blank-string guard")
    class BlankStrings {

        @Test
        @DisplayName("empty profile image gives no credit")
        void emptyProfileImage() {
            assertThat(ProfileCompletion.compute(User.builder().profileImage("").build())).isZero();
        }

        @Test
        @DisplayName("whitespace bio gives no credit")
        void whitespaceBio() {
            assertThat(ProfileCompletion.compute(User.builder().bio("   ").build())).isZero();
        }

        @Test
        @DisplayName("whitespace voice intro gives no credit")
        void whitespaceVoiceIntro() {
            assertThat(ProfileCompletion.compute(User.builder().voiceIntroUrl("  ").build())).isZero();
        }
    }

    @Nested
    @DisplayName("aggregate")
    class Aggregate {

        @Test
        @DisplayName("a fully-filled profile scores exactly 100")
        void fullProfileIs100() {
            User user = User.builder()
                    .profileImage("a.jpg")
                    .bio("hi there")
                    .interests(THREE_INTERESTS)
                    .mood(Mood.FLIRT)
                    .conversationEnergy(ConversationEnergy.FRIENDLY)
                    .languages(Set.of(Language.EN))
                    .lookingFor(Set.of(LookingForTag.FRIENDS))
                    .voiceIntroUrl("intro.mp3")
                    .build();
            assertThat(ProfileCompletion.compute(user)).isEqualTo(100);
        }

        @Test
        @DisplayName("a partial profile sums its weights (image+bio+mood = 45)")
        void partialProfileSums() {
            User user = User.builder()
                    .profileImage("a.jpg")   // 20
                    .bio("hi there")          // 15
                    .mood(Mood.FLIRT)         // 10
                    .build();
            assertThat(ProfileCompletion.compute(user)).isEqualTo(45);
        }
    }
}
