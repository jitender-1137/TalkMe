package com.chat.talkMe;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Application smoke test: boots the full Spring {@link org.springframework.context.ApplicationContext}
 * under the {@code test} profile to prove that every bean wires and the context starts. Follows the
 * standard Spring Boot {@code @SpringBootTest} context-load style with a single no-body assertion —
 * the test passes iff context startup does not throw.
 */
@SpringBootTest
@ActiveProfiles("test")
class TalkMeApplicationTests {

    /** Passes when the application context loads successfully; fails if any bean fails to wire. */
    @Test
    void contextLoads() {
    }

}
