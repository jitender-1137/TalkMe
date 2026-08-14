package com.neo.chat.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit test for {@link ShortCodes}. {@code random} is checked for exact length and a strict
 * base62 alphabet across repetitions; {@code unique} is driven with deterministic predicates that
 * count invocations, covering the first-try success, the retry-then-succeed path, and the
 * exhaustion failure after the bounded 12 attempts.
 */
@DisplayName("ShortCodes (unit)")
class ShortCodesTest {

    private static final String BASE62 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private static void assertBase62(String code) {
        for (int i = 0; i < code.length(); i++) {
            assertThat(BASE62.indexOf(code.charAt(i)))
                    .as("char '%s' at %d is base62", code.charAt(i), i)
                    .isNotNegative();
        }
    }

    @Nested
    @DisplayName("random(length)")
    class Random {

        @RepeatedTest(20)
        @DisplayName("returns a base62 string of the requested length")
        void shouldReturnBase62OfLength() {
            String code = ShortCodes.random(10);
            assertThat(code).hasSize(10);
            assertBase62(code);
        }

        @Test
        @DisplayName("length 0 yields an empty string")
        void shouldReturnEmptyForZeroLength() {
            assertThat(ShortCodes.random(0)).isEmpty();
        }

        @Test
        @DisplayName("honours a non-default length")
        void shouldHonourCustomLength() {
            assertThat(ShortCodes.random(5)).hasSize(5);
        }

        @Test
        @DisplayName("two draws are (overwhelmingly likely) distinct")
        void shouldProduceDistinctCodes() {
            assertThat(ShortCodes.random(10)).isNotEqualTo(ShortCodes.random(10));
        }
    }

    @Nested
    @DisplayName("unique(isUnique)")
    class Unique {

        @Test
        @DisplayName("returns the first code when it is already unique")
        void shouldReturnOnFirstTry() {
            AtomicInteger calls = new AtomicInteger();
            Predicate<String> alwaysUnique = code -> {
                calls.incrementAndGet();
                return true;
            };

            String code = ShortCodes.unique(alwaysUnique);

            assertThat(code).hasSize(10);
            assertBase62(code);
            assertThat(calls).hasValue(1);
        }

        @Test
        @DisplayName("retries past collisions and returns the first free code")
        void shouldRetryUntilUnique() {
            AtomicInteger calls = new AtomicInteger();
            // first 3 attempts "taken", 4th free
            Predicate<String> freeOnFourth = code -> calls.incrementAndGet() >= 4;

            String code = ShortCodes.unique(freeOnFourth);

            assertThat(code).hasSize(10);
            assertThat(calls).hasValue(4);
        }

        @Test
        @DisplayName("throws IllegalStateException after 12 exhausted attempts")
        void shouldThrowAfterExhaustingAttempts() {
            AtomicInteger calls = new AtomicInteger();
            Predicate<String> neverUnique = code -> {
                calls.incrementAndGet();
                return false;
            };

            assertThatThrownBy(() -> ShortCodes.unique(neverUnique))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Unable to generate a unique short code");
            assertThat(calls).hasValue(12);
        }
    }
}
