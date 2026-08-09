package com.chat.talkMe.moderation;

import com.chat.talkMe.enums.MessageType;
import com.chat.talkMe.moderation.impl.ContentModerationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Plain JUnit5 + Mockito unit test for {@link ContentModerationServiceImpl}, with no Spring context;
 * the {@code enabled} flag and the {@code @PostConstruct} word-list loader are driven directly via
 * {@link ReflectionTestUtils}. Verifies three surfaces: text moderation (clean text, plain profanity,
 * Hinglish abuse, evasion normalisation such as spacing/repeats/punctuation/leetspeak, benign
 * substrings, the disabled and empty-word-list short-circuits, and the ABUSE category/score/matched-
 * terms payload); {@link ContentModerationServiceImpl#moderateMedia} over a mocked {@link NsfwClient}
 * (guard skips for null/disabled/non-image-or-video, fail-open when the classifier is unavailable,
 * and NSFW image vs video branches); and {@link ContentModerationServiceImpl#moderateUpload}
 * (MultipartFile guard cases plus classifier delegation with fail-open on classifier errors).
 */
class ContentModerationServiceImplTest {

    private ContentModerationServiceImpl service;

    /**
     * Builds a text-only service (null NSFW client), forces {@code enabled=true}, and manually
     * invokes the private {@code @PostConstruct load()} to populate the bad-word lists.
     */
    @BeforeEach
    void setUp() {
        service = new ContentModerationServiceImpl(null); // text-only tests don't use the NSFW client
        ReflectionTestUtils.setField(service, "enabled", true);
        // invoke @PostConstruct loader
        ReflectionTestUtils.invokeMethod(service, "load");
    }

    @Test
    void cleanTextIsNotExplicit() {
        assertFalse(service.moderateText("hey how are you doing today").isExplicit());
        assertFalse(service.moderateText("let's meet for coffee").isExplicit());
        assertFalse(service.moderateText("").isExplicit());
        assertFalse(service.moderateText(null).isExplicit());
    }

    @Test
    void plainProfanityIsExplicit() {
        assertTrue(service.moderateText("you are a fuck").isExplicit());
        assertTrue(service.moderateText("what the shit").isExplicit());
    }

    @Test
    void hinglishAbuseIsExplicit() {
        assertTrue(service.moderateText("tu ek chutiya hai").isExplicit());
        assertTrue(service.moderateText("madarchod kahin ka").isExplicit());
    }

    @Test
    void evasionsAreCaught() {
        assertTrue(service.moderateText("f u c k you").isExplicit(), "spaced");
        assertTrue(service.moderateText("fuuuuck off").isExplicit(), "repeated chars");
        assertTrue(service.moderateText("f.u.c.k").isExplicit(), "punctuated");
        assertTrue(service.moderateText("sh1t").isExplicit(), "leetspeak digits");
        assertTrue(service.moderateText("a55hole").isExplicit(), "leetspeak 5->s");
    }

    @Test
    void benignSubstringIsNotFlagged() {
        // "assassin"/"class" contain short bad substrings but must not trip the filter.
        assertFalse(service.moderateText("the assassin joined the class").isExplicit());
        assertFalse(service.moderateText("scunthorpe is a town").isExplicit());
    }

    // ── isEnabled() ──────────────────────────────────────────────────────────

    @Test
    void isEnabledReflectsTheConfiguredFlag() {
        assertTrue(service.isEnabled());
        ContentModerationServiceImpl off = new ContentModerationServiceImpl(null);
        ReflectionTestUtils.setField(off, "enabled", false);
        assertFalse(off.isEnabled());
    }

    // ── moderateText: disabled / empty-word-list / category & score ──────────

    @Test
    void disabledService_returnsClean_evenForProfanity() {
        ContentModerationServiceImpl off = new ContentModerationServiceImpl(null);
        ReflectionTestUtils.setField(off, "enabled", false);
        ReflectionTestUtils.invokeMethod(off, "load");

        assertFalse(off.moderateText("you are a fuck").isExplicit());
    }

    @Test
    void whitespaceOnlyText_returnsClean() {
        assertFalse(service.moderateText("     ").isExplicit());
    }

    @Test
    void emptyWordList_returnsClean_evenForProfanity() {
        // Build the service but never invoke load() → badWords is empty → always clean.
        ContentModerationServiceImpl noList = new ContentModerationServiceImpl(null);
        ReflectionTestUtils.setField(noList, "enabled", true);

        assertFalse(noList.moderateText("you are a fuck").isExplicit());
    }

    @Test
    void explicitResultCarriesAbuseCategory_scoreAndMatchedTerms() {
        ModerationResult r = service.moderateText("you fuck");

        assertTrue(r.isExplicit());
        assertEquals(ModerationResult.Category.ABUSE, r.getCategory());
        assertTrue(r.getScore() >= 1.0);            // score = matched count
        assertFalse(r.getMatchedTerms().isEmpty());
    }

    // ── moderateMedia() ──────────────────────────────────────────────────────

    /**
     * Builds an enabled service wired to the given (usually mocked) NSFW client for media tests.
     */
    private ContentModerationServiceImpl mediaService(NsfwClient client) {
        ContentModerationServiceImpl s = new ContentModerationServiceImpl(client);
        ReflectionTestUtils.setField(s, "enabled", true);
        return s;
    }

    @Test
    void moderateMedia_nullFile_returnsClean_withoutCallingClassifier() {
        NsfwClient client = mock(NsfwClient.class);
        ContentModerationServiceImpl s = mediaService(client);

        assertFalse(s.moderateMedia(null, MessageType.IMAGE).isExplicit());
        verifyNoInteractions(client);
    }

    @Test
    void moderateMedia_disabled_returnsClean_withoutCallingClassifier() {
        NsfwClient client = mock(NsfwClient.class);
        ContentModerationServiceImpl s = new ContentModerationServiceImpl(client);
        ReflectionTestUtils.setField(s, "enabled", false);

        assertFalse(s.moderateMedia(Path.of("/tmp/x.jpg"), MessageType.IMAGE).isExplicit());
        verifyNoInteractions(client);
    }

    @Test
    void moderateMedia_nonImageOrVideoType_returnsClean_withoutCallingClassifier() {
        NsfwClient client = mock(NsfwClient.class);
        ContentModerationServiceImpl s = mediaService(client);

        assertFalse(s.moderateMedia(Path.of("/tmp/a.mp3"), MessageType.AUDIO).isExplicit());
        verifyNoInteractions(client);
    }

    @Test
    void moderateMedia_classifierUnavailable_failsOpenClean() {
        NsfwClient client = mock(NsfwClient.class);
        when(client.classify(any(), eq(false))).thenReturn(Optional.empty());
        ContentModerationServiceImpl s = mediaService(client);

        assertFalse(s.moderateMedia(Path.of("/tmp/pic.jpg"), MessageType.IMAGE).isExplicit());
    }

    @Test
    void moderateMedia_imageFlaggedNsfw_returnsExplicitImage() {
        NsfwClient client = mock(NsfwClient.class);
        when(client.classify(any(), eq(false))).thenReturn(Optional.of(true));
        ContentModerationServiceImpl s = mediaService(client);

        ModerationResult r = s.moderateMedia(Path.of("/tmp/pic.jpg"), MessageType.IMAGE);

        assertTrue(r.isExplicit());
        assertEquals(ModerationResult.Category.NSFW_IMAGE, r.getCategory());
        assertEquals(1.0, r.getScore());
        assertEquals("nsfw_image", r.getMatchedTerms().get(0));
    }

    @Test
    void moderateMedia_videoFlaggedNsfw_returnsExplicitVideo_andClassifiesAsVideo() {
        NsfwClient client = mock(NsfwClient.class);
        when(client.classify(any(), eq(true))).thenReturn(Optional.of(true));
        ContentModerationServiceImpl s = mediaService(client);

        ModerationResult r = s.moderateMedia(Path.of("/tmp/clip.mp4"), MessageType.VIDEO);

        assertTrue(r.isExplicit());
        assertEquals(ModerationResult.Category.NSFW_VIDEO, r.getCategory());
        assertEquals("nsfw_video", r.getMatchedTerms().get(0));
        verify(client).classify(any(), eq(true));
    }

    @Test
    void moderateMedia_imageClassifiedClean_returnsClean() {
        NsfwClient client = mock(NsfwClient.class);
        when(client.classify(any(), eq(false))).thenReturn(Optional.of(false));
        ContentModerationServiceImpl s = mediaService(client);

        assertFalse(s.moderateMedia(Path.of("/tmp/pic.jpg"), MessageType.IMAGE).isExplicit());
    }

    // ── moderateUpload() ─────────────────────────────────────────────────────

    @Test
    void moderateUpload_nullFile_returnsClean() {
        NsfwClient client = mock(NsfwClient.class);
        ContentModerationServiceImpl s = mediaService(client);

        assertFalse(s.moderateUpload(null).isExplicit());
        verifyNoInteractions(client);
    }

    @Test
    void moderateUpload_emptyFile_returnsClean() {
        NsfwClient client = mock(NsfwClient.class);
        ContentModerationServiceImpl s = mediaService(client);
        MockMultipartFile empty = new MockMultipartFile("f", "e.jpg", "image/jpeg", new byte[0]);

        assertFalse(s.moderateUpload(empty).isExplicit());
        verifyNoInteractions(client);
    }

    @Test
    void moderateUpload_disabled_returnsClean() {
        NsfwClient client = mock(NsfwClient.class);
        ContentModerationServiceImpl s = new ContentModerationServiceImpl(client);
        ReflectionTestUtils.setField(s, "enabled", false);
        MockMultipartFile img = new MockMultipartFile("f", "a.jpg", "image/jpeg", new byte[]{1, 2, 3});

        assertFalse(s.moderateUpload(img).isExplicit());
        verifyNoInteractions(client);
    }

    @Test
    void moderateUpload_nullContentType_returnsClean() {
        NsfwClient client = mock(NsfwClient.class);
        ContentModerationServiceImpl s = mediaService(client);
        MockMultipartFile file = new MockMultipartFile("f", "a.bin", null, new byte[]{1, 2, 3});

        assertFalse(s.moderateUpload(file).isExplicit());
        verifyNoInteractions(client);
    }

    @Test
    void moderateUpload_nonMediaContentType_returnsClean() {
        NsfwClient client = mock(NsfwClient.class);
        ContentModerationServiceImpl s = mediaService(client);
        MockMultipartFile txt = new MockMultipartFile("f", "a.txt", "text/plain", "hello".getBytes());

        assertFalse(s.moderateUpload(txt).isExplicit());
        verifyNoInteractions(client);
    }

    @Test
    void moderateUpload_image_delegatesToClassifier_flaggedNsfw() {
        NsfwClient client = mock(NsfwClient.class);
        when(client.classify(any(), eq(false))).thenReturn(Optional.of(true));
        ContentModerationServiceImpl s = mediaService(client);
        MockMultipartFile img = new MockMultipartFile("f", "a.png", "image/png", new byte[]{1, 2, 3});

        ModerationResult r = s.moderateUpload(img);

        assertTrue(r.isExplicit());
        assertEquals(ModerationResult.Category.NSFW_IMAGE, r.getCategory());
        verify(client).classify(any(), eq(false));
    }

    @Test
    void moderateUpload_video_delegatesToClassifier_clean() {
        NsfwClient client = mock(NsfwClient.class);
        when(client.classify(any(), eq(true))).thenReturn(Optional.of(false));
        ContentModerationServiceImpl s = mediaService(client);
        MockMultipartFile vid = new MockMultipartFile("f", "a.mp4", "video/mp4", new byte[]{1, 2, 3, 4});

        assertFalse(s.moderateUpload(vid).isExplicit());
        verify(client).classify(any(), eq(true));
    }

    @Test
    void moderateUpload_classifierThrows_failsOpenClean() {
        NsfwClient client = mock(NsfwClient.class);
        when(client.classify(any(), eq(false))).thenThrow(new RuntimeException("sidecar down"));
        ContentModerationServiceImpl s = mediaService(client);
        MockMultipartFile img = new MockMultipartFile("f", "a.png", "image/png", new byte[]{9, 9, 9});

        // classify blows up inside moderateMedia → moderateUpload's catch → fail-open clean
        assertFalse(s.moderateUpload(img).isExplicit());
    }
}
