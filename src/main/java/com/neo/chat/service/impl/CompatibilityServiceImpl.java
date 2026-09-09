package com.neo.chat.service.impl;

import com.neo.chat.config.CompatibilityProperties;
import com.neo.chat.domain.User;
import com.neo.chat.dto.response.CompatibilityScore;
import com.neo.chat.enums.ConversationEnergy;
import com.neo.chat.enums.Interest;
import com.neo.chat.enums.Mood;
import com.neo.chat.enums.PersonalityTrait;
import com.neo.chat.service.CompatibilityService;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic, weighted compatibility scoring. Every factor returns 0..1; the overall
 * score is the weighted mean scaled to 0..100. No LLM and no I/O beyond the two entities,
 * so it's cheap to call in matching hot paths and safe to reuse everywhere.
 */
@Service
@RequiredArgsConstructor
public class CompatibilityServiceImpl implements CompatibilityService {

    private final CompatibilityProperties weights;

    /**
     * Interests that read as "hobbies / creative / music" for the secondary overlap factor.
     */
    private static final EnumSet<Interest> CREATIVE = EnumSet.of(
            Interest.MUSIC, Interest.ART, Interest.DANCE, Interest.PHOTOGRAPHY, Interest.WRITING,
            Interest.FILMMAKING, Interest.COOKING, Interest.GAMING, Interest.BOARD_GAMES, Interest.COMEDY);

    // Energy affinity groups — same group scores higher than cross-group.
    private static final List<EnumSet<ConversationEnergy>> ENERGY_GROUPS = List.of(
            EnumSet.of(ConversationEnergy.FRIENDLY, ConversationEnergy.FUNNY, ConversationEnergy.CHILL, ConversationEnergy.EXTROVERT),
            EnumSet.of(ConversationEnergy.ROMANTIC, ConversationEnergy.FLIRTY, ConversationEnergy.EMOTIONAL),
            EnumSet.of(ConversationEnergy.DEEP, ConversationEnergy.INTELLIGENT, ConversationEnergy.INTROVERT),
            EnumSet.of(ConversationEnergy.SARCASTIC, ConversationEnergy.CHAOTIC));

    // Mood affinity clusters.
    private static final List<EnumSet<Mood>> MOOD_CLUSTERS = List.of(
            EnumSet.of(Mood.FLIRT, Mood.ROMANTIC, Mood.DATING),
            EnumSet.of(Mood.LOOKING_FOR_FRIENDS, Mood.CASUAL, Mood.COFFEE_CHAT, Mood.HAPPY, Mood.PASSING_TIME, Mood.BORED),
            EnumSet.of(Mood.DEEP, Mood.RELATIONSHIP_ADVICE, Mood.CANT_SLEEP, Mood.NEED_TO_LISTEN),
            EnumSet.of(Mood.GAMING, Mood.MOVIES, Mood.MUSIC),
            EnumSet.of(Mood.VOICE_CALLS, Mood.VIDEO_CALLS),
            EnumSet.of(Mood.TRAVEL, Mood.STUDY_PARTNER));

    /**
     * Computes the weighted 0..100 compatibility between two users across nine factors
     * (interests, hobbies, languages, age, timezone, activity, personality, energy, mood),
     * returning the overall score plus per-factor breakdown, human highlights, explanation
     * and HIGH/MEDIUM/LOW bucket. Pure and deterministic — no LLM or I/O. The personality factor
     * only counts when both users' LAZY {@code personality} maps are initialised (see
     * {@code UserRepository#findByUuidWithPersonality}); on detached instances it scores neutral.
     *
     * @param a first user
     * @param b second user
     * @return the assembled compatibility score DTO
     */
    @Override
    public CompatibilityScore score(User a, User b) {
        double fInterests = jaccard(a.getInterests(), b.getInterests());
        double fHobbies = jaccard(intersectType(a.getInterests()), intersectType(b.getInterests()));
        double fLanguages = jaccard(a.getLanguages(), b.getLanguages());
        double fAge = ageScore(a.getAge(), b.getAge());
        double fTimezone = timezoneScore(a.getCountry(), b.getCountry());
        double fActivity = activityScore(a.getPresenceLastSeenAt(), b.getPresenceLastSeenAt());
        double fPersonality = personalityScore(a.getPersonality(), b.getPersonality());
        double fEnergy = energyScore(a.getConversationEnergy(), b.getConversationEnergy());
        double fMood = moodScore(a.getMood(), b.getMood());

        double weighted =
                fInterests * weights.getInterests()
                        + fHobbies * weights.getHobbies()
                        + fLanguages * weights.getLanguages()
                        + fAge * weights.getAge()
                        + fTimezone * weights.getTimezone()
                        + fActivity * weights.getActivity()
                        + fPersonality * weights.getPersonality()
                        + fEnergy * weights.getEnergy()
                        + fMood * weights.getMood();
        int total = Math.max(1, weights.total());
        int overall = (int) Math.round((weighted / total) * 100.0);
        overall = Math.clamp(overall, 0, 100);

        Map<String, Integer> breakdown = new LinkedHashMap<>();
        breakdown.put("interests", pct(fInterests));
        breakdown.put("hobbies", pct(fHobbies));
        breakdown.put("languages", pct(fLanguages));
        breakdown.put("age", pct(fAge));
        breakdown.put("timezone", pct(fTimezone));
        breakdown.put("activity", pct(fActivity));
        breakdown.put("personality", pct(fPersonality));
        breakdown.put("energy", pct(fEnergy));
        breakdown.put("mood", pct(fMood));

        List<String> highlights = buildHighlights(a, b, fLanguages, fAge, fEnergy, fMood, fTimezone);
        List<String> commonalities = buildCommonalities(a, b, fEnergy, fMood, fTimezone);

        return CompatibilityScore.builder()
                .overall(overall)
                .breakdown(breakdown)
                .highlights(highlights)
                .explanation(explain(overall, highlights))
                .bucket(bucket(overall))
                .commonalities(commonalities)
                .build();
    }

    // ── factors ─────────────────────────────────────────────────────────────────

    /**
     * Jaccard overlap (|intersection| / |union|) of two enum sets; 0 when either is null/empty.
     *
     * @param a   first set
     * @param b   second set
     * @param <E> enum type
     * @return overlap in 0..1
     */
    private static <E extends Enum<E>> double jaccard(Set<E> a, Set<E> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0.0;
        Set<E> inter = EnumSet.copyOf(a);
        inter.retainAll(b);
        if (inter.isEmpty()) return 0.0;
        Set<E> union = EnumSet.copyOf(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }

    /**
     * Narrows a user's interests to the "creative / hobbies / music" subset used by the
     * secondary hobby-overlap factor.
     *
     * @param interests the user's full interest set (maybe null)
     * @return the creative subset (empty when none)
     */
    private static Set<Interest> intersectType(Set<Interest> interests) {
        if (interests == null || interests.isEmpty()) return Collections.emptySet();
        EnumSet<Interest> out = EnumSet.noneOf(Interest.class);
        for (Interest i : interests) {
            if (CREATIVE.contains(i)) out.add(i);
        }
        return out;
    }

    /**
     * Age closeness: 1.0 identical, linearly decaying to 0 at a 15-year gap; 0.5 when unknown.
     *
     * @param a first age (nullable)
     * @param b second age (nullable)
     * @return score in 0..1
     */
    private static double ageScore(Integer a, Integer b) {
        if (a == null || b == null) return 0.5; // unknown → neutral
        return 1.0 - Math.min(1.0, Math.abs(a - b) / 15.0);
    }

    /**
     * Country/timezone proxy: 1.0 same country, 0.35 different, 0.5 when either unknown.
     *
     * @param countryA first country (nullable)
     * @param countryB second country (nullable)
     * @return score in 0..1
     */
    private static double timezoneScore(String countryA, String countryB) {
        if (countryA == null || countryB == null) return 0.5;
        return countryA.equalsIgnoreCase(countryB) ? 1.0 : 0.35;
    }

    /**
     * Mean of both users' last-seen recency; 0.4 when either timestamp is unknown.
     *
     * @param a first user's last-seen instant (nullable)
     * @param b second user's last-seen instant (nullable)
     * @return score in 0..1
     */
    private static double activityScore(Instant a, Instant b) {
        if (a == null || b == null) return 0.4;
        Instant now = Instant.now();
        double ra = recency(Duration.between(a, now));
        double rb = recency(Duration.between(b, now));
        // Both recently active scores highest; one dormant drags it down.
        return (ra + rb) / 2.0;
    }

    /**
     * Stepped recency weight from a duration since last activity (<=3d=1.0 down to 0.2).
     *
     * @param since elapsed time since last active
     * @return weight in 0..1
     */
    private static double recency(Duration since) {
        long days = Math.max(0, since.toDays());
        if (days <= 3) return 1.0;
        if (days <= 14) return 0.6;
        if (days <= 30) return 0.4;
        return 0.2;
    }

    /**
     * Cosine similarity of the two personality-trait vectors (missing traits default to 50).
     * Returns a neutral 0.5 when either map is null, empty, or a LAZY collection that is not
     * Hibernate-initialized — guarding against LazyInitializationException on detached entities
     * (e.g. the matchmaking WebSocket path with no open session).
     *
     * @param a first user's trait map
     * @param b second user's trait map
     * @return similarity in 0..1
     */
    private static double personalityScore(Map<PersonalityTrait, Integer> a, Map<PersonalityTrait, Integer> b) {
        // These maps are LAZY @ElementCollections; on the matchmaking WebSocket path the
        // User entities are detached (no OSIV / tx), so touching an uninitialized map would
        // throw LazyInitializationException. Treat uninitialized/absent as "unknown → neutral".
        if (a == null || b == null
                || !Hibernate.isInitialized(a) || !Hibernate.isInitialized(b)
                || a.isEmpty() || b.isEmpty()) return 0.5;
        double dot = 0, na = 0, nb = 0;
        for (PersonalityTrait t : PersonalityTrait.values()) {
            double va = a.getOrDefault(t, 50);
            double vb = b.getOrDefault(t, 50);
            dot += va * vb;
            na += va * va;
            nb += vb * vb;
        }
        if (na == 0 || nb == 0) return 0.5;
        return Math.clamp(dot / (Math.sqrt(na) * Math.sqrt(nb)), 0.0, 1.0);
    }

    /**
     * Conversation-energy affinity: 1.0 identical, 0.6 same affinity group, 0.25 otherwise;
     * 0.5 when either is null.
     *
     * @param a first user's energy (nullable)
     * @param b second user's energy (nullable)
     * @return score in 0..1
     */
    private static double energyScore(ConversationEnergy a, ConversationEnergy b) {
        if (a == null || b == null) return 0.5;
        if (a == b) return 1.0;
        for (EnumSet<ConversationEnergy> g : ENERGY_GROUPS) {
            if (g.contains(a) && g.contains(b)) return 0.6;
        }
        return 0.25;
    }

    /**
     * Mood affinity: 1.0 identical, 0.8 same cluster, 0.3 otherwise; 0.5 when either is null.
     *
     * @param a first user's mood (nullable)
     * @param b second user's mood (nullable)
     * @return score in 0..1
     */
    private static double moodScore(Mood a, Mood b) {
        if (a == null || b == null) return 0.5;
        if (a == b) return 1.0;
        for (EnumSet<Mood> c : MOOD_CLUSTERS) {
            if (c.contains(a) && c.contains(b)) return 0.8;
        }
        return 0.3;
    }

    // ── presentation ──────────────────────────────────────────────────────────────

    /**
     * Clamps a 0..1 factor and rounds it to an integer percentage.
     *
     * @param factor raw factor
     * @return percentage in 0..100
     */
    private static int pct(double factor) {
        return (int) Math.round(Math.clamp(factor, 0.0, 1.0) * 100.0);
    }

    /**
     * Builds the short human-readable highlight lines (shared interests/languages, matching
     * energy/mood, same country, close in age) from the users and pre-computed factors.
     *
     * @param a         first user
     * @param b         second user
     * @param fLang     language factor (unused directly; overlap recomputed by name)
     * @param fAge      age factor
     * @param fEnergy   energy factor
     * @param fMood     mood factor
     * @param fTimezone timezone/country factor
     * @return ordered highlight strings (possibly empty)
     */
    private List<String> buildHighlights(User a, User b, double fLang, double fAge,
                                         double fEnergy, double fMood, double fTimezone) {
        List<String> out = new ArrayList<>();
        List<String> sharedInterests = sharedNames(a.getInterests(), b.getInterests(), 3);
        if (!sharedInterests.isEmpty()) {
            out.add("You both love " + humanJoin(sharedInterests));
        }
        List<String> sharedLanguages = sharedNames(a.getLanguages(), b.getLanguages(), 2);
        if (!sharedLanguages.isEmpty()) {
            out.add("You both speak " + humanJoin(sharedLanguages));
        }
        if (fEnergy >= 0.9 && a.getConversationEnergy() != null) {
            out.add("Matching " + a.getConversationEnergy().name().toLowerCase() + " energy");
        }
        if (fMood >= 0.8 && a.getMood() != null && b.getMood() != null) {
            out.add("You're in a similar mood right now");
        }
        if (fTimezone >= 1.0 && a.getCountry() != null) {
            out.add("You're both in " + a.getCountry());
        }
        if (fAge >= 0.85) {
            out.add("You're close in age");
        }
        return out;
    }

    /**
     * Builds the itemized list of concrete shared things (for the "things in common" count),
     * reusing {@link #sharedNames(Set, Set, int)} for every enum-set category: shared interests,
     * shared languages and shared "looking for" tags — plus the shared country (when equal) and a
     * same/adjacent mood or energy label. Uncapped so the UI can count every commonality. Distinct
     * from {@link #buildHighlights} copy; does not affect scoring.
     *
     * @param a         first user
     * @param b         second user
     * @param fEnergy   energy factor (>=0.6 ⇒ same/adjacent energy)
     * @param fMood     mood factor (>=0.8 ⇒ same/adjacent mood)
     * @param fTimezone timezone/country factor (>=1.0 ⇒ same country)
     * @return ordered list of shared items (possibly empty, never null)
     */
    private List<String> buildCommonalities(User a, User b, double fEnergy, double fMood, double fTimezone) {
        List<String> out = new ArrayList<>();
        out.addAll(sharedNames(a.getInterests(), b.getInterests(), Integer.MAX_VALUE));
        out.addAll(sharedNames(a.getLanguages(), b.getLanguages(), Integer.MAX_VALUE));
        out.addAll(sharedNames(a.getLookingFor(), b.getLookingFor(), Integer.MAX_VALUE));
        if (fTimezone >= 1.0 && a.getCountry() != null) {
            out.add(a.getCountry());
        }
        if (fMood >= 0.8 && a.getMood() != null) {
            out.add(prettify(a.getMood().name()) + " mood");
        }
        if (fEnergy >= 0.6 && a.getConversationEnergy() != null) {
            out.add(prettify(a.getConversationEnergy().name()) + " energy");
        }
        return out;
    }

    /**
     * Up to {@code max} prettified names present in both enum sets, in {@code a}'s iteration order.
     *
     * @param a   first set (nullable)
     * @param b   second set (nullable)
     * @param max cap on returned names
     * @param <E> enum type
     * @return shared names (empty when none)
     */
    private static <E extends Enum<E>> List<String> sharedNames(Set<E> a, Set<E> b, int max) {
        if (a == null || b == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (E e : a) {
            if (b.contains(e)) {
                out.add(prettify(e.name()));
                if (out.size() >= max) break;
            }
        }
        return out;
    }

    /**
     * One-sentence explanation: a strength lead keyed off the overall score plus up to three
     * highlights.
     *
     * @param overall    overall 0..100 score
     * @param highlights highlight lines
     * @return explanation sentence
     */
    private static String explain(int overall, List<String> highlights) {
        String lead = overall >= 75 ? "Strong match. "
                : overall >= 50 ? "Good match. "
                  : "Some things in common. ";
        if (highlights.isEmpty()) return lead.trim();
        return lead + humanJoin(highlights.subList(0, Math.min(3, highlights.size()))) + ".";
    }

    /**
     * Maps the overall score to a HIGH (>=70) / MEDIUM (>=45) / LOW bucket label.
     *
     * @param overall overall 0..100 score
     * @return bucket label
     */
    private static String bucket(int overall) {
        return overall >= 70 ? "HIGH" : overall >= 45 ? "MEDIUM" : "LOW";
    }

    /**
     * Turns an ENUM_NAME into an "Enum name" title-ish label.
     *
     * @param enumName raw enum constant name
     * @return prettified label
     */
    private static String prettify(String enumName) {
        String lower = enumName.toLowerCase().replace('_', ' ');
        return lower.substring(0, 1).toUpperCase() + lower.substring(1);
    }

    /**
     * Joins items with commas and a trailing "and" (e.g. "A, B and C").
     *
     * @param items items to join
     * @return the joined phrase ("" when empty)
     */
    private static String humanJoin(List<String> items) {
        if (items.isEmpty()) return "";
        if (items.size() == 1) return items.get(0);
        if (items.size() == 2) return items.get(0) + " and " + items.get(1);
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.getLast();
    }
}
