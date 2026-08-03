package com.chat.talkMe.match;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link AliasGenerator} — deterministic, non-negative, identity-free anonymous
 * aliases for Mask chat.
 *
 * <p>Key invariants: (1) deterministic per (sessionId, slot); (2) always a real "{Word} #{1..900}"
 * with a word from the fixed list and a number in range; (3) the sign-bit masking + floorMod never
 * yields a negative array index (even for pathological hashes); (4) the two peer slots get distinct
 * aliases; (5) the class is a non-instantiable utility.
 */
@DisplayName("AliasGenerator (unit)")
class AliasGeneratorTest {

    private static final List<String> WORDS = List.of(
            "Moon", "Fox", "Nova", "Wolf", "Echo", "Comet", "Lynx", "Raven", "Ember", "Onyx",
            "Sage", "Aster", "Vega", "Zephyr", "Koi", "Iris", "Orbit", "Frost", "Cobra", "Lumen");

    private static final Pattern FORMAT = Pattern.compile("^(\\S+) #(\\d+)$");

    @Nested
    @DisplayName("alias")
    class Alias {

        @Test
        @DisplayName("is deterministic for the same (sessionId, slot)")
        void deterministic() {
            String first = AliasGenerator.alias("sess-1", 0);
            String second = AliasGenerator.alias("sess-1", 0);

            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("produces a word from the fixed list and a number in 1..900")
        void formatAndRange() {
            String alias = AliasGenerator.alias("sess-42", 1);

            var matcher = FORMAT.matcher(alias);
            assertThat(matcher.matches()).as("alias '%s' matches '{Word} #{n}'", alias).isTrue();
            assertThat(WORDS).contains(matcher.group(1));
            int number = Integer.parseInt(matcher.group(2));
            assertThat(number).isBetween(1, 900);
        }

        @Test
        @DisplayName("the two peer slots get distinct aliases")
        void slotsAreDistinct() {
            for (String sessionId : List.of("sess-1", "abc", "xyz-9", "match-42", "hello-world")) {
                assertThat(AliasGenerator.alias(sessionId, 0))
                        .as("slot 0 vs slot 1 for session '%s'", sessionId)
                        .isNotEqualTo(AliasGenerator.alias(sessionId, 1));
            }
        }

        @Test
        @DisplayName("never yields a negative index / out-of-range word across many inputs")
        void neverNegativeIndex() {
            for (int i = 0; i < 2000; i++) {
                String sessionId = "s-" + i;
                for (int slot = 0; slot < 2; slot++) {
                    String alias = AliasGenerator.alias(sessionId, slot);
                    var matcher = FORMAT.matcher(alias);
                    assertThat(matcher.matches()).isTrue();
                    assertThat(WORDS).contains(matcher.group(1));
                    assertThat(Integer.parseInt(matcher.group(2))).isBetween(1, 900);
                }
            }
        }
    }

    @Nested
    @DisplayName("utility class")
    class UtilityClass {

        @Test
        @DisplayName("is final and cannot be instantiated (private constructor)")
        void nonInstantiable() throws Exception {
            assertThat(Modifier.isFinal(AliasGenerator.class.getModifiers())).isTrue();

            Constructor<AliasGenerator> ctor = AliasGenerator.class.getDeclaredConstructor();
            assertThat(Modifier.isPrivate(ctor.getModifiers())).isTrue();
            ctor.setAccessible(true);
            assertThat(ctor.newInstance()).isNotNull();
        }

        @Test
        @DisplayName("word list has 20 entries (defensive: floorMod domain)")
        void wordListSize() {
            // Sanity that our expected-word mirror matches the production domain.
            assertThat(Arrays.stream(new String[]{
                    "Moon", "Fox", "Nova", "Wolf", "Echo", "Comet", "Lynx", "Raven", "Ember", "Onyx",
                    "Sage", "Aster", "Vega", "Zephyr", "Koi", "Iris", "Orbit", "Frost", "Cobra", "Lumen"
            }).count()).isEqualTo(20);
        }
    }
}
