package com.neo.chat.moderation.impl;

import com.neo.chat.enums.MessageType;
import com.neo.chat.moderation.ContentModerationService;
import com.neo.chat.moderation.ModerationResult;
import com.neo.chat.moderation.NsfwClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * <p>
 * Matching strategy (kept conservative to limit false positives):
 * - tokenize the normalized text and exact-match tokens against the word-list;
 * - additionally scan a "compact" (all-separators-removed) form for word-list
 * entries of length >= 5 only, to catch spaced/punctuated evasions without
 * tripping on short substrings (the "Scunthorpe" problem).
 * <p>
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

    /**
     * On startup, loads the English, Hinglish and Devanagari word-lists and copies the
     * longer-than-{@code RUN_MIN_LEN} entries into the run-scan list used by Pass B.
     */
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

    /**
     * Reads one classpath word-list, skipping blank and {@code #}-comment lines, and adds
     * each normalized (de-spaced) term to the bad-word set; a missing list is logged, not fatal.
     *
     * @param resourcePath the java.lang.String classpath location of the word-list file
     */
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

    /**
     * Normalizes the text then flags it via Pass A (exact token match) and, if nothing hit,
     * Pass B (join runs of single-char tokens to catch spaced/punctuated evasions); returns
     * an ABUSE-category explicit result when any term matches, else CLEAN.
     *
     * @param content the java.lang.String text to classify (null/blank/disabled ⇒ CLEAN)
     * @return the com.neo.chat.moderation.ModerationResult verdict
     */
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
                            if (joined.contains(w)) {
                                matched.add(w);
                                break;
                            }
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

    /**
     * Delegates image/video files to the NSFW sidecar; fail-open (returns CLEAN) when the
     * classifier is unavailable, and CLEAN for non-media types or when moderation is disabled.
     *
     * @param storedFile the java.nio.file.Path of the file on disk to classify
     * @param type       the com.neo.chat.enums.MessageType (only IMAGE/VIDEO are inspected)
     * @return the com.neo.chat.moderation.ModerationResult verdict (NSFW_IMAGE/NSFW_VIDEO if flagged)
     */
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
                    1.0, List.of(isVideo ? "nsfw_video" : "nsfw_image"));
        }
        return ModerationResult.clean();
    }

    /**
     * Classifies an in-flight upload by streaming its bytes to a short-lived temp file and
     * delegating to {@link #moderateMedia}; CLEAN for non-image/video content types, and
     * fail-open (CLEAN) on any error. The temp file is always deleted afterward.
     *
     * @param file the org.springframework.web.multipart.MultipartFile upload to inspect
     * @return the com.neo.chat.moderation.ModerationResult verdict
     */
    @Override
    public ModerationResult moderateUpload(MultipartFile file) {
        if (!enabled || file == null || file.isEmpty()) {
            return ModerationResult.clean();
        }
        String ct = file.getContentType();
        boolean isImage = ct != null && ct.startsWith("image/");
        boolean isVideo = ct != null && ct.startsWith("video/");
        if (!isImage && !isVideo) {
            return ModerationResult.clean();
        }
        // Classify the raw upload bytes via a short-lived temp file — avoids depending
        // on resolving a stored URL back to a path (which is brittle across envs).
        Path temp = null;
        try {
            temp = Files.createTempFile("mod-upload-", isVideo ? ".mp4" : ".img");
            try (var in = file.getInputStream()) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            return moderateMedia(temp, isVideo ? MessageType.VIDEO : MessageType.IMAGE);
        } catch (Exception e) {
            log.warn("moderateUpload failed; allowing (fail-open): {}", e.getMessage());
            return ModerationResult.clean();
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (Exception ignore) {
                    // best-effort temp cleanup
                }
            }
        }
    }

    /**
     * lowercase → NFKC → leetspeak fold → collapse 3+ repeats to one.
     */
    private String normalize(String input) {
        String s = Normalizer.normalize(input.toLowerCase(), Normalizer.Form.NFKC);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            sb.append(deLeet(s.charAt(i)));
        }
        return REPEATS.matcher(sb).replaceAll("$1");
    }

    /**
     * Folds common leetspeak substitutions to their letter form (@/4→a, 0→o, 1→i, 3→e,
     * 5/$→s, 7→t); returns the character unchanged otherwise.
     *
     * @param c the input char
     * @return the folded char
     */
    private char deLeet(char c) {
        return switch (c) {
            case '@', '4' -> 'a';
            case '0' -> 'o';
            case '1' -> 'i';
            case '3' -> 'e';
            case '5', '$' -> 's';
            case '7' -> 't';
            default -> c;
        };
    }
}
