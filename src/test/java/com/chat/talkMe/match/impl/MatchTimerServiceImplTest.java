package com.chat.talkMe.match.impl;

import com.chat.talkMe.enums.MatchMode;
import com.chat.talkMe.match.MatchServerEvent;
import com.chat.talkMe.match.MatchSession;
import com.chat.talkMe.match.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link MatchTimerServiceImpl} — Coffee/Chemistry timers backed
 * by Redis ZSET deadlines + prompt-rotation ZSET, driving anonymous STOMP events to both peers.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MatchTimerServiceImpl (unit)")
class MatchTimerServiceImplTest {

    private static final String A = "alice";
    private static final String B = "bob";
    private static final String SID = "sess-1";
    private static final String TIMER_ZSET = "match:timer-deadlines";
    private static final String PROMPT_ZSET = "match:chem-prompts";
    private static final String IDX_PREFIX = "match:chem-idx:";
    private static final long INTERVAL = 45000L;

    @Mock
    private SessionService sessionService;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ZSetOperations<String, String> zSetOps;
    @Mock
    private ValueOperations<String, String> valueOps;

    private MatchTimerServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForZSet()).thenReturn(zSetOps);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        service = new MatchTimerServiceImpl(sessionService, messagingTemplate, redis);
        ReflectionTestUtils.setField(service, "promptIntervalMs", INTERVAL);
    }

    private MatchSession session(MatchMode mode) {
        return MatchSession.builder().id(SID).userA(A).userB(B).mode(mode).build();
    }

    private String eventTo(String user) {
        ArgumentCaptor<MatchServerEvent> ev = ArgumentCaptor.forClass(MatchServerEvent.class);
        verify(messagingTemplate).convertAndSendToUser(eq(user), eq("/queue/match"), ev.capture());
        return ev.getValue().getEvent();
    }

    @Nested
    @DisplayName("arm")
    class Arm {

        @Test
        @DisplayName("unknown session → no-op")
        void unknownSession() {
            when(sessionService.getSession(SID)).thenReturn(Optional.empty());

            service.arm(SID, 60);

            verify(zSetOps, never()).add(anyString(), anyString(), anyDouble());
            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("COFFEE mode → arms timer, COFFEE_STARTED to both, no prompt scheduling")
        void coffeeArms() {
            MatchSession s = session(MatchMode.COFFEE);
            when(sessionService.getSession(SID)).thenReturn(Optional.of(s));

            service.arm(SID, 60);

            verify(zSetOps).add(eq(TIMER_ZSET), eq(SID), anyDouble());
            assertThat(s.getTimerDeadlineEpochMs()).isNotNull();
            assertThat(s.isPostTimer()).isFalse();
            assertThat(eventTo(A)).isEqualTo("COFFEE_STARTED");
            verify(messagingTemplate).convertAndSendToUser(eq(B), eq("/queue/match"), any());
            verify(zSetOps, never()).add(eq(PROMPT_ZSET), anyString(), anyDouble());
            verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("CHEMISTRY mode → arms timer, CHEMISTRY_STARTED, first prompt, and schedules rotation")
        void chemistryArmsAndSchedulesPrompts() {
            MatchSession s = session(MatchMode.CHEMISTRY);
            when(sessionService.getSession(SID)).thenReturn(Optional.of(s));

            service.arm(SID, 120);

            verify(zSetOps).add(eq(TIMER_ZSET), eq(SID), anyDouble());
            // Both a start event and the first prompt go to each peer.
            ArgumentCaptor<MatchServerEvent> toA = ArgumentCaptor.forClass(MatchServerEvent.class);
            verify(messagingTemplate, Mockito.times(2))
                    .convertAndSendToUser(eq(A), eq("/queue/match"), toA.capture());
            assertThat(toA.getAllValues()).extracting(MatchServerEvent::getEvent)
                    .containsExactly("CHEMISTRY_STARTED", "CHEMISTRY_PROMPT");
            verify(valueOps).set(eq(IDX_PREFIX + SID), eq("0"), eq(Duration.ofHours(1)));
            verify(zSetOps).add(eq(PROMPT_ZSET), eq(SID), anyDouble());
        }

        @Test
        @DisplayName("non-positive seconds → clamped to a positive deadline (still arms)")
        void nonPositiveSecondsClamped() {
            MatchSession s = session(MatchMode.QUICK);
            when(sessionService.getSession(SID)).thenReturn(Optional.of(s));
            long before = System.currentTimeMillis();

            service.arm(SID, 0);

            verify(zSetOps).add(eq(TIMER_ZSET), eq(SID), anyDouble());
            assertThat(s.getTimerDeadlineEpochMs()).isNotNull().isGreaterThanOrEqualTo(before);
            // QUICK is not chemistry → COFFEE_STARTED.
            assertThat(eventTo(A)).isEqualTo("COFFEE_STARTED");
        }
    }

    @Nested
    @DisplayName("cancel")
    class Cancel {

        @Test
        @DisplayName("removes both ZSET entries and the prompt index key")
        void clearsAllState() {
            service.cancel(SID);

            verify(zSetOps).remove(TIMER_ZSET, SID);
            verify(zSetOps).remove(PROMPT_ZSET, SID);
            verify(redis).delete(IDX_PREFIX + SID);
        }
    }

    @Nested
    @DisplayName("continueRequest")
    class ContinueRequest {

        @Test
        @DisplayName("unknown session → no-op")
        void unknownSession() {
            when(sessionService.getSessionByUser(A)).thenReturn(Optional.empty());

            service.continueRequest(A);

            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("only one side continued → records action, does not fire")
        void oneSideOnly() {
            MatchSession s = session(MatchMode.COFFEE);
            s.setTimerDeadlineEpochMs(System.currentTimeMillis() + 10_000);
            when(sessionService.getSessionByUser(A)).thenReturn(Optional.of(s));

            service.continueRequest(A);

            assertThat(s.getTimedActionByUser()).containsEntry(A, "CONTINUE");
            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
            verify(zSetOps, never()).remove(eq(TIMER_ZSET), anyString());
        }

        @Test
        @DisplayName("both continued while timed → cancels timer, clears deadline, TIMER_CONTINUED to both")
        void bothContinueFires() {
            MatchSession s = session(MatchMode.COFFEE);
            s.setTimerDeadlineEpochMs(System.currentTimeMillis() + 10_000);
            s.getTimedActionByUser().put(A, "CONTINUE");
            when(sessionService.getSessionByUser(B)).thenReturn(Optional.of(s));

            service.continueRequest(B);

            verify(zSetOps).remove(TIMER_ZSET, SID);
            verify(zSetOps).remove(PROMPT_ZSET, SID);
            verify(redis).delete(IDX_PREFIX + SID);
            assertThat(s.getTimerDeadlineEpochMs()).isNull();
            assertThat(s.isPostTimer()).isFalse();
            assertThat(eventTo(A)).isEqualTo("TIMER_CONTINUED");
            verify(messagingTemplate).convertAndSendToUser(eq(B), eq("/queue/match"), any());
        }

        @Test
        @DisplayName("both continued but timer already cleared (deadline null) → does not re-fire")
        void bothContinueButTimerAlreadyCleared() {
            MatchSession s = session(MatchMode.COFFEE);
            s.setTimerDeadlineEpochMs(null);
            s.getTimedActionByUser().put(A, "CONTINUE");
            when(sessionService.getSessionByUser(B)).thenReturn(Optional.of(s));

            service.continueRequest(B);

            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
            verify(zSetOps, never()).remove(eq(TIMER_ZSET), anyString());
        }
    }

    @Nested
    @DisplayName("reapDue")
    class ReapDue {

        @Test
        @DisplayName("nothing due → no events")
        void nothingDue() {
            when(zSetOps.rangeByScore(eq(TIMER_ZSET), anyDouble(), anyDouble())).thenReturn(null);
            when(zSetOps.rangeByScore(eq(PROMPT_ZSET), anyDouble(), anyDouble())).thenReturn(null);

            service.reapDue();

            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("due timer, active session → MATCH_TIME_UP to both, session flagged postTimer")
        void dueTimerFiresTimeUp() {
            MatchSession s = session(MatchMode.COFFEE);
            when(zSetOps.rangeByScore(eq(TIMER_ZSET), anyDouble(), anyDouble())).thenReturn(Set.of(SID));
            when(zSetOps.rangeByScore(eq(PROMPT_ZSET), anyDouble(), anyDouble())).thenReturn(null);
            when(sessionService.getSession(SID)).thenReturn(Optional.of(s));

            service.reapDue();

            verify(zSetOps).remove(TIMER_ZSET, SID);
            verify(zSetOps).remove(PROMPT_ZSET, SID);
            assertThat(s.isPostTimer()).isTrue();
            ArgumentCaptor<MatchServerEvent> ev = ArgumentCaptor.forClass(MatchServerEvent.class);
            verify(messagingTemplate).convertAndSendToUser(eq(A), eq("/queue/match"), ev.capture());
            assertThat(ev.getValue().getEvent()).isEqualTo("MATCH_TIME_UP");
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) ev.getValue().getPayload();
            assertThat(payload).containsEntry("sessionId", SID).containsEntry("mode", "COFFEE");
        }

        @Test
        @DisplayName("due timer but session already postTimer → no duplicate MATCH_TIME_UP")
        void dueTimerAlreadyPostTimer() {
            MatchSession s = session(MatchMode.COFFEE);
            s.setPostTimer(true);
            when(zSetOps.rangeByScore(eq(TIMER_ZSET), anyDouble(), anyDouble())).thenReturn(Set.of(SID));
            when(zSetOps.rangeByScore(eq(PROMPT_ZSET), anyDouble(), anyDouble())).thenReturn(null);
            when(sessionService.getSession(SID)).thenReturn(Optional.of(s));

            service.reapDue();

            verify(zSetOps).remove(TIMER_ZSET, SID);
            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("due timer, session gone → removed from ZSETs, no events")
        void dueTimerSessionGone() {
            when(zSetOps.rangeByScore(eq(TIMER_ZSET), anyDouble(), anyDouble())).thenReturn(Set.of(SID));
            when(zSetOps.rangeByScore(eq(PROMPT_ZSET), anyDouble(), anyDouble())).thenReturn(null);
            when(sessionService.getSession(SID)).thenReturn(Optional.empty());

            service.reapDue();

            verify(zSetOps).remove(TIMER_ZSET, SID);
            verify(zSetOps).remove(PROMPT_ZSET, SID);
            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("due prompt, session gone → prompt entry removed, no increment")
        void duePromptSessionGone() {
            when(zSetOps.rangeByScore(eq(TIMER_ZSET), anyDouble(), anyDouble())).thenReturn(null);
            when(zSetOps.rangeByScore(eq(PROMPT_ZSET), anyDouble(), anyDouble())).thenReturn(Set.of(SID));
            when(sessionService.getSession(SID)).thenReturn(Optional.empty());

            service.reapDue();

            verify(zSetOps).remove(PROMPT_ZSET, SID);
            verify(valueOps, never()).increment(anyString());
        }

        @Test
        @DisplayName("due prompt but session postTimer → prompt entry removed, no increment")
        void duePromptPostTimer() {
            MatchSession s = session(MatchMode.CHEMISTRY);
            s.setPostTimer(true);
            when(zSetOps.rangeByScore(eq(TIMER_ZSET), anyDouble(), anyDouble())).thenReturn(null);
            when(zSetOps.rangeByScore(eq(PROMPT_ZSET), anyDouble(), anyDouble())).thenReturn(Set.of(SID));
            when(sessionService.getSession(SID)).thenReturn(Optional.of(s));

            service.reapDue();

            verify(zSetOps).remove(PROMPT_ZSET, SID);
            verify(valueOps, never()).increment(anyString());
        }

        @Test
        @DisplayName("due prompt, active, room before deadline → advances prompt and reschedules")
        void duePromptAdvancesAndReschedules() {
            MatchSession s = session(MatchMode.CHEMISTRY);
            when(zSetOps.rangeByScore(eq(TIMER_ZSET), anyDouble(), anyDouble())).thenReturn(null);
            when(zSetOps.rangeByScore(eq(PROMPT_ZSET), anyDouble(), anyDouble())).thenReturn(Set.of(SID));
            when(sessionService.getSession(SID)).thenReturn(Optional.of(s));
            when(valueOps.increment(IDX_PREFIX + SID)).thenReturn(1L);
            // Deadline far in the future so next interval still fits → reschedule.
            when(zSetOps.score(TIMER_ZSET, SID))
                    .thenReturn((double) (System.currentTimeMillis() + 10_000_000L));

            service.reapDue();

            ArgumentCaptor<MatchServerEvent> ev = ArgumentCaptor.forClass(MatchServerEvent.class);
            verify(messagingTemplate).convertAndSendToUser(eq(A), eq("/queue/match"), ev.capture());
            assertThat(ev.getValue().getEvent()).isEqualTo("CHEMISTRY_PROMPT");
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) ev.getValue().getPayload();
            assertThat(payload).containsEntry("index", 1);
            verify(zSetOps).add(eq(PROMPT_ZSET), eq(SID), anyDouble());
        }

        @Test
        @DisplayName("due prompt, next interval past deadline → advances but does NOT reschedule")
        void duePromptNoRescheduleNearDeadline() {
            MatchSession s = session(MatchMode.CHEMISTRY);
            when(zSetOps.rangeByScore(eq(TIMER_ZSET), anyDouble(), anyDouble())).thenReturn(null);
            when(zSetOps.rangeByScore(eq(PROMPT_ZSET), anyDouble(), anyDouble())).thenReturn(Set.of(SID));
            when(sessionService.getSession(SID)).thenReturn(Optional.of(s));
            when(valueOps.increment(IDX_PREFIX + SID)).thenReturn(2L);
            // Deadline already in the past → next >= deadline → remove, no reschedule.
            when(zSetOps.score(TIMER_ZSET, SID)).thenReturn(1.0);

            service.reapDue();

            verify(messagingTemplate).convertAndSendToUser(eq(A), eq("/queue/match"), any());
            verify(zSetOps).remove(PROMPT_ZSET, SID);
            verify(zSetOps, never()).add(eq(PROMPT_ZSET), anyString(), anyDouble());
        }

        @Test
        @DisplayName("due prompt, increment returns null → prompt index defaults to 0")
        void duePromptIncrementNull() {
            MatchSession s = session(MatchMode.CHEMISTRY);
            when(zSetOps.rangeByScore(eq(TIMER_ZSET), anyDouble(), anyDouble())).thenReturn(null);
            when(zSetOps.rangeByScore(eq(PROMPT_ZSET), anyDouble(), anyDouble())).thenReturn(Set.of(SID));
            when(sessionService.getSession(SID)).thenReturn(Optional.of(s));
            when(valueOps.increment(IDX_PREFIX + SID)).thenReturn(null);
            when(zSetOps.score(TIMER_ZSET, SID)).thenReturn(1.0);

            service.reapDue();

            ArgumentCaptor<MatchServerEvent> ev = ArgumentCaptor.forClass(MatchServerEvent.class);
            verify(messagingTemplate).convertAndSendToUser(eq(A), eq("/queue/match"), ev.capture());
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) ev.getValue().getPayload();
            assertThat(payload).containsEntry("index", 0);
        }
    }
}
