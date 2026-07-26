package com.chat.talkMe.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test for {@link ListenerReason} — the optional context hint attached when a person asks
 * to talk to a volunteer listener (features #26/#27). The enum's only behaviour is the tolerant
 * {@link ListenerReason#fromWireOrDefault(String)} wire parser and the {@link ListenerReason#getLabel()}
 * accessor, both of which are exercised here with no Spring context.
 */
@DisplayName("ListenerReason (unit)")
class ListenerReasonTest {

    @Nested
    @DisplayName("fromWireOrDefault")
    class FromWireOrDefault {

        @Test
        void shouldMapEveryExactWireNameToItsConstant() {
            // The canonical wire form is the enum's own name(); every value must round-trip.
            for (ListenerReason reason : ListenerReason.values()) {
                assertThat(ListenerReason.fromWireOrDefault(reason.name()))
                        .as("round-trip for %s", reason.name())
                        .isEqualTo(reason);
            }
        }

        @ParameterizedTest
        @EnumSource(ListenerReason.class)
        void shouldBeCaseInsensitive(ListenerReason reason) {
            assertThat(ListenerReason.fromWireOrDefault(reason.name().toLowerCase())).isEqualTo(reason);
            assertThat(ListenerReason.fromWireOrDefault(reason.name().toUpperCase())).isEqualTo(reason);
        }

        @Test
        void shouldMapMixedCaseKnownName() {
            assertThat(ListenerReason.fromWireOrDefault("CaNt_SlEeP")).isEqualTo(ListenerReason.CANT_SLEEP);
            assertThat(ListenerReason.fromWireOrDefault("bad_day")).isEqualTo(ListenerReason.BAD_DAY);
        }

        @Test
        void shouldTrimSurroundingWhitespaceBeforeMatching() {
            assertThat(ListenerReason.fromWireOrDefault("   LONELY   ")).isEqualTo(ListenerReason.LONELY);
            assertThat(ListenerReason.fromWireOrDefault("\tanxious\n")).isEqualTo(ListenerReason.ANXIOUS);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t", "\n"})
        void shouldReturnDefaultForNullOrBlank(String wire) {
            assertThat(ListenerReason.fromWireOrDefault(wire)).isEqualTo(ListenerReason.NEED_TO_TALK);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "UNKNOWN", "garbage", "need to talk", "NEEDTOTALK", "42",
                "<script>", "BAD-DAY", "cant sleep"
        })
        void shouldReturnDefaultForUnrecognisedWire(String wire) {
            // Anything that isn't an exact (case/whitespace-insensitive) enum name falls back safely.
            assertThat(ListenerReason.fromWireOrDefault(wire)).isEqualTo(ListenerReason.NEED_TO_TALK);
        }

        @Test
        void shouldMapNeedToTalkWireToItself() {
            // The default value must still resolve from its own explicit wire name (not just the fallback).
            assertThat(ListenerReason.fromWireOrDefault("need_to_talk")).isEqualTo(ListenerReason.NEED_TO_TALK);
        }
    }

    @Nested
    @DisplayName("getLabel")
    class GetLabel {

        @ParameterizedTest
        @EnumSource(ListenerReason.class)
        void shouldReturnNonBlankLabelForEveryValue(ListenerReason reason) {
            assertThat(reason.getLabel()).isNotBlank();
        }

        @Test
        void shouldExposeExpectedHumanReadableLabels() {
            assertThat(ListenerReason.NEED_TO_TALK.getLabel()).isEqualTo("Just need to talk");
            assertThat(ListenerReason.CANT_SLEEP.getLabel()).isEqualTo("Can't sleep");
            assertThat(ListenerReason.OTHER.getLabel()).isEqualTo("Something else");
        }
    }
}
