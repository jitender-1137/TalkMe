package com.chat.talkMe.service.impl;

import com.chat.talkMe.config.CompatibilityProperties;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.CompatibilityScore;
import com.chat.talkMe.enums.ConversationEnergy;
import com.chat.talkMe.enums.Interest;
import com.chat.talkMe.enums.Language;
import com.chat.talkMe.enums.Mood;
import com.chat.talkMe.enums.PersonalityTrait;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.hibernate.collection.spi.PersistentMap;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test for {@link CompatibilityServiceImpl} — a deterministic, I/O-free weighted
 * scorer. Uses a real {@link CompatibilityProperties} (a plain config value object with the
 * production default weights, which sum to 100 so {@code overall == round(sum(factor·weight))}).
 *
 * <p>Each factor is isolated by asserting its 0–100 entry in {@code breakdown} while the other
 * inputs are left at their neutral defaults, then the overall/bucket/explanation/highlights
 * presentation layer is checked with crafted high/medium/low profiles.
 */
@DisplayName("CompatibilityServiceImpl (unit)")
class CompatibilityServiceImplTest {

    private CompatibilityProperties weights;
    private CompatibilityServiceImpl service;

    @BeforeEach
    void setUp() {
        weights = new CompatibilityProperties(); // production defaults, total() == 100
        service = new CompatibilityServiceImpl(weights);
    }

    /** Bare user: empty (initialized) collections, all scalars null. */
    private static User user() {
        return User.builder().username("u").name("U").build();
    }

    @Nested
    @DisplayName("interests factor")
    class InterestsFactor {

        @Test
        @DisplayName("both empty → 0")
        void bothEmpty() {
            assertThat(score(user(), user()).getBreakdown().get("interests")).isZero();
        }

        @Test
        @DisplayName("one side empty → 0")
        void oneEmpty() {
            User a = user();
            a.setInterests(EnumSet.of(Interest.MUSIC));
            assertThat(score(a, user()).getBreakdown().get("interests")).isZero();
        }

        @Test
        @DisplayName("identical sets → 100")
        void identical() {
            User a = user();
            User b = user();
            a.setInterests(EnumSet.of(Interest.MUSIC, Interest.ART));
            b.setInterests(EnumSet.of(Interest.MUSIC, Interest.ART));
            assertThat(score(a, b).getBreakdown().get("interests")).isEqualTo(100);
        }

        @Test
        @DisplayName("partial overlap → Jaccard percentage (1/3 → 33)")
        void partial() {
            User a = user();
            User b = user();
            a.setInterests(EnumSet.of(Interest.MUSIC, Interest.ART));
            b.setInterests(EnumSet.of(Interest.MUSIC, Interest.SPORTS));
            assertThat(score(a, b).getBreakdown().get("interests")).isEqualTo(33);
        }

        @Test
        @DisplayName("disjoint sets → 0")
        void disjoint() {
            User a = user();
            User b = user();
            a.setInterests(EnumSet.of(Interest.MUSIC));
            b.setInterests(EnumSet.of(Interest.SPORTS));
            assertThat(score(a, b).getBreakdown().get("interests")).isZero();
        }

        @Test
        @DisplayName("null interest set on side A → 0 (jaccard/intersectType/sharedNames null-guard)")
        void aInterestsNull() {
            User a = user();
            User b = user();
            a.setInterests(null);                       // a == null branch of jaccard/intersectType/sharedNames
            b.setInterests(EnumSet.of(Interest.MUSIC));
            assertThat(score(a, b).getBreakdown().get("interests")).isZero();
        }

        @Test
        @DisplayName("null interest set on side B → 0 (jaccard/sharedNames B-side null-guard)")
        void bInterestsNull() {
            User a = user();
            User b = user();
            a.setInterests(EnumSet.of(Interest.MUSIC));  // non-null so evaluation reaches the b == null test
            b.setInterests(null);
            assertThat(score(a, b).getBreakdown().get("interests")).isZero();
        }
    }

    @Nested
    @DisplayName("hobbies factor (creative-interest overlap)")
    class HobbiesFactor {

        @Test
        @DisplayName("shared creative interest → 100 even when overall interests only partly overlap")
        void sharedCreative() {
            User a = user();
            User b = user();
            a.setInterests(EnumSet.of(Interest.MUSIC, Interest.SPORTS));
            b.setInterests(EnumSet.of(Interest.MUSIC, Interest.CODING));
            assertThat(score(a, b).getBreakdown().get("hobbies")).isEqualTo(100);
        }

        @Test
        @DisplayName("overlap is entirely non-creative → 0")
        void nonCreativeOnly() {
            User a = user();
            User b = user();
            a.setInterests(EnumSet.of(Interest.SPORTS));
            b.setInterests(EnumSet.of(Interest.SPORTS));
            assertThat(score(a, b).getBreakdown().get("hobbies")).isZero();
        }
    }

    @Nested
    @DisplayName("languages factor")
    class LanguagesFactor {

        @Test
        @DisplayName("identical languages → 100")
        void identical() {
            User a = user();
            User b = user();
            a.setLanguages(EnumSet.of(Language.EN, Language.ES));
            b.setLanguages(EnumSet.of(Language.EN, Language.ES));
            assertThat(score(a, b).getBreakdown().get("languages")).isEqualTo(100);
        }

        @Test
        @DisplayName("no shared language → 0")
        void none() {
            User a = user();
            User b = user();
            a.setLanguages(EnumSet.of(Language.EN));
            b.setLanguages(EnumSet.of(Language.FR));
            assertThat(score(a, b).getBreakdown().get("languages")).isZero();
        }
    }

    @Nested
    @DisplayName("age factor")
    class AgeFactor {

        @Test
        @DisplayName("either age null → neutral 50")
        void unknown() {
            User a = user();
            a.setAge(25);
            assertThat(score(a, user()).getBreakdown().get("age")).isEqualTo(50);
        }

        @Test
        @DisplayName("equal ages → 100")
        void equal() {
            User a = user();
            User b = user();
            a.setAge(30);
            b.setAge(30);
            assertThat(score(a, b).getBreakdown().get("age")).isEqualTo(100);
        }

        @Test
        @DisplayName("3-year gap → 80 (1 - 3/15)")
        void smallGap() {
            User a = user();
            User b = user();
            a.setAge(30);
            b.setAge(33);
            assertThat(score(a, b).getBreakdown().get("age")).isEqualTo(80);
        }

        @Test
        @DisplayName("gap of 15+ years → 0")
        void largeGap() {
            User a = user();
            User b = user();
            a.setAge(20);
            b.setAge(40);
            assertThat(score(a, b).getBreakdown().get("age")).isZero();
        }
    }

    @Nested
    @DisplayName("timezone factor (country proxy)")
    class TimezoneFactor {

        @Test
        @DisplayName("either country null → neutral 50")
        void unknown() {
            User a = user();
            a.setCountry("US");
            assertThat(score(a, user()).getBreakdown().get("timezone")).isEqualTo(50);
        }

        @Test
        @DisplayName("same country (case-insensitive) → 100")
        void same() {
            User a = user();
            User b = user();
            a.setCountry("US");
            b.setCountry("us");
            assertThat(score(a, b).getBreakdown().get("timezone")).isEqualTo(100);
        }

        @Test
        @DisplayName("different country → 35")
        void different() {
            User a = user();
            User b = user();
            a.setCountry("US");
            b.setCountry("CA");
            assertThat(score(a, b).getBreakdown().get("timezone")).isEqualTo(35);
        }
    }

    @Nested
    @DisplayName("activity factor")
    class ActivityFactor {

        @Test
        @DisplayName("either last-seen null → 40")
        void unknown() {
            User a = user();
            a.setPresenceLastSeenAt(Instant.now());
            assertThat(score(a, user()).getBreakdown().get("activity")).isEqualTo(40);
        }

        @Test
        @DisplayName("both active now → 100")
        void bothRecent() {
            User a = user();
            User b = user();
            a.setPresenceLastSeenAt(Instant.now());
            b.setPresenceLastSeenAt(Instant.now());
            assertThat(score(a, b).getBreakdown().get("activity")).isEqualTo(100);
        }

        @Test
        @DisplayName("both ~10 days dormant → 60 (recency 0.6 each)")
        void bothDormant() {
            User a = user();
            User b = user();
            a.setPresenceLastSeenAt(Instant.now().minus(Duration.ofDays(10)));
            b.setPresenceLastSeenAt(Instant.now().minus(Duration.ofDays(10)));
            assertThat(score(a, b).getBreakdown().get("activity")).isEqualTo(60);
        }

        @Test
        @DisplayName("both mid-dormant (20 days) → 40 (recency 0.4 each: 15 < days <= 30)")
        void bothMidDormant() {
            User a = user();
            User b = user();
            a.setPresenceLastSeenAt(Instant.now().minus(Duration.ofDays(20)));
            b.setPresenceLastSeenAt(Instant.now().minus(Duration.ofDays(20)));
            assertThat(score(a, b).getBreakdown().get("activity")).isEqualTo(40);
        }

        @Test
        @DisplayName("both long dormant (40 days) → 20 (recency 0.2 each)")
        void bothVeryDormant() {
            User a = user();
            User b = user();
            a.setPresenceLastSeenAt(Instant.now().minus(Duration.ofDays(40)));
            b.setPresenceLastSeenAt(Instant.now().minus(Duration.ofDays(40)));
            assertThat(score(a, b).getBreakdown().get("activity")).isEqualTo(20);
        }
    }

    @Nested
    @DisplayName("personality factor (trait-vector cosine)")
    class PersonalityFactor {

        @Test
        @DisplayName("both empty maps → neutral 50")
        void bothEmpty() {
            assertThat(score(user(), user()).getBreakdown().get("personality")).isEqualTo(50);
        }

        @Test
        @DisplayName("null map on one side → neutral 50")
        void nullMap() {
            User a = user();
            User b = user();
            a.setPersonality(traits(PersonalityTrait.OPENNESS, 80));
            b.setPersonality(null);
            assertThat(score(a, b).getBreakdown().get("personality")).isEqualTo(50);
        }

        @Test
        @DisplayName("identical trait vectors → cosine 1.0 → 100")
        void identicalVectors() {
            User a = user();
            User b = user();
            a.setPersonality(traits(PersonalityTrait.OPENNESS, 80));
            b.setPersonality(traits(PersonalityTrait.OPENNESS, 80));
            assertThat(score(a, b).getBreakdown().get("personality")).isEqualTo(100);
        }

        @Test
        @DisplayName("different trait vectors → strictly between 0 and 100")
        void differentVectors() {
            User a = user();
            User b = user();
            a.setPersonality(traits(PersonalityTrait.OPENNESS, 100));
            b.setPersonality(traits(PersonalityTrait.EMOTIONALITY, 100));
            int p = score(a, b).getBreakdown().get("personality");
            assertThat(p).isGreaterThan(0).isLessThan(100);
        }

        @Test
        @DisplayName("null map on side A → neutral 50 (a == null branch)")
        void nullMapA() {
            User a = user();
            User b = user();
            a.setPersonality(null);                                  // a == null → left disjunct
            b.setPersonality(traits(PersonalityTrait.OPENNESS, 80));
            assertThat(score(a, b).getBreakdown().get("personality")).isEqualTo(50);
        }

        @Test
        @DisplayName("uninitialized lazy map on side A → neutral 50 (!isInitialized(a) branch)")
        void uninitializedMapA() {
            User a = user();
            User b = user();
            a.setPersonality(new PersistentMap<>());                 // wasInitialized() == false
            b.setPersonality(traits(PersonalityTrait.OPENNESS, 80));
            assertThat(score(a, b).getBreakdown().get("personality")).isEqualTo(50);
        }

        @Test
        @DisplayName("uninitialized lazy map on side B → neutral 50 (!isInitialized(b) branch)")
        void uninitializedMapB() {
            User a = user();
            User b = user();
            a.setPersonality(traits(PersonalityTrait.OPENNESS, 80));  // initialized, so eval reaches b
            b.setPersonality(new PersistentMap<>());                 // wasInitialized() == false
            assertThat(score(a, b).getBreakdown().get("personality")).isEqualTo(50);
        }

        @Test
        @DisplayName("non-empty A but empty B map → neutral 50 (b.isEmpty() branch)")
        void nonEmptyAgainstEmpty() {
            User a = user();
            User b = user();                                          // bare user → empty (initialized) map
            a.setPersonality(traits(PersonalityTrait.OPENNESS, 80));
            assertThat(score(a, b).getBreakdown().get("personality")).isEqualTo(50);
        }

        @Test
        @DisplayName("all-zero trait vector on side A → neutral 50 (na == 0 branch)")
        void zeroNormA() {
            User a = user();
            User b = user();
            a.setPersonality(allTraits(0));                          // every trait 0 → na == 0
            b.setPersonality(traits(PersonalityTrait.OPENNESS, 80));
            assertThat(score(a, b).getBreakdown().get("personality")).isEqualTo(50);
        }

        @Test
        @DisplayName("all-zero trait vector on side B → neutral 50 (nb == 0 branch)")
        void zeroNormB() {
            User a = user();
            User b = user();
            a.setPersonality(traits(PersonalityTrait.OPENNESS, 80));  // na != 0 so eval reaches nb == 0
            b.setPersonality(allTraits(0));                          // every trait 0 → nb == 0
            assertThat(score(a, b).getBreakdown().get("personality")).isEqualTo(50);
        }

        private Map<PersonalityTrait, Integer> traits(PersonalityTrait t, int v) {
            Map<PersonalityTrait, Integer> m = new EnumMap<>(PersonalityTrait.class);
            m.put(t, v);
            return m;
        }

        /** Every trait explicitly mapped to {@code v} (defeats the getOrDefault-50 fallback). */
        private Map<PersonalityTrait, Integer> allTraits(int v) {
            Map<PersonalityTrait, Integer> m = new EnumMap<>(PersonalityTrait.class);
            for (PersonalityTrait t : PersonalityTrait.values()) {
                m.put(t, v);
            }
            return m;
        }
    }

    @Nested
    @DisplayName("energy factor")
    class EnergyFactor {

        @Test
        @DisplayName("either null → neutral 50")
        void unknown() {
            User a = user();
            a.setConversationEnergy(ConversationEnergy.FRIENDLY);
            assertThat(score(a, user()).getBreakdown().get("energy")).isEqualTo(50);
        }

        @Test
        @DisplayName("identical energy → 100")
        void identical() {
            User a = user();
            User b = user();
            a.setConversationEnergy(ConversationEnergy.FRIENDLY);
            b.setConversationEnergy(ConversationEnergy.FRIENDLY);
            assertThat(score(a, b).getBreakdown().get("energy")).isEqualTo(100);
        }

        @Test
        @DisplayName("same affinity group → 60")
        void sameGroup() {
            User a = user();
            User b = user();
            a.setConversationEnergy(ConversationEnergy.FRIENDLY);
            b.setConversationEnergy(ConversationEnergy.FUNNY);
            assertThat(score(a, b).getBreakdown().get("energy")).isEqualTo(60);
        }

        @Test
        @DisplayName("cross-group → 25")
        void crossGroup() {
            User a = user();
            User b = user();
            a.setConversationEnergy(ConversationEnergy.FRIENDLY);
            b.setConversationEnergy(ConversationEnergy.ROMANTIC);
            assertThat(score(a, b).getBreakdown().get("energy")).isEqualTo(25);
        }
    }

    @Nested
    @DisplayName("mood factor")
    class MoodFactor {

        @Test
        @DisplayName("either null → neutral 50")
        void unknown() {
            User a = user();
            a.setMood(Mood.FLIRT);
            assertThat(score(a, user()).getBreakdown().get("mood")).isEqualTo(50);
        }

        @Test
        @DisplayName("identical mood → 100")
        void identical() {
            User a = user();
            User b = user();
            a.setMood(Mood.FLIRT);
            b.setMood(Mood.FLIRT);
            assertThat(score(a, b).getBreakdown().get("mood")).isEqualTo(100);
        }

        @Test
        @DisplayName("same cluster → 80")
        void sameCluster() {
            User a = user();
            User b = user();
            a.setMood(Mood.FLIRT);
            b.setMood(Mood.ROMANTIC);
            assertThat(score(a, b).getBreakdown().get("mood")).isEqualTo(80);
        }

        @Test
        @DisplayName("cross cluster → 30")
        void crossCluster() {
            User a = user();
            User b = user();
            a.setMood(Mood.FLIRT);
            b.setMood(Mood.GAMING);
            assertThat(score(a, b).getBreakdown().get("mood")).isEqualTo(30);
        }
    }

    @Nested
    @DisplayName("overall / bucket / explanation / highlights")
    class Presentation {

        @Test
        @DisplayName("breakdown always carries all nine factor keys")
        void breakdownKeys() {
            Map<String, Integer> breakdown = score(user(), user()).getBreakdown();
            assertThat(breakdown).containsOnlyKeys("interests", "hobbies", "languages", "age",
                    "timezone", "activity", "personality", "energy", "mood");
        }

        @Test
        @DisplayName("fully aligned profiles → overall 100, HIGH bucket, rich highlights")
        void strongMatch() {
            User a = alignedUser();
            User b = alignedUser();

            CompatibilityScore s = score(a, b);

            assertThat(s.getOverall()).isEqualTo(100);
            assertThat(s.getBucket()).isEqualTo("HIGH");
            assertThat(s.getBreakdown().values()).allMatch(v -> v == 100);
            assertThat(s.getExplanation()).startsWith("Strong match.");
            assertThat(s.getHighlights())
                    .anyMatch(h -> h.startsWith("You both love"))
                    .contains("You both speak En and Es")
                    .contains("Matching friendly energy")
                    .contains("You're in a similar mood right now")
                    .contains("You're both in US")
                    .contains("You're close in age");
        }

        @Test
        @DisplayName("moderate overlap → MEDIUM bucket, 'Good match.' lead")
        void mediumMatch() {
            User a = user();
            User b = user();
            // Shared interests+hobbies only; everything else neutral/mismatched.
            a.setInterests(EnumSet.of(Interest.MUSIC, Interest.ART));
            b.setInterests(EnumSet.of(Interest.MUSIC, Interest.ART));

            CompatibilityScore s = score(a, b);

            assertThat(s.getOverall()).isBetween(45, 69);
            assertThat(s.getBucket()).isEqualTo("MEDIUM");
            assertThat(s.getExplanation()).startsWith("Good match.");
        }

        @Test
        @DisplayName("poor overlap → LOW bucket, no highlights, generic explanation")
        void lowMatch() {
            User a = user();
            User b = user();
            a.setAge(20);
            b.setAge(40);                       // age → 0
            a.setCountry("US");
            b.setCountry("CA");                 // timezone → 0.35
            a.setConversationEnergy(ConversationEnergy.FRIENDLY);
            b.setConversationEnergy(ConversationEnergy.ROMANTIC); // energy → 0.25
            a.setMood(Mood.FLIRT);
            b.setMood(Mood.GAMING);             // mood → 0.30

            CompatibilityScore s = score(a, b);

            assertThat(s.getOverall()).isLessThan(45);
            assertThat(s.getBucket()).isEqualTo("LOW");
            assertThat(s.getHighlights()).isEmpty();
            assertThat(s.getExplanation()).isEqualTo("Some things in common.");
        }

        /** A profile that scores 100 on every factor against a copy of itself. */
        private User alignedUser() {
            User u = user();
            u.setInterests(EnumSet.of(Interest.MUSIC, Interest.ART, Interest.GAMING));
            u.setLanguages(EnumSet.of(Language.EN, Language.ES));
            u.setAge(25);
            u.setCountry("US");
            u.setPresenceLastSeenAt(Instant.now());
            Map<PersonalityTrait, Integer> p = new HashMap<>();
            p.put(PersonalityTrait.OPENNESS, 80);
            u.setPersonality(p);
            u.setConversationEnergy(ConversationEnergy.FRIENDLY);
            u.setMood(Mood.FLIRT);
            return u;
        }
    }

    private CompatibilityScore score(User a, User b) {
        return service.score(a, b);
    }
}
