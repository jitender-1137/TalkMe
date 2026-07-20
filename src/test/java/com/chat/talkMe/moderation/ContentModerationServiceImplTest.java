package com.chat.talkMe.moderation;

import com.chat.talkMe.moderation.impl.ContentModerationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentModerationServiceImplTest {

    private ContentModerationServiceImpl service;

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
}
