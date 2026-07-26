package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.ChatMember;
import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.WhiteboardStrokeRequest;
import com.chat.talkMe.dto.response.WhiteboardOp;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.repository.ChatMemberRepository;
import com.chat.talkMe.repository.ChatRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link WhiteboardServiceImpl} (feature SHARED_WHITEBOARD).
 *
 * <p>All five collaborators are mocked ({@link StringRedisTemplate}, {@link ObjectMapper},
 * {@link ChatRepository}, {@link ChatMemberRepository}, {@link SimpMessagingTemplate}); the service
 * does no real I/O. Focus areas — both were just security-hardened:
 * <ul>
 *   <li><b>Membership (IDOR) guard</b> {@code requireChatMember}: it must REJECT a missing chat, a
 *       soft-deleted chat, and a member row that is left/banned/deleted — a bare
 *       {@code findByChatAndUser(...).isPresent()} would let former members through — and ACCEPT only
 *       an active member. Exercised through the public {@code getBoard}.</li>
 *   <li><b>Payload bound</b> {@code addStroke}: the count cap ({@value} 2000) AND the per-point
 *       {@code [x, y]} length check (the amplification-DoS fix that bean-validation can't express on a
 *       {@code List<double[]>}).</li>
 *   <li><b>Redis fail-open</b>: a Redis blip during {@code getBoard} yields an empty board, never a
 *       thrown exception.</li>
 * </ul>
 *
 * <p>Strictness is LENIENT: several tests arrange Redis op-handles that a short-circuiting reject path
 * never reaches, and that is intentional.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WhiteboardServiceImpl (unit)")
class WhiteboardServiceImplTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private ChatRepository chatRepository;
    @Mock
    private ChatMemberRepository chatMemberRepository;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private ListOperations<String, String> listOps;

    private WhiteboardServiceImpl service;

    private User me;
    private UUID chatId;
    private String chatUuid;

    @BeforeEach
    void setUp() {
        service = new WhiteboardServiceImpl(
                redis, objectMapper, chatRepository, chatMemberRepository, messagingTemplate);

        Role role = Role.builder().name("ROLE_USER").build();
        me = User.builder()
                .username("testuser").email("t@e.com").name("Test User")
                .isGuest(false).roles(Set.of(role))
                .build();
        me.setId(7L);
        me.setUuid(UUID.randomUUID());

        chatId = UUID.randomUUID();
        chatUuid = chatId.toString();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Chat activeChat() {
        Chat chat = new Chat();      // no-arg ctor → isDeleted=false (active)
        chat.setUuid(chatId);
        return chat;
    }

    /** Arrange the happy IDOR path: a live chat with an active member row for {@code me}. */
    private Chat arrangeActiveMember() {
        Chat chat = activeChat();
        ChatMember member = new ChatMember(); // active: leftAt=null, isBanned=false, isDeleted=false
        when(chatRepository.findByUuid(chatId)).thenReturn(Optional.of(chat));
        when(chatMemberRepository.findByChatAndUser(chat, me)).thenReturn(Optional.of(member));
        return chat;
    }

    private static WhiteboardStrokeRequest strokeRequest(String chatUuid, List<double[]> points) {
        WhiteboardStrokeRequest req = new WhiteboardStrokeRequest();
        req.setChatUuid(chatUuid);
        req.setColor("#ff0055");
        req.setSize(3.0);
        req.setTool("pen");
        req.setPoints(points);
        return req;
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  requireChatMember  (IDOR guard, driven through getBoard)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("requireChatMember (IDOR)")
    class RequireChatMember {

        @Test
        void shouldRejectWhenChatMissing() {
            when(chatRepository.findByUuid(chatId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getBoard(me, chatUuid))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_103"));

            // Chat resolution failed → the member lookup must never run.
            verify(chatMemberRepository, never()).findByChatAndUser(any(), any());
            verifyNoInteractions(messagingTemplate);
        }

        @Test
        void shouldRejectWhenChatSoftDeleted() {
            Chat chat = activeChat();
            chat.setDeleted(true); // soft-deleted 1:1 chat — the row is still queryable
            when(chatRepository.findByUuid(chatId)).thenReturn(Optional.of(chat));

            assertThatThrownBy(() -> service.getBoard(me, chatUuid))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_103"));

            verify(chatMemberRepository, never()).findByChatAndUser(any(), any());
        }

        @Test
        void shouldRejectWhenMemberHasLeft() {
            Chat chat = activeChat();
            ChatMember member = new ChatMember();
            member.setLeftAt(Instant.now()); // removed / left — read history only, not active
            when(chatRepository.findByUuid(chatId)).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, me)).thenReturn(Optional.of(member));

            assertThatThrownBy(() -> service.getBoard(me, chatUuid))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_103"));
        }

        @Test
        void shouldRejectWhenMemberBanned() {
            Chat chat = activeChat();
            ChatMember member = new ChatMember();
            member.setBanned(true);
            when(chatRepository.findByUuid(chatId)).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, me)).thenReturn(Optional.of(member));

            assertThatThrownBy(() -> service.getBoard(me, chatUuid))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_103"));
        }

        @Test
        void shouldRejectWhenMemberSoftDeleted() {
            Chat chat = activeChat();
            ChatMember member = new ChatMember();
            member.setDeleted(true);
            when(chatRepository.findByUuid(chatId)).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, me)).thenReturn(Optional.of(member));

            assertThatThrownBy(() -> service.getBoard(me, chatUuid))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_103"));
        }

        @Test
        void shouldRejectWhenMemberRowAbsentEntirely() {
            Chat chat = activeChat();
            when(chatRepository.findByUuid(chatId)).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, me)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getBoard(me, chatUuid))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_103"));
        }

        @Test
        void shouldRejectWhenChatUuidNotAValidUuid() {
            assertThatThrownBy(() -> service.getBoard(me, "not-a-uuid"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_400"));

            verifyNoInteractions(chatRepository);
            verifyNoInteractions(chatMemberRepository);
        }

        @Test
        void shouldAcceptActiveMember() {
            arrangeActiveMember();
            // Redis returns nothing — the point is that no ForbiddenException is thrown.
            when(redis.opsForList()).thenReturn(listOps);
            when(listOps.range(anyString(), eq(0L), eq(-1L))).thenReturn(null);

            List<WhiteboardOp> board = service.getBoard(me, chatUuid);

            assertThat(board).isEmpty();
            verify(chatMemberRepository).findByChatAndUser(any(Chat.class), eq(me));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  getBoard  (read path + Redis fail-open)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getBoard")
    class GetBoard {

        @Test
        void shouldReplayParsedOpsInOrder() throws Exception {
            arrangeActiveMember();
            WhiteboardOp op1 = WhiteboardOp.builder().seq(1).type("stroke").build();
            WhiteboardOp op2 = WhiteboardOp.builder().seq(2).type("undo").build();
            when(redis.opsForList()).thenReturn(listOps);
            when(listOps.range(anyString(), eq(0L), eq(-1L))).thenReturn(List.of("json1", "json2"));
            when(objectMapper.readValue("json1", WhiteboardOp.class)).thenReturn(op1);
            when(objectMapper.readValue("json2", WhiteboardOp.class)).thenReturn(op2);

            List<WhiteboardOp> board = service.getBoard(me, chatUuid);

            assertThat(board).containsExactly(op1, op2);
        }

        @Test
        void shouldSkipUnparseableOpsButKeepGoodOnes() throws Exception {
            arrangeActiveMember();
            WhiteboardOp good = WhiteboardOp.builder().seq(1).type("stroke").build();
            when(redis.opsForList()).thenReturn(listOps);
            when(listOps.range(anyString(), eq(0L), eq(-1L))).thenReturn(List.of("good", "corrupt"));
            when(objectMapper.readValue("good", WhiteboardOp.class)).thenReturn(good);
            when(objectMapper.readValue("corrupt", WhiteboardOp.class))
                    .thenThrow(new RuntimeException("bad json"));

            List<WhiteboardOp> board = service.getBoard(me, chatUuid);

            assertThat(board).containsExactly(good);
        }

        @Test
        void shouldReturnEmptyWhenRangeIsNull() {
            arrangeActiveMember();
            when(redis.opsForList()).thenReturn(listOps);
            when(listOps.range(anyString(), eq(0L), eq(-1L))).thenReturn(null);

            assertThat(service.getBoard(me, chatUuid)).isEmpty();
        }

        @Test
        void shouldFailOpenToEmptyListWhenRedisThrows() {
            // The membership guard passes, then Redis is down. The read is fail-open: no throw.
            arrangeActiveMember();
            when(redis.opsForList()).thenThrow(new RuntimeException("redis down"));

            assertThatCode(() -> {
                List<WhiteboardOp> board = service.getBoard(me, chatUuid);
                assertThat(board).isEmpty();
            }).doesNotThrowAnyException();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  addStroke  (payload bounds + broadcast)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("addStroke")
    class AddStroke {

        @Test
        void shouldRejectStrokeWithMoreThan2000Points() {
            arrangeActiveMember();
            List<double[]> points = new ArrayList<>();
            for (int i = 0; i < 2001; i++) {
                points.add(new double[]{0.1, 0.2});
            }
            WhiteboardStrokeRequest req = strokeRequest(chatUuid, points);

            assertThatThrownBy(() -> service.addStroke(me, req))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_821"))
                    .hasMessageContaining("at most 2000");

            // Rejected before any Redis write or broadcast.
            verifyNoInteractions(messagingTemplate);
            verify(redis, never()).opsForValue();
            verify(redis, never()).opsForList();
        }

        @Test
        void shouldRejectStrokeWithAPointThatIsNotLengthTwo() {
            arrangeActiveMember();
            // A single 3-element array passes the count cap but is not a valid [x, y] pair —
            // the amplification-DoS vector the length check closes.
            List<double[]> points = new ArrayList<>();
            points.add(new double[]{0.1, 0.2, 0.3});
            WhiteboardStrokeRequest req = strokeRequest(chatUuid, points);

            assertThatThrownBy(() -> service.addStroke(me, req))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_821"))
                    .hasMessageContaining("[x, y] pair");

            verifyNoInteractions(messagingTemplate);
        }

        @Test
        void shouldRejectStrokeWithANullPoint() {
            arrangeActiveMember();
            List<double[]> points = new ArrayList<>();
            points.add(new double[]{0.1, 0.2});
            points.add(null); // a null element must not slip through the [x, y] guard
            WhiteboardStrokeRequest req = strokeRequest(chatUuid, points);

            assertThatThrownBy(() -> service.addStroke(me, req))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_821"));

            verifyNoInteractions(messagingTemplate);
        }

        @Test
        void shouldRejectStrokeFromNonMemberBeforeCheckingPoints() {
            // IDOR guard runs first: a non-member never even reaches the payload validation.
            when(chatRepository.findByUuid(chatId)).thenReturn(Optional.empty());
            List<double[]> points = new ArrayList<>();
            points.add(new double[]{0.1, 0.2, 0.3}); // would be rejected too — but must not be reached
            WhiteboardStrokeRequest req = strokeRequest(chatUuid, points);

            assertThatThrownBy(() -> service.addStroke(me, req))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_103"));

            verifyNoInteractions(messagingTemplate);
        }

        @Test
        void shouldAcceptValidStrokeStampSeqAndBroadcast() throws Exception {
            arrangeActiveMember();
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.increment(anyString())).thenReturn(5L);
            when(redis.opsForList()).thenReturn(listOps);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            List<double[]> points = List.of(new double[]{0.1, 0.2}, new double[]{0.3, 0.4});
            WhiteboardStrokeRequest req = strokeRequest(chatUuid, points);

            WhiteboardOp op = service.addStroke(me, req);

            assertThat(op.getType()).isEqualTo("stroke");
            assertThat(op.getSeq()).isEqualTo(5L);
            assertThat(op.getAuthorUuid()).isEqualTo(me.getUuid().toString());
            assertThat(op.getColor()).isEqualTo("#ff0055");
            assertThat(op.getSize()).isEqualTo(3.0);
            assertThat(op.getTool()).isEqualTo("pen");
            assertThat(op.getPoints()).hasSize(2);
            assertThat(op.getTs()).isPositive();

            // Persisted to the op-log AND re-broadcast on the chat topic.
            verify(listOps).rightPush(eq("wb:ops:" + chatUuid), anyString());
            verify(messagingTemplate).convertAndSend(
                    eq("/topic/chat/" + chatUuid + "/messages"), any(Object.class));
        }

        @Test
        void shouldAcceptStrokeWithNullPointsList() throws Exception {
            // points == null is a valid (empty-path) stroke: the bound block is skipped entirely.
            arrangeActiveMember();
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.increment(anyString())).thenReturn(1L);
            when(redis.opsForList()).thenReturn(listOps);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            WhiteboardStrokeRequest req = strokeRequest(chatUuid, null);

            WhiteboardOp op = service.addStroke(me, req);

            assertThat(op.getType()).isEqualTo("stroke");
            assertThat(op.getPoints()).isNull();
            verify(messagingTemplate).convertAndSend(
                    eq("/topic/chat/" + chatUuid + "/messages"), any(Object.class));
        }
    }
}
