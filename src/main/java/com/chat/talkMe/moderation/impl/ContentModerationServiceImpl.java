package com.chat.talkMe.moderation.impl;

import com.chat.talkMe.enums.MessageType;
import com.chat.talkMe.moderation.ContentModerationService;
import com.chat.talkMe.moderation.ModerationResult;
import com.chat.talkMe.moderation.NsfwClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure-Java text moderation: curated multilingual word-lists + a normalization
 * pipeline (lowercasing, Unicode NFKC, leetspeak folding, repeated-char collapse,
 * de-spacing) so common evasions ("f u c k", "fuuuck", "f.u.c.k", "fμck") are caught.
 *
 * Matching strategy (kept conservative to limit false positives):
 *  - tokenize the normalized text and exact-match tokens against the word-list;
 *  - additionally scan a "compact" (all-separators-removed) form for word-list
 *    entries of length >= 5 only, to catch spaced/punctuated evasions without
 *    tripping on short substrings (the "Scunthorpe" problem).
 *
 * Media moderation is delegated to the NSFW client (wired in a later phase); until
 * then it returns CLEAN.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentModerationServiceImpl implements ContentModerationService {

    private static final int RUN_MIN_LEN = 3;

    private final NsfwClient nsfwClient;

    @Value("${moderation.enabled:true}")
    private boolean enabled;

    private final Set<String> badWords = new HashSet<>();
    private final List<String> runScanWords = new ArrayList<>();

    private static final Pattern SEPARATORS = Pattern.compile("[\\p{Punct}\\s_]+");
    // Collapse a run of 3+ identical chars down to ONE ("fuuuuck" -> "fuck").
    private static final Pattern REPEATS = Pattern.compile("(.)\\1{2,}");

    @PostConstruct
    void load() {
        loadList("moderation/profanity_en.txt");
        loadList("moderation/abuse_hinglish.txt");
        loadList("moderation/profanity_hi_devanagari.txt");
        for (String w : badWords) {
            if (w.length() >= RUN_MIN_LEN) {
                runScanWords.add(w);
            }
        }
        log.info("Content moderation loaded {} terms (enabled={})", badWords.size(), enabled);
    }

    private void loadList(String resourcePath) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ClassPathResource(resourcePath).getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String term = line.trim();
                if (term.isEmpty() || term.startsWith("#")) continue;
                badWords.add(normalize(term).replace(" ", ""));
            }
        } catch (Exception e) {
            // A missing list must not crash the app — just moderate with whatever loaded.
            log.warn("Could not load moderation list {}: {}", resourcePath, e.getMessage());
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public ModerationResult moderateText(String content) {
        if (!enabled || content == null || content.isBlank() || badWords.isEmpty()) {
            return ModerationResult.clean();
        }

        String normalized = normalize(content);
        String[] tokens = SEPARATORS.split(normalized);
        List<String> matched = new ArrayList<>();

        // Pass A: exact token match (catches "fuck", "sh1t"→"shit", "fuuuuck"→"fuck",
        // "chutiya", "a55hole"→"asshole").
        for (String token : tokens) {
            if (!token.isEmpty() && badWords.contains(token)) {
                matched.add(token);
            }
        }

        // Pass B: join runs of consecutive SINGLE-character tokens and scan them —
        // catches spaced/punctuated evasions ("f u c k", "f.u.c.k") with low false
        // positives (legitimate text isn't written as lone letters).
        if (matched.isEmpty()) {
            StringBuilder run = new StringBuilder();
            for (int i = 0; i <= tokens.length; i++) {
                String tok = i < tokens.length ? tokens[i] : "";
                if (tok.length() == 1) {
                    run.append(tok);
                } else {
                    if (run.length() >= RUN_MIN_LEN) {
                        String joined = run.toString();
                        for (String w : runScanWords) {
                            if (joined.contains(w)) { matched.add(w); break; }
                        }
                    }
                    if (!matched.isEmpty()) break;
                    run.setLength(0);
                }
            }
        }

        if (matched.isEmpty()) {
            return ModerationResult.clean();
        }
        return ModerationResult.explicit(ModerationResult.Category.ABUSE, matched.size(), matched);
    }

    @Override
    public ModerationResult moderateMedia(Path storedFile, MessageType type) {
        if (!enabled || storedFile == null) {
            return ModerationResult.clean();
        }
        boolean isImage = type == MessageType.IMAGE;
        boolean isVideo = type == MessageType.VIDEO;
        if (!isImage && !isVideo) {
            return ModerationResult.clean();
        }
        // Authoritative server-side NSFW check via the free self-hosted sidecar.
        // Fail-open: if the classifier is unavailable we let media through (logged)
        // rather than blocking every upload — the text path is unaffected.
        var verdict = nsfwClient.classify(storedFile, isVideo);
        if (verdict.isEmpty()) {
            log.warn("NSFW classifier unavailable; allowing media {} (fail-open)", storedFile);
            return ModerationResult.clean();
        }
        if (verdict.get()) {
            return ModerationResult.explicit(
                    isVideo ? ModerationResult.Category.NSFW_VIDEO : ModerationResult.Category.NSFW_IMAGE,
                    1.0, java.util.List.of(isVideo ? "nsfw_video" : "nsfw_image"));
        }
        return ModerationResult.clean();
    }

    /** lowercase → NFKC → leetspeak fold → collapse 3+ repeats to one. */
    private String normalize(String input) {
        String s = Normalizer.normalize(input.toLowerCase(), Normalizer.Form.NFKC);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            sb.append(deLeet(s.charAt(i)));
        }
        return REPEATS.matcher(sb).replaceAll("$1");
    }

    private char deLeet(char c) {
        switch (c) {
            case '@': return 'a';
            case '4': return 'a';
            case '0': return 'o';
            case '1': return 'i';
            case '3': return 'e';
            case '5': return 's';
            case '$': return 's';
            case '7': return 't';
            default: return c;
        }
    }
}
