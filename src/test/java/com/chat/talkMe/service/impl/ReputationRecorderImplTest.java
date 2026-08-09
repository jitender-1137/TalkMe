package com.chat.talkMe.service.impl;

import com.chat.talkMe.enums.ReputationEventType;
import com.chat.talkMe.event.ReputationSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Pure Mockito unit test for {@link ReputationRecorderImpl} — the thin publisher that turns a
 * {@code record(...)} call into a {@link ReputationSignal} application event.
 *
 * <p>Invariants under test: (1) a null {@code userId} or {@code type} short-circuits with NO
 * publish; (2) a valid call publishes exactly one signal carrying the given userId/type/sourceRef
 * and a fresh {@code occurredAt}; (3) a null {@code sourceRef} is preserved verbatim.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReputationRecorderImpl (unit)")
class ReputationRecorderImplTest {

    @Mock
    private ApplicationEventPublisher publisher;

    private ReputationRecorderImpl recorder;

    @BeforeEach
    void setUp() {
        recorder = new ReputationRecorderImpl(publisher);
    }

    @Nested
    @DisplayName("record")
    class Record {

        @Test
        @DisplayName("valid call publishes a ReputationSignal with the given fields")
        void publishesSignal() {
            Instant before = Instant.now();

            recorder.record(42L, ReputationEventType.POST_QUALITY, "post-1");

            ArgumentCaptor<ReputationSignal> captor = ArgumentCaptor.forClass(ReputationSignal.class);
            verify(publisher).publishEvent(captor.capture());
            ReputationSignal signal = captor.getValue();
            assertThat(signal.userId()).isEqualTo(42L);
            assertThat(signal.type()).isEqualTo(ReputationEventType.POST_QUALITY);
            assertThat(signal.sourceRef()).isEqualTo("post-1");
            assertThat(signal.occurredAt()).isNotNull();
            assertThat(signal.occurredAt()).isAfterOrEqualTo(before);
        }

        @Test
        @DisplayName("null sourceRef is preserved (sourceless daily/aggregate signal)")
        void publishesWithNullSourceRef() {
            recorder.record(7L, ReputationEventType.DAILY_ACTIVE, null);

            ArgumentCaptor<ReputationSignal> captor = ArgumentCaptor.forClass(ReputationSignal.class);
            verify(publisher).publishEvent(captor.capture());
            assertThat(captor.getValue().sourceRef()).isNull();
            assertThat(captor.getValue().type()).isEqualTo(ReputationEventType.DAILY_ACTIVE);
        }

        @Test
        @DisplayName("null userId → no publish (silent no-op)")
        void nullUserIdNoPublish() {
            recorder.record(null, ReputationEventType.POST_QUALITY, "post-1");

            verify(publisher, never()).publishEvent(ArgumentMatchers.any());
        }

        @Test
        @DisplayName("null type → no publish (silent no-op)")
        void nullTypeNoPublish() {
            recorder.record(42L, null, "post-1");

            verify(publisher, never()).publishEvent(ArgumentMatchers.any());
        }
    }
}
