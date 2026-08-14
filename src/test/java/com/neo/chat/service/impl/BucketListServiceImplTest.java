package com.neo.chat.service.impl;

import com.neo.chat.domain.BucketList;
import com.neo.chat.domain.BucketListItem;
import com.neo.chat.domain.Chat;
import com.neo.chat.domain.ChatMember;
import com.neo.chat.domain.User;
import com.neo.chat.dto.response.BucketListResponse;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.ForbiddenException;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.repository.BucketListItemRepository;
import com.neo.chat.repository.BucketListRepository;
import com.neo.chat.repository.ChatMemberRepository;
import com.neo.chat.repository.ChatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link BucketListServiceImpl} — the shared per-chat bucket list
 * (feature #18).
 *
 * <p>Key invariants: (1) every mutation is IDOR-guarded — a non-member (or malformed chat id)
 * is rejected before any work; (2) the single list row is created lazily in its own tx via the
 * self-proxy, and a lost unique-constraint race re-reads the winner; (3) add appends at the end
 * (orderIndex = current count) with trimmed text, toggle flips completion metadata, remove
 * deletes; (4) each successful mutation broadcasts the fresh list to the chat topic, and a
 * broadcast failure is swallowed.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BucketListServiceImpl (unit)")
class BucketListServiceImplTest {

    @Mock
    private BucketListRepository bucketListRepository;
    @Mock
    private BucketListItemRepository bucketListItemRepository;
    @Mock
    private ChatRepository chatRepository;
    @Mock
    private ChatMemberRepository chatMemberRepository;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private ObjectProvider<BucketListServiceImpl> self;
    @Mock
    private BucketListServiceImpl selfProxy;
    @Mock
    private Chat chat;
    @Mock
    private ChatMember member;

    private BucketListServiceImpl service;

    private static final String CHAT_ID = "11111111-1111-1111-1111-111111111111";
    private static final UUID CHAT_UUID = UUID.fromString(CHAT_ID);

    private User user;

    @BeforeEach
    void setUp() {
        service = new BucketListServiceImpl(bucketListRepository, bucketListItemRepository,
                chatRepository, chatMemberRepository, messagingTemplate, self);
        user = User.builder().username("alice").name("Alice").build();
        user.setId(7L);
    }

    /**
     * Stub the IDOR guard so the caller passes as a member of the chat.
     */
    private void asMember() {
        when(chatRepository.findByUuid(CHAT_UUID)).thenReturn(Optional.of(chat));
        when(chatMemberRepository.findByChatAndUser(chat, user)).thenReturn(Optional.of(member));
    }

    private BucketList list() {
        BucketList l = BucketList.builder().chatUuid(CHAT_ID).build();
        l.setId(100L);
        l.setUuid(UUID.randomUUID());
        return l;
    }

    private BucketListItem item(UUID uuid, String text, boolean completed, int order) {
        BucketListItem i = BucketListItem.builder()
                .bucketList(null).text(text).completed(completed)
                .createdByUserId(7L).orderIndex(order).build();
        i.setUuid(uuid);
        i.setId((long) order + 1);
        return i;
    }

    // ── IDOR guard (shared across every method) ──────────────────────────────────

    @Nested
    @DisplayName("membership guard")
    class MembershipGuard {

        @Test
        @DisplayName("malformed chat id → BadRequestException TM_400")
        void malformedChatId() {
            assertThatThrownBy(() -> service.getList(user, "not-a-uuid"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_400"));
        }

        @Test
        @DisplayName("caller not a member of the chat → ForbiddenException TM_103")
        void notAMember() {
            when(chatRepository.findByUuid(CHAT_UUID)).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, user)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getList(user, CHAT_ID))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_103"));
        }

        @Test
        @DisplayName("chat does not exist → ForbiddenException TM_103")
        void chatNotFound() {
            when(chatRepository.findByUuid(CHAT_UUID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getList(user, CHAT_ID))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_103"));
        }
    }

    // ── getList ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getList")
    class GetList {

        @Test
        @DisplayName("existing list → builds response from ordered items")
        void existingList() {
            asMember();
            BucketList list = list();
            when(bucketListRepository.findByChatUuid(CHAT_ID)).thenReturn(Optional.of(list));
            BucketListItem a = item(UUID.randomUUID(), "Skydive", false, 0);
            BucketListItem b = item(UUID.randomUUID(), "Cook together", true, 1);
            when(bucketListItemRepository.findByBucketListOrderByOrderIndexAsc(list))
                    .thenReturn(List.of(a, b));

            BucketListResponse res = service.getList(user, CHAT_ID);

            assertThat(res.getChatId()).isEqualTo(CHAT_ID);
            assertThat(res.getItems()).hasSize(2);
            assertThat(res.getItems().get(0).getText()).isEqualTo("Skydive");
            assertThat(res.getItems().get(1).isCompleted()).isTrue();
            // A pure read must not broadcast.
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        @DisplayName("no list yet → lazily created via self-proxy in a new tx")
        void lazilyCreatesList() {
            asMember();
            when(bucketListRepository.findByChatUuid(CHAT_ID)).thenReturn(Optional.empty());
            BucketList created = list();
            when(self.getObject()).thenReturn(selfProxy);
            when(selfProxy.createListInNewTx(CHAT_ID)).thenReturn(created);
            when(bucketListItemRepository.findByBucketListOrderByOrderIndexAsc(created))
                    .thenReturn(List.of());

            BucketListResponse res = service.getList(user, CHAT_ID);

            assertThat(res.getChatId()).isEqualTo(CHAT_ID);
            assertThat(res.getItems()).isEmpty();
            verify(selfProxy).createListInNewTx(CHAT_ID);
        }

        @Test
        @DisplayName("concurrent create race → re-reads the committed winner")
        void raceReReadsWinner() {
            asMember();
            BucketList winner = list();
            when(bucketListRepository.findByChatUuid(CHAT_ID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(winner));
            when(self.getObject()).thenReturn(selfProxy);
            when(selfProxy.createListInNewTx(CHAT_ID))
                    .thenThrow(new DataIntegrityViolationException("dup"));
            when(bucketListItemRepository.findByBucketListOrderByOrderIndexAsc(winner))
                    .thenReturn(List.of());

            BucketListResponse res = service.getList(user, CHAT_ID);

            assertThat(res.getChatId()).isEqualTo(CHAT_ID);
        }

        @Test
        @DisplayName("create race but winner still absent on re-read → propagates the DIVE")
        void raceReReadAbsentPropagates() {
            asMember();
            when(bucketListRepository.findByChatUuid(CHAT_ID)).thenReturn(Optional.empty());
            when(self.getObject()).thenReturn(selfProxy);
            when(selfProxy.createListInNewTx(CHAT_ID))
                    .thenThrow(new DataIntegrityViolationException("dup"));

            assertThatThrownBy(() -> service.getList(user, CHAT_ID))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    // ── addItem ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("addItem")
    class AddItem {

        @Test
        @DisplayName("null text → BadRequestException TM_810")
        void nullText() {
            asMember();

            assertThatThrownBy(() -> service.addItem(user, CHAT_ID, null))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_810"));
            verify(bucketListItemRepository, never()).save(any());
        }

        @Test
        @DisplayName("blank text → BadRequestException TM_810")
        void blankText() {
            asMember();

            assertThatThrownBy(() -> service.addItem(user, CHAT_ID, "   "))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_810"));
        }

        @Test
        @DisplayName("valid text → appends trimmed item at next index and broadcasts")
        void appendsItem() {
            asMember();
            BucketList list = list();
            when(bucketListRepository.findByChatUuid(CHAT_ID)).thenReturn(Optional.of(list));
            // Two existing items → next index is 2.
            List<BucketListItem> existing = List.of(
                    item(UUID.randomUUID(), "One", false, 0),
                    item(UUID.randomUUID(), "Two", false, 1));
            when(bucketListItemRepository.findByBucketListOrderByOrderIndexAsc(list))
                    .thenReturn(existing);

            BucketListResponse res = service.addItem(user, CHAT_ID, "  Ride a hot air balloon  ");

            ArgumentCaptor<BucketListItem> cap = ArgumentCaptor.forClass(BucketListItem.class);
            verify(bucketListItemRepository).save(cap.capture());
            BucketListItem saved = cap.getValue();
            assertThat(saved.getText()).isEqualTo("Ride a hot air balloon"); // trimmed
            assertThat(saved.getOrderIndex()).isEqualTo(2);
            assertThat(saved.isCompleted()).isFalse();
            assertThat(saved.getCreatedByUserId()).isEqualTo(7L);
            assertThat(saved.getBucketList()).isSameAs(list);

            assertThat(res.getChatId()).isEqualTo(CHAT_ID);
            verify(messagingTemplate).convertAndSend(
                    eq("/topic/chat/" + CHAT_ID + "/bucket-list"), any(Object.class));
        }

        @Test
        @DisplayName("first item on a fresh list → index 0")
        void firstItemIndexZero() {
            asMember();
            BucketList list = list();
            when(bucketListRepository.findByChatUuid(CHAT_ID)).thenReturn(Optional.of(list));
            when(bucketListItemRepository.findByBucketListOrderByOrderIndexAsc(list))
                    .thenReturn(List.of());

            service.addItem(user, CHAT_ID, "First");

            ArgumentCaptor<BucketListItem> cap = ArgumentCaptor.forClass(BucketListItem.class);
            verify(bucketListItemRepository).save(cap.capture());
            assertThat(cap.getValue().getOrderIndex()).isZero();
        }

        @Test
        @DisplayName("broadcast failure is swallowed — add still returns the fresh list")
        void broadcastFailureSwallowed() {
            asMember();
            BucketList list = list();
            when(bucketListRepository.findByChatUuid(CHAT_ID)).thenReturn(Optional.of(list));
            when(bucketListItemRepository.findByBucketListOrderByOrderIndexAsc(list))
                    .thenReturn(List.of());
            doThrow(new RuntimeException("broker down"))
                    .when(messagingTemplate).convertAndSend(anyString(), any(Object.class));

            BucketListResponse res = service.addItem(user, CHAT_ID, "Anything");

            assertThat(res.getChatId()).isEqualTo(CHAT_ID);
            verify(bucketListItemRepository).save(any());
        }
    }

    // ── toggleItem ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("toggleItem")
    class ToggleItem {

        @Test
        @DisplayName("malformed item id → BadRequestException TM_811")
        void malformedItemId() {
            asMember();
            when(bucketListRepository.findByChatUuid(CHAT_ID)).thenReturn(Optional.of(list()));

            assertThatThrownBy(() -> service.toggleItem(user, CHAT_ID, "bad"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_811"));
        }

        @Test
        @DisplayName("unknown item → NotFoundException TM_812")
        void itemNotFound() {
            asMember();
            BucketList list = list();
            when(bucketListRepository.findByChatUuid(CHAT_ID)).thenReturn(Optional.of(list));
            UUID itemUuid = UUID.randomUUID();
            when(bucketListItemRepository.findByBucketListAndUuid(list, itemUuid))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.toggleItem(user, CHAT_ID, itemUuid.toString()))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_812"));
        }

        @Test
        @DisplayName("open item → marked completed with completer + timestamp, broadcasts")
        void toggleToCompleted() {
            asMember();
            BucketList list = list();
            when(bucketListRepository.findByChatUuid(CHAT_ID)).thenReturn(Optional.of(list));
            UUID itemUuid = UUID.randomUUID();
            BucketListItem it = item(itemUuid, "Go", false, 0);
            when(bucketListItemRepository.findByBucketListAndUuid(list, itemUuid))
                    .thenReturn(Optional.of(it));
            when(bucketListItemRepository.findByBucketListOrderByOrderIndexAsc(list))
                    .thenReturn(List.of(it));

            service.toggleItem(user, CHAT_ID, itemUuid.toString());

            assertThat(it.isCompleted()).isTrue();
            assertThat(it.getCompletedByUserId()).isEqualTo(7L);
            assertThat(it.getCompletedAt()).isNotNull();
            verify(bucketListItemRepository).save(it);
            verify(messagingTemplate).convertAndSend(
                    eq("/topic/chat/" + CHAT_ID + "/bucket-list"), any(Object.class));
        }

        @Test
        @DisplayName("completed item → re-opened, clears completer + timestamp")
        void toggleToReopened() {
            asMember();
            BucketList list = list();
            when(bucketListRepository.findByChatUuid(CHAT_ID)).thenReturn(Optional.of(list));
            UUID itemUuid = UUID.randomUUID();
            BucketListItem it = item(itemUuid, "Go", true, 0);
            it.setCompletedByUserId(9L);
            it.setCompletedAt(Instant.now());
            when(bucketListItemRepository.findByBucketListAndUuid(list, itemUuid))
                    .thenReturn(Optional.of(it));
            when(bucketListItemRepository.findByBucketListOrderByOrderIndexAsc(list))
                    .thenReturn(List.of(it));

            service.toggleItem(user, CHAT_ID, itemUuid.toString());

            assertThat(it.isCompleted()).isFalse();
            assertThat(it.getCompletedByUserId()).isNull();
            assertThat(it.getCompletedAt()).isNull();
            verify(bucketListItemRepository).save(it);
        }
    }

    // ── removeItem ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("removeItem")
    class RemoveItem {

        @Test
        @DisplayName("malformed item id → BadRequestException TM_811")
        void malformedItemId() {
            asMember();
            when(bucketListRepository.findByChatUuid(CHAT_ID)).thenReturn(Optional.of(list()));

            assertThatThrownBy(() -> service.removeItem(user, CHAT_ID, "nope"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_811"));
        }

        @Test
        @DisplayName("unknown item → NotFoundException TM_812")
        void itemNotFound() {
            asMember();
            BucketList list = list();
            when(bucketListRepository.findByChatUuid(CHAT_ID)).thenReturn(Optional.of(list));
            UUID itemUuid = UUID.randomUUID();
            when(bucketListItemRepository.findByBucketListAndUuid(list, itemUuid))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.removeItem(user, CHAT_ID, itemUuid.toString()))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_812"));
        }

        @Test
        @DisplayName("existing item → deleted and change broadcast")
        void deletesItem() {
            asMember();
            BucketList list = list();
            when(bucketListRepository.findByChatUuid(CHAT_ID)).thenReturn(Optional.of(list));
            UUID itemUuid = UUID.randomUUID();
            BucketListItem it = item(itemUuid, "Old goal", false, 0);
            when(bucketListItemRepository.findByBucketListAndUuid(list, itemUuid))
                    .thenReturn(Optional.of(it));
            when(bucketListItemRepository.findByBucketListOrderByOrderIndexAsc(list))
                    .thenReturn(List.of());

            BucketListResponse res = service.removeItem(user, CHAT_ID, itemUuid.toString());

            verify(bucketListItemRepository).delete(it);
            assertThat(res.getItems()).isEmpty();
            verify(messagingTemplate).convertAndSend(
                    eq("/topic/chat/" + CHAT_ID + "/bucket-list"), any(Object.class));
        }
    }

    // ── createListInNewTx ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createListInNewTx")
    class CreateListInNewTx {

        @Test
        @DisplayName("persists a fresh list row bound to the chat uuid")
        void persistsRow() {
            BucketList saved = list();
            when(bucketListRepository.save(any())).thenReturn(saved);

            BucketList result = service.createListInNewTx(CHAT_ID);

            assertThat(result).isSameAs(saved);
            ArgumentCaptor<BucketList> cap = ArgumentCaptor.forClass(BucketList.class);
            verify(bucketListRepository).save(cap.capture());
            assertThat(cap.getValue().getChatUuid()).isEqualTo(CHAT_ID);
        }
    }
}
