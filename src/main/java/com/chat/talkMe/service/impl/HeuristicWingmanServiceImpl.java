package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.CompatibilityScore;
import com.chat.talkMe.enums.Language;
import com.chat.talkMe.service.CompatibilityService;
import com.chat.talkMe.service.WingmanService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Default, heuristic implementation of {@link WingmanService}. No LLM and no I/O beyond
 * the two {@link User} entities, so it is safe to call on hot match surfaces. Marked
 * {@code @Primary} so it wins over any future provider bean unless that one is explicitly
 * preferred via configuration ({@code wingman.provider}).
 */
@Service
@Primary
@RequiredArgsConstructor
public class HeuristicWingmanServiceImpl implements WingmanService {

    private final CompatibilityService compatibilityService;

    // ── Static template banks ────────────────────────────────────────────────────

    /** Generic openers used when we have no shared signal to lean on. */
    private static final List<String> GENERIC_ICEBREAKERS = List.of(
            "Hey! What's keeping you up tonight?",
            "If you could be anywhere right now, where would it be?",
            "What's the best thing that happened to you this week?",
            "Coffee or late-night walks — which are you?",
            "What's a song you have on repeat right now?",
            "Tell me something most people don't know about you.");

    /** Answer-style replies for when the other person asked a question. */
    private static final List<String> ANSWER_STYLE = List.of(
            "Good question — let me think about that for a sec.",
            "Honestly? Probably not what you'd expect. What about you?",
            "Depends on the day — but I'd love to hear your take first.",
            "Ha, that's a fun one. Here's my honest answer...");

    /** Openers for short/greeting-style incoming messages. */
    private static final List<String> OPENER_STYLE = List.of(
            "Hey there! How's your night going?",
            "Hi! Perfect timing — I was just about to say hello.",
            "Hey :) what are you up to right now?",
            "Hello! What brought you here tonight?");

    /** Follow-up style replies to keep an ongoing thread alive. */
    private static final List<String> FOLLOWUP_STYLE = List.of(
            "That's really interesting — tell me more about that.",
            "Love that. What got you into it?",
            "Same here, honestly. How long have you felt that way?",
            "Okay now I'm curious — what happened next?");

    // ── Icebreakers ──────────────────────────────────────────────────────────────

    @Override
    public List<String> icebreakers(User a, User b, int max) {
        if (max <= 0 || a == null || b == null) return List.of();

        // De-duplicating, insertion-ordered accumulator.
        Set<String> out = new LinkedHashSet<>();

        // 1) Derive from compatibility highlights (already human-readable copy).
        CompatibilityScore score = compatibilityService.score(a, b);
        if (score != null && score.getHighlights() != null) {
            for (String h : score.getHighlights()) {
                if (out.size() >= max) break;
                out.add(highlightToOpener(h));
            }
        }

        // 2) Add targeted templates from concrete shared signals.
        for (String opener : sharedSignalOpeners(a, b)) {
            if (out.size() >= max) break;
            out.add(opener);
        }

        // 3) Backfill with generic openers so we always return something useful.
        for (String g : GENERIC_ICEBREAKERS) {
            if (out.size() >= max) break;
            out.add(g);
        }

        return new ArrayList<>(out).subList(0, Math.min(max, out.size()));
    }

    /** Turn a compatibility highlight ("You both love Music") into a conversational opener. */
    private static String highlightToOpener(String highlight) {
        if (highlight == null || highlight.isBlank()) return "What are you into these days?";
        String h = highlight.trim();
        String lower = h.toLowerCase(Locale.ROOT);
        if (lower.startsWith("you both love")) {
            return h + " — what got you into it?";
        }
        if (lower.startsWith("you both speak")) {
            return h + ". Say something in it!";
        }
        if (lower.startsWith("you're both in")) {
            return h + " — any hidden gems around there?";
        }
        if (lower.contains("mood")) {
            return "Seems like we're on the same wavelength tonight — what's on your mind?";
        }
        if (lower.contains("energy")) {
            return "I get a good vibe from you — what's your ideal way to spend a night?";
        }
        // Fallback: pose the highlight back as a light prompt.
        return h + " — tell me more?";
    }

    /** Concrete, template-based openers built from shared interests / languages / mood. */
    private static List<String> sharedSignalOpeners(User a, User b) {
        List<String> out = new ArrayList<>();

        String sharedInterest = firstShared(a.getInterests(), b.getInterests());
        if (sharedInterest != null) {
            String pretty = prettify(sharedInterest);
            out.add("I saw we both like " + pretty + " — what's your favourite thing about it?");
        }

        String sharedLanguage = firstSharedLang(a.getLanguages(), b.getLanguages());
        if (sharedLanguage != null) {
            out.add("We both speak " + prettify(sharedLanguage) + " — teach me your favourite phrase in it?");
        }

        if (a.getMood() != null && b.getMood() != null && a.getMood() == b.getMood()) {
            out.add("Looks like we're both in a " + prettify(a.getMood().name())
                    + " mood tonight — what does that look like for you?");
        }

        return out;
    }

    private static <E extends Enum<E>> String firstShared(Set<E> a, Set<E> b) {
        if (a == null || b == null) return null;
        for (E e : a) {
            if (b.contains(e)) return e.name();
        }
        return null;
    }

    // Kept separate so the two enum-typed collections resolve without unchecked mixing.
    private static String firstSharedLang(Set<Language> a, Set<Language> b) {
        return firstShared(a, b);
    }

    // ── Reply suggestions ────────────────────────────────────────────────────────

    @Override
    public List<String> replySuggestions(String lastMessageText, int max) {
        if (max <= 0) return List.of();
        String text = lastMessageText == null ? "" : lastMessageText.trim();

        List<String> bank;
        if (text.isEmpty()) {
            bank = OPENER_STYLE;
        } else if (text.contains("?")) {
            bank = ANSWER_STYLE;
        } else if (isShortOrGreeting(text)) {
            bank = OPENER_STYLE;
        } else {
            bank = FOLLOWUP_STYLE;
        }

        return bank.subList(0, Math.min(max, bank.size()));
    }

    // ── Rewrite my message ───────────────────────────────────────────────────────

    /** Tone → (prefix, suffix) decoration templates for the heuristic rewriter. */
    private static final java.util.Map<String, String[]> TONE_TEMPLATES = java.util.Map.of(
            "friendly", new String[]{"Hey! ", " 😊"},
            "flirty", new String[]{"", " 😉"},
            "casual", new String[]{"", " haha"},
            "confident", new String[]{"", "."},
            "warm", new String[]{"", " — really glad we're talking."},
            "playful", new String[]{"Okay so… ", " 😄"});

    @Override
    public List<String> rewrite(String draft, String tone, int max) {
        if (max <= 0) return List.of();
        String base = draft == null ? "" : draft.trim();
        if (base.isEmpty()) return List.of();

        String core = normalizeCore(base);
        String key = tone == null ? "friendly" : tone.trim().toLowerCase(Locale.ROOT);

        Set<String> out = new LinkedHashSet<>();

        // 1) The requested tone first (if known).
        String[] chosen = TONE_TEMPLATES.get(key);
        if (chosen != null) {
            out.add(applyTone(core, chosen));
        }

        // 2) A softened / more-open variant that invites a reply.
        out.add(capitalize(core) + (endsWithPunctuation(core) ? "" : ".") + " What do you think?");

        // 3) A concise, cleaned-up variant (the core, tidied).
        out.add(capitalize(core) + (endsWithPunctuation(core) ? "" : "."));

        // 4) Backfill with the other tone templates so we always offer variety.
        for (var entry : TONE_TEMPLATES.entrySet()) {
            if (out.size() >= max) break;
            out.add(applyTone(core, entry.getValue()));
        }

        return new ArrayList<>(out).subList(0, Math.min(max, out.size()));
    }

    /** Strip a leading greeting and trailing filler so tone templates read cleanly. */
    private static String normalizeCore(String text) {
        String t = text.trim();
        // Collapse runs of whitespace.
        t = t.replaceAll("\\s+", " ");
        return t;
    }

    private static String applyTone(String core, String[] template) {
        String body = template[0].isEmpty() ? capitalize(core) : core;
        String result = template[0] + body;
        if (!endsWithPunctuation(result)) {
            result = result + template[1];
        } else if (!template[1].isBlank() && template[1].trim().length() > 1) {
            // Keep an emoji/clause suffix even when the body already ends in punctuation.
            result = result + template[1];
        }
        return result.trim();
    }

    private static boolean endsWithPunctuation(String s) {
        if (s == null || s.isEmpty()) return false;
        char c = s.charAt(s.length() - 1);
        return c == '.' || c == '!' || c == '?' || c == '…';
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static boolean isShortOrGreeting(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        // Very short messages read as openers rather than substantive turns.
        if (text.length() <= 12) return true;
        String stripped = lower.replaceAll("[^a-z ]", "").trim();
        return stripped.equals("hi") || stripped.equals("hey") || stripped.equals("hello")
                || stripped.startsWith("hi ") || stripped.startsWith("hey ")
                || stripped.startsWith("hello ") || stripped.startsWith("good morning")
                || stripped.startsWith("good evening") || stripped.startsWith("good night");
    }

    // ── shared formatting ────────────────────────────────────────────────────────

    private static String prettify(String enumName) {
        String lower = enumName.toLowerCase(Locale.ROOT).replace('_', ' ');
        return lower.substring(0, 1).toUpperCase(Locale.ROOT) + lower.substring(1);
    }
}
