package com.chat.talkMe.service.impl;

import com.chat.talkMe.config.CompatibilityProperties;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.CompatibilityScore;
import com.chat.talkMe.enums.ConversationEnergy;
import com.chat.talkMe.enums.Interest;
import com.chat.talkMe.enums.Mood;
import com.chat.talkMe.enums.PersonalityTrait;
import com.chat.talkMe.service.CompatibilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Deterministic, weighted compatibility scoring. Every factor returns 0..1; the overall
 * score is the weighted mean scaled to 0..100. No LLM and no I/O beyond the two entities,
 * so it's cheap to call in matching hot paths and safe to reuse everywhere.
 */
@Service
@RequiredArgsConstructor
public class CompatibilityServiceImpl implements CompatibilityService {

    private final CompatibilityProperties weights;

    /** Interests that read as "hobbies / creative / music" for the secondary overlap factor. */
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
        overall = Math.max(0, Math.min(100, overall));

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

        return CompatibilityScore.builder()
                .overall(overall)
                .breakdown(breakdown)
                .highlights(highlights)
                .explanation(explain(overall, highlights))
                .bucket(bucket(overall))
                .build();
    }

    // ── factors ─────────────────────────────────────────────────────────────────

    private static <E extends Enum<E>> double jaccard(Set<E> a, Set<E> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0.0;
        Set<E> inter = EnumSet.copyOf(a);
        inter.retainAll(b);
        if (inter.isEmpty()) return 0.0;
        Set<E> union = EnumSet.copyOf(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }

    private static Set<Interest> intersectType(Set<Interest> interests) {
        if (interests == null || interests.isEmpty()) return Collections.emptySet();
        EnumSet<Interest> out = EnumSet.noneOf(Interest.class);
        for (Interest i : interests) {
            if (CREATIVE.contains(i)) out.add(i);
        }
        return out;
    }

    private static double ageScore(Integer a, Integer b) {
        if (a == null || b == null) return 0.5; // unknown → neutral
        return 1.0 - Math.min(1.0, Math.abs(a - b) / 15.0);
    }

    private static double timezoneScore(String countryA, String countryB) {
        if (countryA == null || countryB == null) return 0.5;
        return countryA.equalsIgnoreCase(countryB) ? 1.0 : 0.35;
    }

    private static double activityScore(Instant a, Instant b) {
        if (a == null || b == null) return 0.4;
        Instant now = Instant.now();
        double ra = recency(Duration.between(a, now));
        double rb = recency(Duration.between(b, now));
        // Both recently active scores highest; one dormant drags it down.
        return (ra + rb) / 2.0;
    }

    private static double recency(Duration since) {
        long days = Math.max(0, since.toDays());
        if (days <= 3) return 1.0;
        if (days <= 14) return 0.6;
        if (days <= 30) return 0.4;
        return 0.2;
    }

    private static double personalityScore(Map<PersonalityTrait, Integer> a, Map<PersonalityTrait, Integer> b) {
        // These maps are LAZY @ElementCollections; on the matchmaking WebSocket path the
        // User entities are detached (no OSIV / tx), so touching an uninitialized map would
        // throw LazyInitializationException. Treat uninitialized/absent as "unknown → neutral".
        if (a == null || b == null
                || !org.hibernate.Hibernate.isInitialized(a) || !org.hibernate.Hibernate.isInitialized(b)
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
        return Math.max(0.0, Math.min(1.0, dot / (Math.sqrt(na) * Math.sqrt(nb))));
    }

    private static double energyScore(ConversationEnergy a, ConversationEnergy b) {
        if (a == null || b == null) return 0.5;
        if (a == b) return 1.0;
        for (EnumSet<ConversationEnergy> g : ENERGY_GROUPS) {
            if (g.contains(a) && g.contains(b)) return 0.6;
        }
        return 0.25;
    }

    private static double moodScore(Mood a, Mood b) {
        if (a == null || b == null) return 0.5;
        if (a == b) return 1.0;
        for (EnumSet<Mood> c : MOOD_CLUSTERS) {
            if (c.contains(a) && c.contains(b)) return 0.8;
        }
        return 0.3;
    }

    // ── presentation ──────────────────────────────────────────────────────────────

    private static int pct(double factor) {
        return (int) Math.round(Math.max(0.0, Math.min(1.0, factor)) * 100.0);
    }

    private List<String> buildHighlights(User a, User b, double fLang, double fAge,
                                         double fEnergy, double fMood, double fTimezone) {
        List<String> out = new ArrayList<>();
        List<String> sharedInterests = sharedNames(a.getInterests(), b.getInterests(), 3);
        if (!sharedInterests.isEmpty()) {
            out.add("You both love " + humanJoin(sharedInterests));
        }
        List<String> sharedLangs = sharedNames(a.getLanguages(), b.getLanguages(), 2);
        if (!sharedLangs.isEmpty()) {
            out.add("You both speak " + humanJoin(sharedLangs));
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

    private static String explain(int overall, List<String> highlights) {
        String lead = overall >= 75 ? "Strong match. "
                : overall >= 50 ? "Good match. "
                : "Some things in common. ";
        if (highlights.isEmpty()) return lead.trim();
        return lead + humanJoin(highlights.subList(0, Math.min(3, highlights.size()))) + ".";
    }

    private static String bucket(int overall) {
        return overall >= 70 ? "HIGH" : overall >= 45 ? "MEDIUM" : "LOW";
    }

    private static String prettify(String enumName) {
        String lower = enumName.toLowerCase().replace('_', ' ');
        return lower.substring(0, 1).toUpperCase() + lower.substring(1);
    }

    private static String humanJoin(List<String> items) {
        if (items.isEmpty()) return "";
        if (items.size() == 1) return items.get(0);
        if (items.size() == 2) return items.get(0) + " and " + items.get(1);
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
    }
}
