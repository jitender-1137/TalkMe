package com.neo.chat.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import java.net.ConnectException;
import java.net.SocketException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Pure unit test for {@link BackgroundTaskErrors}. Transience is decided purely on the class-name
 * of the throwable (or any cause), so the non-{@code java.net} tokens are exercised with locally
 * declared exception classes whose names contain the exact token — no dependency on Redis/Spring
 * types being on the classpath. The {@code log} dispatch (quiet WARN vs full ERROR) is asserted
 * against a mocked SLF4J {@link Logger}, and the cause-chain walk / self-referential-loop guard
 * are covered directly.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BackgroundTaskErrors (unit)")
class BackgroundTaskErrorsTest {

    @Mock
    private Logger log;

    // --- locally-named exceptions whose getName() contains each policed token ---
    static class RedisCommandTimeoutException extends RuntimeException {
    }

    static class RedisConnectionException extends RuntimeException {
    }

    static class RedisConnectionFailureException extends RuntimeException {
    }

    static class QueryTimeoutException extends RuntimeException {
    }

    static class DataAccessResourceFailureException extends RuntimeException {
    }

    static class RedisSystemException extends RuntimeException {
    }

    /**
     * A throwable whose cause is itself — exercises the self-loop guard without infinite spin.
     */
    static class SelfCausedException extends RuntimeException {
        SelfCausedException() {
            super("loop");
        }

        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }

    @Nested
    @DisplayName("isTransientInfra")
    class IsTransientInfra {

        @Test
        @DisplayName("null throwable is not transient")
        void nullIsNotTransient() {
            assertThat(BackgroundTaskErrors.isTransientInfra(null)).isFalse();
        }

        @Test
        @DisplayName("RedisCommandTimeoutException is transient")
        void redisCommandTimeout() {
            assertThat(BackgroundTaskErrors.isTransientInfra(new RedisCommandTimeoutException())).isTrue();
        }

        @Test
        @DisplayName("RedisConnectionException is transient")
        void redisConnection() {
            assertThat(BackgroundTaskErrors.isTransientInfra(new RedisConnectionException())).isTrue();
        }

        @Test
        @DisplayName("RedisConnectionFailureException is transient")
        void redisConnectionFailure() {
            assertThat(BackgroundTaskErrors.isTransientInfra(new RedisConnectionFailureException())).isTrue();
        }

        @Test
        @DisplayName("QueryTimeoutException is transient")
        void queryTimeout() {
            assertThat(BackgroundTaskErrors.isTransientInfra(new QueryTimeoutException())).isTrue();
        }

        @Test
        @DisplayName("DataAccessResourceFailureException is transient")
        void dataAccessResourceFailure() {
            assertThat(BackgroundTaskErrors.isTransientInfra(new DataAccessResourceFailureException())).isTrue();
        }

        @Test
        @DisplayName("RedisSystemException is transient")
        void redisSystem() {
            assertThat(BackgroundTaskErrors.isTransientInfra(new RedisSystemException())).isTrue();
        }

        @Test
        @DisplayName("SocketException is transient")
        void socket() {
            assertThat(BackgroundTaskErrors.isTransientInfra(new SocketException("reset"))).isTrue();
        }

        @Test
        @DisplayName("ConnectException is transient")
        void connect() {
            assertThat(BackgroundTaskErrors.isTransientInfra(new ConnectException("refused"))).isTrue();
        }

        @Test
        @DisplayName("a transient cause wrapped in a generic exception is still transient")
        void transientNestedInCause() {
            Throwable wrapped = new RuntimeException("outer", new SocketException("inner"));
            assertThat(BackgroundTaskErrors.isTransientInfra(wrapped)).isTrue();
        }

        @Test
        @DisplayName("a deeply nested transient cause is found")
        void deeplyNestedTransient() {
            Throwable deep = new IllegalStateException("a",
                    new RuntimeException("b", new ConnectException("c")));
            assertThat(BackgroundTaskErrors.isTransientInfra(deep)).isTrue();
        }

        @Test
        @DisplayName("an ordinary application exception is not transient")
        void ordinaryNotTransient() {
            assertThat(BackgroundTaskErrors.isTransientInfra(new IllegalStateException("bug"))).isFalse();
        }

        @Test
        @DisplayName("a non-transient exception with a non-transient cause chain is not transient")
        void nonTransientChain() {
            Throwable chain = new RuntimeException("x", new IllegalArgumentException("y"));
            assertThat(BackgroundTaskErrors.isTransientInfra(chain)).isFalse();
        }

        @Test
        @DisplayName("a self-referential cause loop terminates and returns false")
        void selfReferentialLoopTerminates() {
            assertThat(BackgroundTaskErrors.isTransientInfra(new SelfCausedException())).isFalse();
        }
    }

    @Nested
    @DisplayName("log")
    class Log {

        @Test
        @DisplayName("transient infra blip logs a single WARN and no ERROR")
        void transientLogsWarn() {
            BackgroundTaskErrors.log(log, "PresenceReaper", new SocketException("reset"));

            verify(log).warn(anyString(), eq("PresenceReaper"), any());
            verify(log, never()).error(anyString(), any(), any());
        }

        @Test
        @DisplayName("genuine bug logs a full ERROR stack trace and no WARN")
        void nonTransientLogsError() {
            IllegalStateException bug = new IllegalStateException("boom");

            BackgroundTaskErrors.log(log, "MatchReaper", bug);

            verify(log).error(eq("{} failed"), eq("MatchReaper"), eq(bug));
            verify(log, never()).warn(anyString(), any(), any());
        }
    }
}
