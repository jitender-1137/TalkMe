package com.neo.chat.service.impl;

import com.neo.chat.domain.Notification;
import com.neo.chat.domain.User;
import com.neo.chat.dto.response.NotificationResponse;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.repository.FriendRepository;
import com.neo.chat.repository.NotificationRepository;
import com.neo.chat.repository.UserFollowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link NotificationServiceImpl} — persistence + WebSocket
 * fan-out of user notifications.
 *
 * <p>Key invariants: (1) mark-as-read enforces ownership via
 * {@code findByUuidAndUser} → {@code TM_002} when absent; (2) {@code createNotification}
 * always persists then best-effort broadcasts (a WS failure never propagates); (3) the
 * fan-out helpers ({@code notifyFriends} / {@code notifyFollowersAndFollowing}) are
 * fail-open on the graph load, skip self/guest/deleted/null recipients, de-dup the follow
 * graph, and isolate a single failing recipient from the rest of the batch.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationServiceImpl (unit)")
class NotificationServiceImplTest {

    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private FriendRepository friendRepository;
    @Mock
    private UserFollowRepository userFollowRepository;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private NotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NotificationServiceImpl(
                notificationRepository, friendRepository, userFollowRepository, messagingTemplate);
    }

    private static User user(long id, String username) {
        User u = User.builder().username(username).name("Name-" + username).build();
        u.setId(id);
        u.setUuid(UUID.randomUUID());
        return u;
    }

    private static Notification notification(String title, String content, String type) {
        Notification n = Notification.builder()
                .title(title).content(content).type(type)
                .referenceId("ref-1").isRead(false).build();
        n.setUuid(UUID.randomUUID());
        n.setCreatedAt(Instant.parse("2026-07-30T10:15:30Z"));
        return n;
    }

    /**
     * Make repository.save echo the argument back with a uuid assigned (mirrors @PrePersist).
     */
    private void saveEchoesWithUuid() {
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> {
            Notification n = inv.getArgument(0);
            if (n.getUuid() == null) n.setUuid(UUID.randomUUID());
            return n;
        });
    }

    @Nested
    @DisplayName("getNotifications")
    class GetNotifications {

        @Test
        @DisplayName("maps each entity to a response DTO, preserving fields")
        void mapsPage() {
            User me = user(1L, "alice");
            Notification n = notification("Liked your post", "bob liked it", "LIKE");
            n.setActorId("actor-uuid");
            n.setActorName("Bob");
            n.setActorAvatar("http://img/bob.png");
            n.setImageUrl("http://img/post.png");
            n.setRead(true);
            Pageable pageable = PageRequest.of(0, 20);
            Page<Notification> page = new PageImpl<>(List.of(n), pageable, 1);
            when(notificationRepository.findByUser(me, pageable)).thenReturn(page);

            Page<NotificationResponse> result = service.getNotifications(pageable, me);

            assertThat(result.getContent()).hasSize(1);
            NotificationResponse r = result.getContent().get(0);
            assertThat(r.getId()).isEqualTo(n.getUuid().toString());
            assertThat(r.getTitle()).isEqualTo("Liked your post");
            assertThat(r.getContent()).isEqualTo("bob liked it");
            assertThat(r.getType()).isEqualTo("LIKE");
            assertThat(r.isRead()).isTrue();
            assertThat(r.getReferenceId()).isEqualTo("ref-1");
            assertThat(r.getActorId()).isEqualTo("actor-uuid");
            assertThat(r.getActorName()).isEqualTo("Bob");
            assertThat(r.getActorAvatar()).isEqualTo("http://img/bob.png");
            assertThat(r.getImageUrl()).isEqualTo("http://img/post.png");
            assertThat(r.getCreatedAt()).isEqualTo("2026-07-30T10:15:30Z");
        }

        @Test
        @DisplayName("empty page → empty result, no NPE")
        void emptyPage() {
            User me = user(1L, "alice");
            Pageable pageable = PageRequest.of(0, 20);
            when(notificationRepository.findByUser(me, pageable))
                    .thenReturn(new PageImpl<>(List.of(), pageable, 0));

            assertThat(service.getNotifications(pageable, me).getContent()).isEmpty();
        }

        @Test
        @DisplayName("null createdAt → null createdAt in the DTO (no NPE)")
        void nullCreatedAt() {
            User me = user(1L, "alice");
            Notification n = notification("t", "c", "SYSTEM");
            n.setCreatedAt(null);
            Pageable pageable = PageRequest.of(0, 20);
            when(notificationRepository.findByUser(me, pageable))
                    .thenReturn(new PageImpl<>(List.of(n), pageable, 1));

            assertThat(service.getNotifications(pageable, me).getContent().get(0).getCreatedAt()).isNull();
        }
    }

    @Nested
    @DisplayName("markAsRead")
    class MarkAsRead {

        @Test
        @DisplayName("owned notification → flips isRead and saves")
        void marksRead() {
            User me = user(1L, "alice");
            Notification n = notification("t", "c", "LIKE");
            UUID uuid = n.getUuid();
            when(notificationRepository.findByUuidAndUser(uuid, me)).thenReturn(Optional.of(n));
            saveEchoesWithUuid();

            service.markAsRead(uuid.toString(), me);

            ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository).save(captor.capture());
            assertThat(captor.getValue().isRead()).isTrue();
        }

        @Test
        @DisplayName("not found / not owned → NotFoundException TM_002, no save")
        void notFound() {
            User me = user(1L, "alice");
            UUID uuid = UUID.randomUUID();
            when(notificationRepository.findByUuidAndUser(uuid, me)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.markAsRead(uuid.toString(), me))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_002"));
            verify(notificationRepository, never()).save(any());
        }

        @Test
        @DisplayName("malformed uuid → IllegalArgumentException before any repo hit")
        void malformedUuid() {
            User me = user(1L, "alice");

            assertThatThrownBy(() -> service.markAsRead("not-a-uuid", me))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(notificationRepository, never()).findByUuidAndUser(any(), any());
        }
    }

    @Nested
    @DisplayName("markAllAsRead")
    class MarkAllAsRead {

        @Test
        @DisplayName("delegates to the bulk update query")
        void delegates() {
            User me = user(1L, "alice");

            service.markAllAsRead(me);

            verify(notificationRepository).markAllAsRead(me);
        }
    }

    @Nested
    @DisplayName("createNotification")
    class CreateNotification {

        @Test
        @DisplayName("persists an unread notification and broadcasts it to the user's queue")
        void persistsAndBroadcasts() {
            User me = user(1L, "alice");
            User actor = user(2L, "bob");
            actor.setProfileImage("http://img/bob.png");
            saveEchoesWithUuid();

            service.createNotification(me, "New follower", "bob followed you", "FOLLOW",
                    "ref-9", actor, "http://img/thumb.png");

            ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository).save(saved.capture());
            Notification n = saved.getValue();
            assertThat(n.getUser()).isSameAs(me);
            assertThat(n.getTitle()).isEqualTo("New follower");
            assertThat(n.getContent()).isEqualTo("bob followed you");
            assertThat(n.getType()).isEqualTo("FOLLOW");
            assertThat(n.getReferenceId()).isEqualTo("ref-9");
            assertThat(n.getActorId()).isEqualTo(actor.getUuid().toString());
            assertThat(n.getActorName()).isEqualTo("Name-bob");
            assertThat(n.getActorAvatar()).isEqualTo("http://img/bob.png");
            assertThat(n.getImageUrl()).isEqualTo("http://img/thumb.png");
            assertThat(n.isRead()).isFalse();

            ArgumentCaptor<NotificationResponse> payload = ArgumentCaptor.forClass(NotificationResponse.class);
            verify(messagingTemplate).convertAndSendToUser(eq("alice"), eq("/queue/notifications"), payload.capture());
            assertThat(payload.getValue().getTitle()).isEqualTo("New follower");
            assertThat(payload.getValue().getActorName()).isEqualTo("Name-bob");
        }

        @Test
        @DisplayName("4-arg overload → null actor/image, actor fields null")
        void overloadWithoutActor() {
            User me = user(1L, "alice");
            saveEchoesWithUuid();

            service.createNotification(me, "System", "maintenance", "SYSTEM", "ref-0");

            ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository).save(saved.capture());
            Notification n = saved.getValue();
            assertThat(n.getActorId()).isNull();
            assertThat(n.getActorName()).isNull();
            assertThat(n.getActorAvatar()).isNull();
            assertThat(n.getImageUrl()).isNull();
            verify(messagingTemplate).convertAndSendToUser(eq("alice"), eq("/queue/notifications"), any(NotificationResponse.class));
        }

        @Test
        @DisplayName("actor present but null uuid → actorId null, actorName still set")
        void actorWithNullUuid() {
            User me = user(1L, "alice");
            User actor = user(2L, "bob");
            actor.setUuid(null);
            saveEchoesWithUuid();

            service.createNotification(me, "t", "c", "LIKE", "ref", actor, null);

            ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository).save(saved.capture());
            assertThat(saved.getValue().getActorId()).isNull();
            assertThat(saved.getValue().getActorName()).isEqualTo("Name-bob");
        }

        @Test
        @DisplayName("WebSocket broadcast failure is swallowed — persistence still succeeds")
        void broadcastFailureSwallowed() {
            User me = user(1L, "alice");
            saveEchoesWithUuid();
            Mockito.doThrow(new RuntimeException("broker down"))
                    .when(messagingTemplate).convertAndSendToUser(anyString(), anyString(), any());

            // must not throw
            service.createNotification(me, "t", "c", "SYSTEM", "ref");

            verify(notificationRepository).save(any(Notification.class));
        }
    }

    @Nested
    @DisplayName("notifyFriends")
    class NotifyFriends {

        @Test
        @DisplayName("null actor → no-op (no repo access)")
        void nullActor() {
            service.notifyFriends(null, "t", "c", "POST", "ref", null);

            verify(friendRepository, never()).findFriendsByUser(any());
            verify(notificationRepository, never()).save(any());
        }

        @Test
        @DisplayName("friend-load failure → fail-open, nothing sent")
        void loadFailureFailOpen() {
            User actor = user(1L, "alice");
            when(friendRepository.findFriendsByUser(actor)).thenThrow(new RuntimeException("db down"));

            service.notifyFriends(actor, "t", "c", "POST", "ref", null);

            verify(notificationRepository, never()).save(any());
        }

        @Test
        @DisplayName("skips null / self / guest / deleted friends, notifies only eligible ones")
        void skipsIneligible() {
            User actor = user(1L, "alice");
            User eligible = user(2L, "bob");
            User self = user(1L, "alice");
            User guest = user(3L, "guest");
            guest.setGuest(true);
            User deleted = user(4L, "gone");
            deleted.setDeleted(true);
            when(friendRepository.findFriendsByUser(actor))
                    .thenReturn(Arrays.asList(null, eligible, self, guest, deleted));
            saveEchoesWithUuid();

            service.notifyFriends(actor, "New post", "check it", "POST", "ref-1", "http://img.png");

            ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository, times(1)).save(saved.capture());
            assertThat(saved.getValue().getUser()).isSameAs(eligible);
        }

        @Test
        @DisplayName("one failing recipient does not abort the batch")
        void perRecipientFailureIsolated() {
            User actor = user(1L, "alice");
            User bad = user(2L, "bob");
            User good = user(3L, "carol");
            when(friendRepository.findFriendsByUser(actor)).thenReturn(List.of(bad, good));
            // first save (bad) throws; second (good) succeeds
            when(notificationRepository.save(any(Notification.class)))
                    .thenThrow(new RuntimeException("save failed"))
                    .thenAnswer(inv -> {
                        Notification n = inv.getArgument(0);
                        n.setUuid(UUID.randomUUID());
                        return n;
                    });

            service.notifyFriends(actor, "t", "c", "POST", "ref", null);

            verify(notificationRepository, times(2)).save(any(Notification.class));
        }
    }

    @Nested
    @DisplayName("notifyFollowersAndFollowing")
    class NotifyFollowersAndFollowing {

        @Test
        @DisplayName("null actor → no-op")
        void nullActor() {
            service.notifyFollowersAndFollowing(null, "t", "c", "STORY", "ref", null);

            verify(userFollowRepository, never()).findAcceptedFollowers(any());
            verify(notificationRepository, never()).save(any());
        }

        @Test
        @DisplayName("follow-graph load failure → fail-open, nothing sent")
        void loadFailureFailOpen() {
            User actor = user(1L, "alice");
            when(userFollowRepository.findAcceptedFollowers(actor)).thenThrow(new RuntimeException("db down"));

            service.notifyFollowersAndFollowing(actor, "t", "c", "STORY", "ref", null);

            verify(notificationRepository, never()).save(any());
        }

        @Test
        @DisplayName("de-dups the union of followers+following, drops the actor, skips guest/deleted")
        void dedupAndFilter() {
            User actor = user(1L, "alice");
            User follower = user(2L, "bob");     // appears in both lists → once
            User following = user(3L, "carol");
            User guest = user(4L, "guest");
            guest.setGuest(true);
            User deleted = user(5L, "gone");
            deleted.setDeleted(true);
            when(userFollowRepository.findAcceptedFollowers(actor))
                    .thenReturn(Arrays.asList(follower, actor, guest, null));
            when(userFollowRepository.findAcceptedFollowing(actor))
                    .thenReturn(List.of(follower, following, deleted));
            saveEchoesWithUuid();

            service.notifyFollowersAndFollowing(actor, "New story", "watch", "STORY", "ref-1", "http://img.png");

            ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository, times(2)).save(saved.capture());
            assertThat(saved.getAllValues()).extracting(Notification::getUser)
                    .containsExactlyInAnyOrder(follower, following);
        }

        @Test
        @DisplayName("one failing recipient does not abort the batch")
        void perRecipientFailureIsolated() {
            User actor = user(1L, "alice");
            User bad = user(2L, "bob");
            User good = user(3L, "carol");
            when(userFollowRepository.findAcceptedFollowers(actor)).thenReturn(List.of(bad, good));
            when(userFollowRepository.findAcceptedFollowing(actor)).thenReturn(List.of());
            when(notificationRepository.save(any(Notification.class)))
                    .thenThrow(new RuntimeException("save failed"))
                    .thenAnswer(inv -> {
                        Notification n = inv.getArgument(0);
                        n.setUuid(UUID.randomUUID());
                        return n;
                    });

            service.notifyFollowersAndFollowing(actor, "t", "c", "STORY", "ref", null);

            verify(notificationRepository, times(2)).save(any(Notification.class));
        }
    }
}
