package com.neo.chat.service.impl;

import com.neo.chat.cache.BlockCache;
import com.neo.chat.domain.BlockUser;
import com.neo.chat.domain.Friend;
import com.neo.chat.domain.FriendRequest;
import com.neo.chat.domain.User;
import com.neo.chat.dto.response.AuthUserResponse;
import com.neo.chat.dto.response.FriendRequestResponse;
import com.neo.chat.enums.FriendRequestStatus;
import com.neo.chat.enums.PresenceStatus;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.ConflictException;
import com.neo.chat.exception.ForbiddenException;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.exception.TooManyRequestsException;
import com.neo.chat.mapper.FriendRequestMapper;
import com.neo.chat.mapper.UserMapper;
import com.neo.chat.repository.BlockUserRepository;
import com.neo.chat.repository.FriendRepository;
import com.neo.chat.repository.FriendRequestRepository;
import com.neo.chat.repository.UserRepository;
import com.neo.chat.repository.UserSettingRepository;
import com.neo.chat.service.PresenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link FriendServiceImpl} — friend requests (send/accept/reject/
 * cancel), friend listing, unfriend, and block/unblock. Exercises every ownership/state/self/
 * block/quota guard with its exact TM_### code, the re-send + auto-accept + duplicate-race
 * branches, and the STOMP friend-event broadcast + block-cache eviction + Redis daily quota
 * side effects.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FriendServiceImpl (unit)")
class FriendServiceImplTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private FriendRepository friendRepository;
    @Mock
    private FriendRequestRepository friendRequestRepository;
    @Mock
    private BlockUserRepository blockUserRepository;
    @Mock
    private BlockCache blockCache;
    @Mock
    private UserSettingRepository userSettingRepository;
    @Mock
    private FriendRequestMapper friendRequestMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private PresenceService presenceService;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    private FriendServiceImpl service;

    private User me;
    private User other;

    @BeforeEach
    void setUp() {
        service = new FriendServiceImpl(userRepository, friendRepository, friendRequestRepository,
                blockUserRepository, blockCache, userSettingRepository, friendRequestMapper,
                userMapper, presenceService, messagingTemplate, redisTemplate);
        me = user(1L, "me");
        other = user(2L, "other");
    }

    private User user(long id, String username) {
        User u = User.builder().username(username).name("Name " + id).build();
        u.setId(id);
        u.setUuid(UUID.randomUUID());
        return u;
    }

    private FriendRequest request(User sender, User receiver, FriendRequestStatus status) {
        FriendRequest r = FriendRequest.builder().sender(sender).receiver(receiver).status(status).build();
        r.setUuid(UUID.randomUUID());
        return r;
    }

    /**
     * Lets the anti-spam quota pass (count = 1 → also triggers the expire()).
     */
    private void quotaAllows() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(1L);
    }

    // ─────────────────────────────────────────── sendFriendRequest ───────────────────────────────

    @Nested
    @DisplayName("sendFriendRequest")
    class SendFriendRequest {

        @Test
        @DisplayName("brand-new request → PENDING row saved, receiver notified, quota consumed")
        void newRequest() {
            when(userRepository.findByUuid(any())).thenReturn(Optional.of(other));
            when(friendRepository.findByUserAndFriend(me, other)).thenReturn(Optional.empty());
            when(friendRequestRepository.findFirstBySenderAndReceiverOrderByIdDesc(me, other))
                    .thenReturn(Optional.empty());
            when(friendRequestRepository.findFirstBySenderAndReceiverOrderByIdDesc(other, me))
                    .thenReturn(Optional.empty());
            quotaAllows();
            FriendRequest saved = request(me, other, FriendRequestStatus.PENDING);
            when(friendRequestRepository.saveAndFlush(any())).thenReturn(saved);
            FriendRequestResponse dto = FriendRequestResponse.builder().id("x").build();
            when(friendRequestMapper.toResponse(saved)).thenReturn(dto);

            FriendRequestResponse result = service.sendFriendRequest(other.getUuid().toString(), me);

            assertThat(result).isSameAs(dto);
            ArgumentCaptor<FriendRequest> captor = ArgumentCaptor.forClass(FriendRequest.class);
            verify(friendRequestRepository).saveAndFlush(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(FriendRequestStatus.PENDING);
            assertThat(captor.getValue().getSender()).isSameAs(me);
            assertThat(captor.getValue().getReceiver()).isSameAs(other);
            verify(valueOps).increment(anyString());
            verify(redisTemplate).expire(anyString(), eq(Duration.ofDays(1)));
            verify(messagingTemplate).convertAndSendToUser(eq("other"), eq("/queue/friends"), any());
        }

        @Test
        @DisplayName("receiver uuid not found → NotFoundException TM_064")
        void receiverNotFound() {
            when(userRepository.findByUuid(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.sendFriendRequest(UUID.randomUUID().toString(), me))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_064"));
        }

        @Test
        @DisplayName("sending to yourself → BadRequestException TM_097")
        void selfRequest() {
            when(userRepository.findByUuid(any())).thenReturn(Optional.of(me));

            assertThatThrownBy(() -> service.sendFriendRequest(me.getUuid().toString(), me))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_097"));
        }

        @Test
        @DisplayName("either side has blocked the other → ForbiddenException TM_103")
        void blocked() {
            when(userRepository.findByUuid(any())).thenReturn(Optional.of(other));
            when(blockUserRepository.existsByUserAndBlocked(other, me)).thenReturn(true);

            assertThatThrownBy(() -> service.sendFriendRequest(other.getUuid().toString(), me))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_103"));
            verify(friendRequestRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("already friends → ConflictException TM_096")
        void alreadyFriends() {
            when(userRepository.findByUuid(any())).thenReturn(Optional.of(other));
            when(friendRepository.findByUserAndFriend(me, other))
                    .thenReturn(Optional.of(Friend.builder().user(me).friend(other).build()));

            assertThatThrownBy(() -> service.sendFriendRequest(other.getUuid().toString(), me))
                    .isInstanceOfSatisfying(ConflictException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_096"));
        }

        @Test
        @DisplayName("prior ACCEPTED request (unfriended) is reused → set PENDING, no quota consumed")
        void reuseAcceptedRequest() {
            when(userRepository.findByUuid(any())).thenReturn(Optional.of(other));
            when(friendRepository.findByUserAndFriend(me, other)).thenReturn(Optional.empty());
            FriendRequest existing = request(me, other, FriendRequestStatus.ACCEPTED);
            when(friendRequestRepository.findFirstBySenderAndReceiverOrderByIdDesc(me, other))
                    .thenReturn(Optional.of(existing));
            when(friendRequestRepository.save(existing)).thenReturn(existing);
            FriendRequestResponse dto = FriendRequestResponse.builder().id("x").build();
            when(friendRequestMapper.toResponse(existing)).thenReturn(dto);

            FriendRequestResponse result = service.sendFriendRequest(other.getUuid().toString(), me);

            assertThat(result).isSameAs(dto);
            assertThat(existing.getStatus()).isEqualTo(FriendRequestStatus.PENDING);
            verify(friendRequestRepository).save(existing);
            verify(friendRequestRepository, never()).saveAndFlush(any());
            verify(valueOps, never()).increment(anyString());
            verify(messagingTemplate).convertAndSendToUser(eq("other"), eq("/queue/friends"), any());
        }

        @Test
        @DisplayName("prior REJECTED request is reused → set PENDING, no quota consumed")
        void reuseRejectedRequest() {
            when(userRepository.findByUuid(any())).thenReturn(Optional.of(other));
            when(friendRepository.findByUserAndFriend(me, other)).thenReturn(Optional.empty());
            FriendRequest existing = request(me, other, FriendRequestStatus.REJECTED);
            when(friendRequestRepository.findFirstBySenderAndReceiverOrderByIdDesc(me, other))
                    .thenReturn(Optional.of(existing));
            when(friendRequestRepository.save(existing)).thenReturn(existing);
            when(friendRequestMapper.toResponse(existing))
                    .thenReturn(FriendRequestResponse.builder().build());

            service.sendFriendRequest(other.getUuid().toString(), me);

            assertThat(existing.getStatus()).isEqualTo(FriendRequestStatus.PENDING);
            verify(friendRequestRepository).save(existing);
            verify(valueOps, never()).increment(anyString());
        }

        @Test
        @DisplayName("reverse PENDING request → auto-accepts (creates mutual friendships)")
        void autoAcceptsReverse() {
            when(userRepository.findByUuid(any())).thenReturn(Optional.of(other));
            when(friendRepository.findByUserAndFriend(me, other)).thenReturn(Optional.empty());
            when(friendRequestRepository.findFirstBySenderAndReceiverOrderByIdDesc(me, other))
                    .thenReturn(Optional.empty());
            FriendRequest reverse = request(other, me, FriendRequestStatus.PENDING);
            when(friendRequestRepository.findFirstBySenderAndReceiverOrderByIdDesc(other, me))
                    .thenReturn(Optional.of(reverse));
            when(friendRequestRepository.findByUuid(reverse.getUuid())).thenReturn(Optional.of(reverse));
            FriendRequestResponse dto = FriendRequestResponse.builder().build();
            when(friendRequestMapper.toResponse(reverse)).thenReturn(dto);

            FriendRequestResponse result = service.sendFriendRequest(other.getUuid().toString(), me);

            assertThat(result).isSameAs(dto);
            assertThat(reverse.getStatus()).isEqualTo(FriendRequestStatus.ACCEPTED);
            // Two mutual Friend rows persisted by the auto-accept.
            verify(friendRepository, times(2)).save(any(Friend.class));
            // Reuse existing row → no new outbound request, no quota consumed.
            verify(friendRequestRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("daily quota exceeded → TooManyRequestsException TM_498, nothing saved")
        void quotaExceeded() {
            when(userRepository.findByUuid(any())).thenReturn(Optional.of(other));
            when(friendRepository.findByUserAndFriend(me, other)).thenReturn(Optional.empty());
            when(friendRequestRepository.findFirstBySenderAndReceiverOrderByIdDesc(me, other))
                    .thenReturn(Optional.empty());
            when(friendRequestRepository.findFirstBySenderAndReceiverOrderByIdDesc(other, me))
                    .thenReturn(Optional.empty());
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.increment(anyString())).thenReturn(51L);

            assertThatThrownBy(() -> service.sendFriendRequest(other.getUuid().toString(), me))
                    .isInstanceOfSatisfying(TooManyRequestsException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_498"));
            verify(friendRequestRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("Redis error during quota check is swallowed (fail-open) → request still saved")
        void quotaFailOpen() {
            when(userRepository.findByUuid(any())).thenReturn(Optional.of(other));
            when(friendRepository.findByUserAndFriend(me, other)).thenReturn(Optional.empty());
            when(friendRequestRepository.findFirstBySenderAndReceiverOrderByIdDesc(me, other))
                    .thenReturn(Optional.empty());
            when(friendRequestRepository.findFirstBySenderAndReceiverOrderByIdDesc(other, me))
                    .thenReturn(Optional.empty());
            when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));
            FriendRequest saved = request(me, other, FriendRequestStatus.PENDING);
            when(friendRequestRepository.saveAndFlush(any())).thenReturn(saved);
            when(friendRequestMapper.toResponse(saved)).thenReturn(FriendRequestResponse.builder().build());

            service.sendFriendRequest(other.getUuid().toString(), me);

            verify(friendRequestRepository).saveAndFlush(any());
        }

        @Test
        @DisplayName("concurrent duplicate insert → reuses the winning row instead of failing")
        void duplicateRaceReusesWinner() {
            when(userRepository.findByUuid(any())).thenReturn(Optional.of(other));
            when(friendRepository.findByUserAndFriend(me, other)).thenReturn(Optional.empty());
            FriendRequest winner = request(me, other, FriendRequestStatus.REJECTED);
            when(friendRequestRepository.findFirstBySenderAndReceiverOrderByIdDesc(me, other))
                    .thenReturn(Optional.empty())          // initial lookup: nothing yet
                    .thenReturn(Optional.of(winner));      // after the collision: the winning row
            when(friendRequestRepository.findFirstBySenderAndReceiverOrderByIdDesc(other, me))
                    .thenReturn(Optional.empty());
            quotaAllows();
            when(friendRequestRepository.saveAndFlush(any()))
                    .thenThrow(new DataIntegrityViolationException("uk_friend_request"));
            when(friendRequestRepository.save(winner)).thenReturn(winner);
            when(friendRequestMapper.toResponse(winner)).thenReturn(FriendRequestResponse.builder().build());

            service.sendFriendRequest(other.getUuid().toString(), me);

            assertThat(winner.getStatus()).isEqualTo(FriendRequestStatus.PENDING);
            verify(friendRequestRepository).save(winner);
        }

        @Test
        @DisplayName("duplicate insert but winning row cannot be re-read → rethrows the violation")
        void duplicateRaceWinnerVanished() {
            when(userRepository.findByUuid(any())).thenReturn(Optional.of(other));
            when(friendRepository.findByUserAndFriend(me, other)).thenReturn(Optional.empty());
            when(friendRequestRepository.findFirstBySenderAndReceiverOrderByIdDesc(me, other))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.empty());
            when(friendRequestRepository.findFirstBySenderAndReceiverOrderByIdDesc(other, me))
                    .thenReturn(Optional.empty());
            quotaAllows();
            DataIntegrityViolationException dup = new DataIntegrityViolationException("dup");
            when(friendRequestRepository.saveAndFlush(any())).thenThrow(dup);

            assertThatThrownBy(() -> service.sendFriendRequest(other.getUuid().toString(), me))
                    .isSameAs(dup);
        }
    }

    // ─────────────────────────────────────────── acceptFriendRequest ─────────────────────────────

    @Nested
    @DisplayName("acceptFriendRequest")
    class AcceptFriendRequest {

        @Test
        @DisplayName("receiver accepts pending → status ACCEPTED, two Friend rows, both parties notified")
        void accepts() {
            FriendRequest r = request(other, me, FriendRequestStatus.PENDING);
            when(friendRequestRepository.findByUuid(r.getUuid())).thenReturn(Optional.of(r));

            service.acceptFriendRequest(r.getUuid().toString(), me);

            assertThat(r.getStatus()).isEqualTo(FriendRequestStatus.ACCEPTED);
            verify(friendRequestRepository).save(r);
            ArgumentCaptor<Friend> friends = ArgumentCaptor.forClass(Friend.class);
            verify(friendRepository, times(2)).save(friends.capture());
            assertThat(friends.getAllValues()).extracting(Friend::getUser)
                    .containsExactlyInAnyOrder(other, me);
            verify(messagingTemplate).convertAndSendToUser(eq("other"), eq("/queue/friends"), any());
            verify(messagingTemplate).convertAndSendToUser(eq("me"), eq("/queue/friends"), any());
        }

        @Test
        @DisplayName("request not found → NotFoundException TM_094")
        void notFound() {
            when(friendRequestRepository.findByUuid(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.acceptFriendRequest(UUID.randomUUID().toString(), me))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_094"));
        }

        @Test
        @DisplayName("accepting a request not addressed to you → ForbiddenException TM_103")
        void notReceiver() {
            FriendRequest r = request(me, other, FriendRequestStatus.PENDING); // receiver is 'other'
            when(friendRequestRepository.findByUuid(r.getUuid())).thenReturn(Optional.of(r));

            assertThatThrownBy(() -> service.acceptFriendRequest(r.getUuid().toString(), me))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_103"));
            verify(friendRepository, never()).save(any());
        }

        @Test
        @DisplayName("request already processed (not PENDING) → ConflictException TM_096")
        void alreadyProcessed() {
            FriendRequest r = request(other, me, FriendRequestStatus.ACCEPTED);
            when(friendRequestRepository.findByUuid(r.getUuid())).thenReturn(Optional.of(r));

            assertThatThrownBy(() -> service.acceptFriendRequest(r.getUuid().toString(), me))
                    .isInstanceOfSatisfying(ConflictException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_096"));
        }
    }

    // ─────────────────────────────────────────── rejectFriendRequest ─────────────────────────────

    @Nested
    @DisplayName("rejectFriendRequest")
    class RejectFriendRequest {

        @Test
        @DisplayName("receiver rejects pending → status REJECTED, both parties notified")
        void rejects() {
            FriendRequest r = request(other, me, FriendRequestStatus.PENDING);
            when(friendRequestRepository.findByUuid(r.getUuid())).thenReturn(Optional.of(r));

            service.rejectFriendRequest(r.getUuid().toString(), me);

            assertThat(r.getStatus()).isEqualTo(FriendRequestStatus.REJECTED);
            verify(friendRequestRepository).save(r);
            verify(messagingTemplate).convertAndSendToUser(eq("other"), eq("/queue/friends"), any());
            verify(messagingTemplate).convertAndSendToUser(eq("me"), eq("/queue/friends"), any());
        }

        @Test
        @DisplayName("request not found → NotFoundException TM_094")
        void notFound() {
            when(friendRequestRepository.findByUuid(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.rejectFriendRequest(UUID.randomUUID().toString(), me))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_094"));
        }

        @Test
        @DisplayName("rejecting a request not addressed to you → ForbiddenException TM_103")
        void notReceiver() {
            FriendRequest r = request(me, other, FriendRequestStatus.PENDING);
            when(friendRequestRepository.findByUuid(r.getUuid())).thenReturn(Optional.of(r));

            assertThatThrownBy(() -> service.rejectFriendRequest(r.getUuid().toString(), me))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_103"));
        }

        @Test
        @DisplayName("request already processed → ConflictException TM_096")
        void alreadyProcessed() {
            FriendRequest r = request(other, me, FriendRequestStatus.REJECTED);
            when(friendRequestRepository.findByUuid(r.getUuid())).thenReturn(Optional.of(r));

            assertThatThrownBy(() -> service.rejectFriendRequest(r.getUuid().toString(), me))
                    .isInstanceOfSatisfying(ConflictException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_096"));
        }
    }

    // ─────────────────────────────────────── respondToFriendRequests (bulk) ──────────────────────

    @Nested
    @DisplayName("respondToFriendRequests (bulk)")
    class RespondToFriendRequests {

        @Test
        @DisplayName("accept all pending → each ACCEPTED, two Friend rows each, returns processed count")
        void acceptsAll() {
            FriendRequest r1 = request(other, me, FriendRequestStatus.PENDING);
            User third = user(3L, "third");
            FriendRequest r2 = request(third, me, FriendRequestStatus.PENDING);
            when(friendRequestRepository.findByUuid(r1.getUuid())).thenReturn(Optional.of(r1));
            when(friendRequestRepository.findByUuid(r2.getUuid())).thenReturn(Optional.of(r2));

            int processed = service.respondToFriendRequests(
                    List.of(r1.getUuid().toString(), r2.getUuid().toString()), true, me);

            assertThat(processed).isEqualTo(2);
            assertThat(r1.getStatus()).isEqualTo(FriendRequestStatus.ACCEPTED);
            assertThat(r2.getStatus()).isEqualTo(FriendRequestStatus.ACCEPTED);
            verify(friendRepository, times(4)).save(any()); // 2 Friend rows per accept
        }

        @Test
        @DisplayName("reject all pending → each REJECTED, no Friend rows, returns processed count")
        void rejectsAll() {
            FriendRequest r1 = request(other, me, FriendRequestStatus.PENDING);
            when(friendRequestRepository.findByUuid(r1.getUuid())).thenReturn(Optional.of(r1));

            int processed = service.respondToFriendRequests(List.of(r1.getUuid().toString()), false, me);

            assertThat(processed).isEqualTo(1);
            assertThat(r1.getStatus()).isEqualTo(FriendRequestStatus.REJECTED);
            verify(friendRepository, never()).save(any());
        }

        @Test
        @DisplayName("stale / not-mine / already-processed ids are skipped; valid ones still processed")
        void skipsBadIdsButProcessesValid() {
            FriendRequest ok = request(other, me, FriendRequestStatus.PENDING);
            FriendRequest notMine = request(me, other, FriendRequestStatus.PENDING); // receiver != me
            FriendRequest done = request(other, me, FriendRequestStatus.ACCEPTED);    // not PENDING
            UUID missing = UUID.randomUUID();
            when(friendRequestRepository.findByUuid(ok.getUuid())).thenReturn(Optional.of(ok));
            when(friendRequestRepository.findByUuid(notMine.getUuid())).thenReturn(Optional.of(notMine));
            when(friendRequestRepository.findByUuid(done.getUuid())).thenReturn(Optional.of(done));
            when(friendRequestRepository.findByUuid(missing)).thenReturn(Optional.empty());

            int processed = service.respondToFriendRequests(
                    List.of(ok.getUuid().toString(), notMine.getUuid().toString(),
                            done.getUuid().toString(), missing.toString()), true, me);

            assertThat(processed).isEqualTo(1);
            assertThat(ok.getStatus()).isEqualTo(FriendRequestStatus.ACCEPTED);
            assertThat(notMine.getStatus()).isEqualTo(FriendRequestStatus.PENDING); // untouched
            assertThat(done.getStatus()).isEqualTo(FriendRequestStatus.ACCEPTED);   // untouched
        }

        @Test
        @DisplayName("duplicate ids are de-duplicated → processed once")
        void dedupes() {
            FriendRequest r = request(other, me, FriendRequestStatus.PENDING);
            when(friendRequestRepository.findByUuid(r.getUuid())).thenReturn(Optional.of(r));

            int processed = service.respondToFriendRequests(
                    List.of(r.getUuid().toString(), r.getUuid().toString()), true, me);

            assertThat(processed).isEqualTo(1);
            verify(friendRequestRepository).save(r); // saved exactly once
        }

        @Test
        @DisplayName("empty or null list → returns 0, no repository interaction")
        void emptyOrNull() {
            assertThat(service.respondToFriendRequests(List.of(), true, me)).isZero();
            assertThat(service.respondToFriendRequests(null, true, me)).isZero();
            verifyNoInteractions(friendRequestRepository);
        }
    }

    // ─────────────────────────────────────────── cancelFriendRequest ─────────────────────────────

    @Nested
    @DisplayName("cancelFriendRequest")
    class CancelFriendRequest {

        @Test
        @DisplayName("sender cancels → row deleted, receiver notified")
        void cancels() {
            FriendRequest r = request(me, other, FriendRequestStatus.PENDING);
            when(friendRequestRepository.findByUuid(r.getUuid())).thenReturn(Optional.of(r));

            service.cancelFriendRequest(r.getUuid().toString(), me);

            verify(friendRequestRepository).delete(r);
            verify(messagingTemplate).convertAndSendToUser(eq("other"), eq("/queue/friends"), any());
        }

        @Test
        @DisplayName("request not found → NotFoundException TM_094")
        void notFound() {
            when(friendRequestRepository.findByUuid(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.cancelFriendRequest(UUID.randomUUID().toString(), me))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_094"));
        }

        @Test
        @DisplayName("cancelling a request you did not send → ForbiddenException TM_103")
        void notSender() {
            FriendRequest r = request(other, me, FriendRequestStatus.PENDING); // sender is 'other'
            when(friendRequestRepository.findByUuid(r.getUuid())).thenReturn(Optional.of(r));

            assertThatThrownBy(() -> service.cancelFriendRequest(r.getUuid().toString(), me))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_103"));
            verify(friendRequestRepository, never()).delete(any());
        }
    }

    // ─────────────────────────────────────────── getFriends ──────────────────────────────────────

    @Nested
    @DisplayName("getFriends")
    class GetFriends {

        @Test
        @DisplayName("maps friends with presence, apparent last-seen and friends-only flag")
        void mapsFriends() {
            when(friendRepository.findFriendsByUser(me)).thenReturn(List.of(other));
            when(userSettingRepository.findFriendsOnlyUserIds(any())).thenReturn(Set.of(other.getId()));
            when(userMapper.toAuthUserResponse(other)).thenReturn(AuthUserResponse.builder().build());
            when(presenceService.getStatus(other)).thenReturn(PresenceStatus.ONLINE);
            Instant seen = Instant.parse("2026-07-30T00:00:00Z");
            when(presenceService.getApparentLastSeen(other)).thenReturn(seen);

            List<AuthUserResponse> result = service.getFriends(me);

            assertThat(result).hasSize(1);
            AuthUserResponse dto = result.get(0);
            assertThat(dto.getPresence()).isEqualTo("online");
            assertThat(dto.getLastSeen()).isEqualTo(seen.toString());
            assertThat(dto.getMessagingFriendsOnly()).isTrue();
        }

        @Test
        @DisplayName("apparent last-seen null (Invisible/Hide-last-seen) → lastSeen left unset")
        void hiddenLastSeen() {
            when(friendRepository.findFriendsByUser(me)).thenReturn(List.of(other));
            when(userSettingRepository.findFriendsOnlyUserIds(any())).thenReturn(Set.of());
            when(userMapper.toAuthUserResponse(other)).thenReturn(AuthUserResponse.builder().build());
            when(presenceService.getStatus(other)).thenReturn(PresenceStatus.OFFLINE);
            when(presenceService.getApparentLastSeen(other)).thenReturn(null);

            AuthUserResponse dto = service.getFriends(me).get(0);

            assertThat(dto.getPresence()).isEqualTo("offline");
            assertThat(dto.getLastSeen()).isNull();
            assertThat(dto.getMessagingFriendsOnly()).isFalse();
        }

        @Test
        @DisplayName("no friends → empty list, friends-only lookup skipped")
        void noFriends() {
            when(friendRepository.findFriendsByUser(me)).thenReturn(List.of());

            List<AuthUserResponse> result = service.getFriends(me);

            assertThat(result).isEmpty();
            verify(userSettingRepository, never()).findFriendsOnlyUserIds(any());
        }
    }

    // ─────────────────────────────────────────── getFriendRequests ───────────────────────────────

    @Nested
    @DisplayName("getFriendRequests")
    class GetFriendRequests {

        @Test
        @DisplayName("returns mapped pending requests addressed to the user")
        void returnsPending() {
            FriendRequest r = request(other, me, FriendRequestStatus.PENDING);
            when(friendRequestRepository.findByReceiverAndStatusOrderByCreatedAtDesc(me, FriendRequestStatus.PENDING))
                    .thenReturn(List.of(r));
            FriendRequestResponse dto = FriendRequestResponse.builder().build();
            when(friendRequestMapper.toResponse(r)).thenReturn(dto);

            assertThat(service.getFriendRequests(me)).containsExactly(dto);
        }

        @Test
        @DisplayName("no pending requests → empty list")
        void empty() {
            when(friendRequestRepository.findByReceiverAndStatusOrderByCreatedAtDesc(me, FriendRequestStatus.PENDING))
                    .thenReturn(List.of());

            assertThat(service.getFriendRequests(me)).isEmpty();
        }
    }

    // ─────────────────────────────────────────── removeFriend ────────────────────────────────────

    @Nested
    @DisplayName("removeFriend")
    class RemoveFriend {

        @Test
        @DisplayName("both directions present → both deleted, requests purged, both notified")
        void removesBoth() {
            when(userRepository.findByUuid(any())).thenReturn(Optional.of(other));
            Friend f1 = Friend.builder().user(me).friend(other).build();
            Friend f2 = Friend.builder().user(other).friend(me).build();
            when(friendRepository.findByUserAndFriend(me, other)).thenReturn(Optional.of(f1));
            when(friendRepository.findByUserAndFriend(other, me)).thenReturn(Optional.of(f2));
            when(friendRequestRepository.findAllBySenderAndReceiver(any(), any())).thenReturn(List.of());

            service.removeFriend(other.getUuid().toString(), me);

            verify(friendRepository).delete(f1);
            verify(friendRepository).delete(f2);
            // Requests are purged in both directions so they can re-add cleanly.
            verify(friendRequestRepository, times(2)).deleteAll(any());
            verify(messagingTemplate).convertAndSendToUser(eq("me"), eq("/queue/friends"), any());
            verify(messagingTemplate).convertAndSendToUser(eq("other"), eq("/queue/friends"), any());
        }

        @Test
        @DisplayName("only one direction present → the missing side is not deleted (no NPE)")
        void onlyOneDirection() {
            when(userRepository.findByUuid(any())).thenReturn(Optional.of(other));
            Friend f1 = Friend.builder().user(me).friend(other).build();
            when(friendRepository.findByUserAndFriend(me, other)).thenReturn(Optional.of(f1));
            when(friendRepository.findByUserAndFriend(other, me)).thenReturn(Optional.empty());
            when(friendRequestRepository.findAllBySenderAndReceiver(any(), any())).thenReturn(List.of());

            service.removeFriend(other.getUuid().toString(), me);

            verify(friendRepository, times(1)).delete(any(Friend.class));
            verify(friendRepository).delete(f1);
        }

        @Test
        @DisplayName("friend user not found → NotFoundException TM_064")
        void notFound() {
            when(userRepository.findByUuid(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.removeFriend(UUID.randomUUID().toString(), me))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_064"));
        }
    }

    // ─────────────────────────────────────────── blockUser ───────────────────────────────────────

    @Nested
    @DisplayName("blockUser")
    class BlockUserTests {

        @Test
        @DisplayName("new block → row saved, cache evicted, friendship removed")
        void blocks() {
            when(userRepository.findByUuid(any())).thenReturn(Optional.of(other));
            when(blockUserRepository.existsByUserAndBlocked(me, other)).thenReturn(false);
            when(friendRepository.findByUserAndFriend(any(), any())).thenReturn(Optional.empty());
            when(friendRequestRepository.findAllBySenderAndReceiver(any(), any())).thenReturn(List.of());

            service.blockUser(other.getUuid().toString(), me);

            ArgumentCaptor<BlockUser> captor = ArgumentCaptor.forClass(BlockUser.class);
            verify(blockUserRepository).save(captor.capture());
            assertThat(captor.getValue().getUser()).isSameAs(me);
            assertThat(captor.getValue().getBlocked()).isSameAs(other);
            verify(blockCache).evict(me.getId());
            // removeFriend side effect fired (friend-removed broadcast to both parties).
            verify(messagingTemplate).convertAndSendToUser(eq("me"), eq("/queue/friends"), any());
        }

        @Test
        @DisplayName("already blocked → no-op (no save, no evict, no unfriend)")
        void alreadyBlocked() {
            when(userRepository.findByUuid(any())).thenReturn(Optional.of(other));
            when(blockUserRepository.existsByUserAndBlocked(me, other)).thenReturn(true);

            service.blockUser(other.getUuid().toString(), me);

            verify(blockUserRepository, never()).save(any());
            verify(blockCache, never()).evict(any());
            verifyNoInteractions(messagingTemplate);
        }

        @Test
        @DisplayName("blocking yourself → BadRequestException TM_071")
        void selfBlock() {
            when(userRepository.findByUuid(any())).thenReturn(Optional.of(me));

            assertThatThrownBy(() -> service.blockUser(me.getUuid().toString(), me))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_071"));
        }

        @Test
        @DisplayName("target user not found → NotFoundException TM_064")
        void notFound() {
            when(userRepository.findByUuid(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.blockUser(UUID.randomUUID().toString(), me))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_064"));
        }
    }

    // ─────────────────────────────────────────── unblockUser ─────────────────────────────────────

    @Nested
    @DisplayName("unblockUser")
    class UnblockUser {

        @Test
        @DisplayName("existing block → deleted and cache evicted")
        void unblocks() {
            when(userRepository.findByUuid(any())).thenReturn(Optional.of(other));
            BlockUser block = BlockUser.builder().user(me).blocked(other).build();
            when(blockUserRepository.findByUserAndBlocked(me, other)).thenReturn(Optional.of(block));

            service.unblockUser(other.getUuid().toString(), me);

            verify(blockUserRepository).delete(block);
            verify(blockCache).evict(me.getId());
        }

        @Test
        @DisplayName("no existing block → no-op (no delete, no evict)")
        void noBlock() {
            when(userRepository.findByUuid(any())).thenReturn(Optional.of(other));
            when(blockUserRepository.findByUserAndBlocked(me, other)).thenReturn(Optional.empty());

            service.unblockUser(other.getUuid().toString(), me);

            verify(blockUserRepository, never()).delete(any());
            verify(blockCache, never()).evict(any());
        }

        @Test
        @DisplayName("target user not found → NotFoundException TM_064")
        void notFound() {
            when(userRepository.findByUuid(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.unblockUser(UUID.randomUUID().toString(), me))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_064"));
        }
    }
}
