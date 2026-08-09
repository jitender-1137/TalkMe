package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.ChatMember;
import com.chat.talkMe.domain.GameSession;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.GameSessionResponse;
import com.chat.talkMe.enums.GameState;
import com.chat.talkMe.enums.GameType;
import com.chat.talkMe.enums.ReputationEventType;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.repository.ChatMemberRepository;
import com.chat.talkMe.repository.ChatRepository;
import com.chat.talkMe.repository.GameSessionRepository;
import com.chat.talkMe.service.GamePromptBank;
import com.chat.talkMe.service.ReputationRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link GameServiceImpl} — the server-authoritative conversation-game
 * engine (feature #13). Exercises the IDOR chat-member guard, the one-live-game-per-chat retirement,
 * round advancement / bank-exhaustion, and the reputation side effect.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GameServiceImpl (unit)")
class GameServiceImplTest {

    private static final String CHAT_ID = "11111111-1111-1111-1111-111111111111";
    private static final UUID CHAT_UUID = UUID.fromString(CHAT_ID);
    private static final String SESSION_ID = "22222222-2222-2222-2222-222222222222";
    private static final UUID SESSION_UUID = UUID.fromString(SESSION_ID);

    @Mock private GameSessionRepository gameSessionRepository;
    @Mock private ReputationRecorder reputationRecorder;
    @Mock private ChatRepository chatRepository;
    @Mock private ChatMemberRepository chatMemberRepository;

    private GameServiceImpl service;
    private User user;
    private Chat chat;

    @BeforeEach
    void setUp() {
        service = new GameServiceImpl(gameSessionRepository, reputationRecorder,
                chatRepository, chatMemberRepository);
        user = User.builder().username("alice").name("Alice").build();
        user.setId(7L);
        user.setUuid(UUID.randomUUID());
        chat = Chat.builder().name("Room").build();
        chat.setId(3L);
        chat.setUuid(CHAT_UUID);
    }

    /** Stub the chat-member guard to pass for CHAT_UUID. */
    private void stubMember() {
        when(chatRepository.findByUuid(CHAT_UUID)).thenReturn(Optional.of(chat));
        when(chatMemberRepository.findByChatAndUser(chat, user)).thenReturn(Optional.of(new ChatMember()));
    }

    /** save() echoes the argument back, minting a uuid if the entity is new (mirrors JPA persist). */
    private void stubSaveEcho() {
        when(gameSessionRepository.save(any(GameSession.class))).thenAnswer(inv -> {
            GameSession s = inv.getArgument(0);
            if (s.getUuid() == null) s.setUuid(UUID.randomUUID());
            return s;
        });
    }

    private GameSession session(GameState state, int round) {
        GameSession s = GameSession.builder()
                .chatId(CHAT_ID)
                .gameType(GameType.TWO_TRUTHS)
                .state(state)
                .currentRound(round)
                .currentPromptId(GameType.TWO_TRUTHS.name() + "#" + round)
                .build();
        s.setUuid(SESSION_UUID);
        return s;
    }

    @Nested
    @DisplayName("start")
    class Start {

        @Test
        @DisplayName("null chatId → BadRequest TM_400")
        void nullChatId() {
            assertThatThrownBy(() -> service.start(user, null, GameType.TWO_TRUTHS))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_400"));
            verify(gameSessionRepository, never()).save(any());
        }

        @Test
        @DisplayName("blank chatId → BadRequest TM_400")
        void blankChatId() {
            assertThatThrownBy(() -> service.start(user, "   ", GameType.TWO_TRUTHS))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_400"));
        }

        @Test
        @DisplayName("null gameType → BadRequest TM_400")
        void nullGameType() {
            assertThatThrownBy(() -> service.start(user, CHAT_ID, null))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_400"));
        }

        @Test
        @DisplayName("malformed chat uuid → BadRequest TM_400")
        void malformedChatUuid() {
            assertThatThrownBy(() -> service.start(user, "not-a-uuid", GameType.TWO_TRUTHS))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_400"));
        }

        @Test
        @DisplayName("caller is not a chat member → Forbidden TM_103")
        void notMember() {
            when(chatRepository.findByUuid(CHAT_UUID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.start(user, CHAT_ID, GameType.TWO_TRUTHS))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_103"));
            verify(gameSessionRepository, never()).save(any());
        }

        @Test
        @DisplayName("no existing session → creates IN_PROGRESS at round 0 and records reputation")
        void createsFresh() {
            stubMember();
            when(gameSessionRepository.findFirstByChatIdAndStateNotOrderByIdDesc(CHAT_ID, GameState.ENDED))
                    .thenReturn(Optional.empty());
            stubSaveEcho();

            GameSessionResponse resp = service.start(user, CHAT_ID, GameType.TWO_TRUTHS);

            ArgumentCaptor<GameSession> saved = ArgumentCaptor.forClass(GameSession.class);
            verify(gameSessionRepository).save(saved.capture());
            GameSession s = saved.getValue();
            assertThat(s.getState()).isEqualTo(GameState.IN_PROGRESS);
            assertThat(s.getCurrentRound()).isZero();
            assertThat(s.getChatId()).isEqualTo(CHAT_ID);
            assertThat(s.getCurrentPromptId()).isEqualTo("TWO_TRUTHS#0");

            assertThat(resp.getState()).isEqualTo("IN_PROGRESS");
            assertThat(resp.getGameType()).isEqualTo("TWO_TRUTHS");
            assertThat(resp.getRound()).isZero();
            assertThat(resp.getPrompt()).isEqualTo(GamePromptBank.promptAt(GameType.TWO_TRUTHS, 0));

            verify(reputationRecorder).record(7L, ReputationEventType.CONVERSATION_STARTED, "game:" + CHAT_ID);
        }

        @Test
        @DisplayName("existing live session is retired (ENDED) before the new one is created")
        void retiresExisting() {
            stubMember();
            GameSession existing = session(GameState.IN_PROGRESS, 2);
            when(gameSessionRepository.findFirstByChatIdAndStateNotOrderByIdDesc(CHAT_ID, GameState.ENDED))
                    .thenReturn(Optional.of(existing));
            stubSaveEcho();

            service.start(user, CHAT_ID, GameType.WOULD_YOU_RATHER);

            assertThat(existing.getState()).isEqualTo(GameState.ENDED);

            ArgumentCaptor<GameSession> saved = ArgumentCaptor.forClass(GameSession.class);
            verify(gameSessionRepository, Mockito.times(2)).save(saved.capture());
            // First save retires the old session; second persists the fresh IN_PROGRESS one.
            assertThat(saved.getAllValues().get(0)).isSameAs(existing);
            assertThat(saved.getAllValues().get(1).getState()).isEqualTo(GameState.IN_PROGRESS);
            assertThat(saved.getAllValues().get(1).getGameType()).isEqualTo(GameType.WOULD_YOU_RATHER);
        }
    }

    @Nested
    @DisplayName("next")
    class Next {

        @Test
        @DisplayName("malformed session uuid → BadRequest TM_400")
        void malformedUuid() {
            assertThatThrownBy(() -> service.next(user, "nope"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_400"));
        }

        @Test
        @DisplayName("session not found → NotFound TM_101")
        void notFound() {
            when(gameSessionRepository.findByUuid(SESSION_UUID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.next(user, SESSION_ID))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_101"));
        }

        @Test
        @DisplayName("caller not a member of the game's chat → Forbidden TM_103")
        void notMember() {
            when(gameSessionRepository.findByUuid(SESSION_UUID)).thenReturn(Optional.of(session(GameState.IN_PROGRESS, 0)));
            when(chatRepository.findByUuid(CHAT_UUID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.next(user, SESSION_ID))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_103"));
        }

        @Test
        @DisplayName("session not IN_PROGRESS → BadRequest TM_400")
        void notInProgress() {
            when(gameSessionRepository.findByUuid(SESSION_UUID)).thenReturn(Optional.of(session(GameState.ENDED, 0)));
            stubMember();

            assertThatThrownBy(() -> service.next(user, SESSION_ID))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_400"));
            verify(gameSessionRepository, never()).save(any());
        }

        @Test
        @DisplayName("advances the cursor when the bank still has prompts")
        void advances() {
            GameSession s = session(GameState.IN_PROGRESS, 2);
            when(gameSessionRepository.findByUuid(SESSION_UUID)).thenReturn(Optional.of(s));
            stubMember();
            when(gameSessionRepository.save(any(GameSession.class))).thenAnswer(inv -> inv.getArgument(0));

            GameSessionResponse resp = service.next(user, SESSION_ID);

            assertThat(s.getState()).isEqualTo(GameState.IN_PROGRESS);
            assertThat(s.getCurrentRound()).isEqualTo(3);
            assertThat(s.getCurrentPromptId()).isEqualTo("TWO_TRUTHS#3");
            assertThat(resp.getRound()).isEqualTo(3);
            assertThat(resp.getState()).isEqualTo("IN_PROGRESS");
            assertThat(resp.getPrompt()).isEqualTo(GamePromptBank.promptAt(GameType.TWO_TRUTHS, 3));
        }

        @Test
        @DisplayName("ends the game when the bank is exhausted (no prompt in response)")
        void exhaustsBank() {
            int last = GamePromptBank.size(GameType.TWO_TRUTHS) - 1; // 7
            GameSession s = session(GameState.IN_PROGRESS, last);
            when(gameSessionRepository.findByUuid(SESSION_UUID)).thenReturn(Optional.of(s));
            stubMember();
            when(gameSessionRepository.save(any(GameSession.class))).thenAnswer(inv -> inv.getArgument(0));

            GameSessionResponse resp = service.next(user, SESSION_ID);

            assertThat(s.getState()).isEqualTo(GameState.ENDED);
            assertThat(s.getCurrentRound()).isEqualTo(last); // round not advanced past the bank
            assertThat(resp.getState()).isEqualTo("ENDED");
            assertThat(resp.getPrompt()).isNull();
        }
    }

    @Nested
    @DisplayName("end")
    class End {

        @Test
        @DisplayName("malformed session uuid → BadRequest TM_400")
        void malformedUuid() {
            assertThatThrownBy(() -> service.end(user, "bad"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_400"));
        }

        @Test
        @DisplayName("session not found → NotFound TM_101")
        void notFound() {
            when(gameSessionRepository.findByUuid(SESSION_UUID)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.end(user, SESSION_ID))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_101"));
        }

        @Test
        @DisplayName("caller not a member → Forbidden TM_103")
        void notMember() {
            when(gameSessionRepository.findByUuid(SESSION_UUID)).thenReturn(Optional.of(session(GameState.IN_PROGRESS, 1)));
            when(chatRepository.findByUuid(CHAT_UUID)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.end(user, SESSION_ID))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_103"));
        }

        @Test
        @DisplayName("marks the session ENDED and returns it with no prompt")
        void endsSession() {
            GameSession s = session(GameState.IN_PROGRESS, 3);
            when(gameSessionRepository.findByUuid(SESSION_UUID)).thenReturn(Optional.of(s));
            stubMember();
            when(gameSessionRepository.save(any(GameSession.class))).thenAnswer(inv -> inv.getArgument(0));

            GameSessionResponse resp = service.end(user, SESSION_ID);

            assertThat(s.getState()).isEqualTo(GameState.ENDED);
            assertThat(resp.getState()).isEqualTo("ENDED");
            assertThat(resp.getPrompt()).isNull();
        }

        @Test
        @DisplayName("ending an already-ENDED session is idempotent")
        void idempotentOnEnded() {
            GameSession s = session(GameState.ENDED, 5);
            when(gameSessionRepository.findByUuid(SESSION_UUID)).thenReturn(Optional.of(s));
            stubMember();
            when(gameSessionRepository.save(any(GameSession.class))).thenAnswer(inv -> inv.getArgument(0));

            GameSessionResponse resp = service.end(user, SESSION_ID);

            assertThat(s.getState()).isEqualTo(GameState.ENDED);
            assertThat(resp.getState()).isEqualTo("ENDED");
        }
    }

    @Nested
    @DisplayName("active")
    class Active {

        @Test
        @DisplayName("null chatId → BadRequest TM_400")
        void nullChatId() {
            assertThatThrownBy(() -> service.active(user, null))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_400"));
        }

        @Test
        @DisplayName("blank chatId → BadRequest TM_400")
        void blankChatId() {
            assertThatThrownBy(() -> service.active(user, "  "))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_400"));
        }

        @Test
        @DisplayName("caller not a member → Forbidden TM_103")
        void notMember() {
            when(chatRepository.findByUuid(CHAT_UUID)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.active(user, CHAT_ID))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_103"));
        }

        @Test
        @DisplayName("returns the live session when one exists")
        void returnsLive() {
            stubMember();
            when(gameSessionRepository.findFirstByChatIdAndStateNotOrderByIdDesc(CHAT_ID, GameState.ENDED))
                    .thenReturn(Optional.of(session(GameState.IN_PROGRESS, 1)));

            GameSessionResponse resp = service.active(user, CHAT_ID);

            assertThat(resp).isNotNull();
            assertThat(resp.getState()).isEqualTo("IN_PROGRESS");
            assertThat(resp.getRound()).isEqualTo(1);
        }

        @Test
        @DisplayName("returns null when there is no live session")
        void returnsNullWhenNone() {
            stubMember();
            when(gameSessionRepository.findFirstByChatIdAndStateNotOrderByIdDesc(CHAT_ID, GameState.ENDED))
                    .thenReturn(Optional.empty());

            assertThat(service.active(user, CHAT_ID)).isNull();
        }
    }
}
