package com.chat.talkMe.dto.request;

import com.chat.talkMe.config.JacksonConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression tests for {@link SendMessageRequest} deserialization.
 *
 * <p>{@code lenient()} is the app's real HTTP mapper configuration (via {@link JacksonConfig}'s
 * {@code JsonMapperBuilderCustomizer}); {@code strict()} is the bare Jackson-3 default that
 * reproduces the original bug — a text-only message (no {@code allowDownload}/{@code forwarded})
 * failing with "Cannot map null into type boolean".
 */
@DisplayName("SendMessageRequest JSON deserialization (absent/null primitive leniency)")
class SendMessageRequestJsonTest {

    /** Mirrors the running app: applies the same JsonMapper customizer Spring MVC uses. */
    private ObjectMapper lenient() {
        JsonMapper.Builder builder = JsonMapper.builder();
        new JacksonConfig().primitiveNullLeniencyCustomizer().customize(builder);
        return builder.build();
    }

    /** The un-customized Jackson-3 default (strict on absent/null primitives). */
    private ObjectMapper strict() {
        return JsonMapper.builder()
                .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)
                .build();
    }

    // ── Happy path ───────────────────────────────────────────────────────────

    @Test
    void allFieldsPresent_bindVerbatim() {
        SendMessageRequest req = lenient().readValue("""
                {"content":"hi","messageType":"TEXT","clientId":"c1",
                 "forwarded":true,"allowDownload":true,"selfDestructSeconds":10,
                 "fileName":"a.png","fileSize":123,"fileUrl":"u","mimeType":"image/png",
                 "duration":1.5,"mentionedUserIds":["u1","u2"]}""", SendMessageRequest.class);

        assertThat(req.getContent()).isEqualTo("hi");
        assertThat(req.getMessageType()).isEqualTo("TEXT");
        assertThat(req.isForwarded()).isTrue();
        assertThat(req.isAllowDownload()).isTrue();
        assertThat(req.getSelfDestructSeconds()).isEqualTo(10);
        assertThat(req.getFileSize()).isEqualTo(123L);
        assertThat(req.getDuration()).isEqualTo(1.5);
        assertThat(req.getMentionedUserIds()).containsExactly("u1", "u2");
    }

    // ── The regression: a text message omits the optional boolean flags ───────

    @Test
    void textMessage_omitsAllowDownloadAndForwarded_defaultsFalse_noThrow() {
        // The exact body a plain text send produces — no allowDownload / forwarded keys.
        SendMessageRequest req = lenient().readValue(
                """
                {"content":"just text","messageType":"TEXT","clientId":"abc"}""",
                SendMessageRequest.class);

        assertThat(req.getContent()).isEqualTo("just text");
        assertThat(req.isAllowDownload()).isFalse();
        assertThat(req.isForwarded()).isFalse();
    }

    @Test
    void explicitNullPrimitives_defaultFalse_noThrow() {
        SendMessageRequest req = lenient().readValue(
                """
                {"content":"x","allowDownload":null,"forwarded":null}""",
                SendMessageRequest.class);

        assertThat(req.isAllowDownload()).isFalse();
        assertThat(req.isForwarded()).isFalse();
    }

    @Test
    void emptyObject_deserializesToAllDefaults_noThrow() {
        assertThatCode(() -> {
            SendMessageRequest req = lenient().readValue("{}", SendMessageRequest.class);
            assertThat(req.isAllowDownload()).isFalse();
            assertThat(req.isForwarded()).isFalse();
            assertThat(req.getContent()).isNull();
        }).doesNotThrowAnyException();
    }

    @Test
    void absentNullableWrappers_stayNull() {
        SendMessageRequest req = lenient().readValue(
                """
                {"content":"x"}""", SendMessageRequest.class);

        // Wrapper types legitimately stay null when absent — only PRIMITIVES were affected.
        assertThat(req.getSelfDestructSeconds()).isNull();
        assertThat(req.getFileSize()).isNull();
        assertThat(req.getDuration()).isNull();
        assertThat(req.getMentionedUserIds()).isNull();
    }

    // ── Proves the fix is load-bearing: the strict default reproduces the bug ──

    @Test
    void strictDefault_reproducesTheBug_onAnAbsentPrimitive() {
        assertThatThrownBy(() -> strict().readValue(
                """
                {"content":"just text","messageType":"TEXT"}""",
                SendMessageRequest.class))
                .isInstanceOf(tools.jackson.databind.exc.MismatchedInputException.class)
                .hasMessageContaining("boolean");
    }
}
