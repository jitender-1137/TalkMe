package com.neo.chat.service.impl;

import com.neo.chat.cache.MemberCountCache;
import com.neo.chat.domain.Chat;
import com.neo.chat.domain.User;
import com.neo.chat.dto.request.CreateGroupRequest;
import com.neo.chat.dto.request.TranslateRequest;
import com.neo.chat.dto.response.ChatResponse;
import com.neo.chat.dto.response.LiveRoomResponse;
import com.neo.chat.dto.response.TranslateResponse;
import com.neo.chat.enums.RoomMode;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.repository.ChatRepository;
import com.neo.chat.repository.TopicRoomChatRepository;
import com.neo.chat.service.GroupService;
import com.neo.chat.service.PresenceService;
import com.neo.chat.service.TranslationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link LiveRoomServiceImpl} — Live Rooms (Connect Wave-2). A room is
 * built via {@link GroupService#createGroup} (subtype "room") then flipped into {@link RoomMode#TOPIC}
 * or {@link RoomMode#LANGUAGE_PRACTICE}; live presence is a per-room Redis set intersected with the
 * global online set (fail-open), enter/leave broadcast to {@code /topic/chat/{uuid}/room}, and
 * language rooms add inline translation + conversation-starter prompts.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LiveRoomServiceImpl (unit)")
class LiveRoomServiceImplTest {

    @Mock
    private GroupService groupService;
    @Mock
    private ChatRepository chatRepository;
    @Mock
    private TopicRoomChatRepository topicRoomChatRepository;
    @Mock
    private TranslationService translationService;
    @Mock
    private MemberCountCache memberCountCache;
    @Mock
    private PresenceService presenceService;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private SetOperations<String, String> setOps;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private LiveRoomServiceImpl service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new LiveRoomServiceImpl(groupService, chatRepository, topicRoomChatRepository,
                translationService, memberCountCache, presenceService, redis, messagingTemplate);
        lenient().when(redis.opsForSet()).thenReturn(setOps);
        user = new User();
        user.setId(1L);
        user.setUuid(UUID.randomUUID());
        user.setUsername("alice");
    }

    private Chat chat(UUID uuid, RoomMode mode, String category) {
        Chat c = new Chat();
        c.setUuid(uuid);
        c.setName("Room");
        c.setDescription("desc");
        c.setCategory(category);
        c.setRoomMode(mode);
        c.setCreatedAt(Instant.parse("2026-08-01T00:00:00Z"));
        return c;
    }

    // ── createTopicRoom ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createTopicRoom")
    class CreateTopicRoom {

        @Test
        @DisplayName("builds a PUBLIC room, flips to TOPIC, saves, maps response")
        void createsTopicRoom() {
            UUID uuid = UUID.randomUUID();
            when(groupService.createGroup(any(), eq(user)))
                    .thenReturn(ChatResponse.builder().id(uuid.toString()).build());
            Chat chat = chat(uuid, RoomMode.STANDARD, "gaming");
            when(chatRepository.findByUuid(uuid)).thenReturn(Optional.of(chat));
            when(memberCountCache.get(chat)).thenReturn(7);

            LiveRoomResponse resp = service.createTopicRoom(user, "  Retro Nights  ", "  gaming  ", List.of("GAMING"));

            assertThat(chat.getRoomMode()).isEqualTo(RoomMode.TOPIC);
            verify(chatRepository).save(chat);

            ArgumentCaptor<CreateGroupRequest> req = ArgumentCaptor.forClass(CreateGroupRequest.class);
            verify(groupService).createGroup(req.capture(), eq(user));
            assertThat(req.getValue().getName()).isEqualTo("Retro Nights");
            assertThat(req.getValue().getSubtype()).isEqualTo("room");
            assertThat(req.getValue().getVisibility()).isEqualTo("PUBLIC");
            assertThat(req.getValue().getCategory()).isEqualTo("gaming");
            assertThat(req.getValue().getTags()).containsExactly("GAMING");

            assertThat(resp.getId()).isEqualTo(uuid.toString());
            assertThat(resp.getRoomMode()).isEqualTo("TOPIC");
            assertThat(resp.getMemberCount()).isEqualTo(7);
        }

        @Test
        @DisplayName("blank category falls back to 'topic'")
        void defaultsCategory() {
            UUID uuid = UUID.randomUUID();
            when(groupService.createGroup(any(), eq(user)))
                    .thenReturn(ChatResponse.builder().id(uuid.toString()).build());
            when(chatRepository.findByUuid(uuid)).thenReturn(Optional.of(chat(uuid, RoomMode.STANDARD, "x")));

            service.createTopicRoom(user, "Room", "   ", null);

            ArgumentCaptor<CreateGroupRequest> req = ArgumentCaptor.forClass(CreateGroupRequest.class);
            verify(groupService).createGroup(req.capture(), eq(user));
            assertThat(req.getValue().getCategory()).isEqualTo("topic");
        }

        @Test
        @DisplayName("blank name → BadRequestException TM_928, no room created")
        void blankNameRejected() {
            assertThatThrownBy(() -> service.createTopicRoom(user, "   ", "gaming", null))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_928"));
            verify(groupService, never()).createGroup(any(), any());
        }

        @Test
        @DisplayName("created room cannot be reloaded → NotFoundException TM_925, no save")
        void reloadMiss() {
            UUID uuid = UUID.randomUUID();
            when(groupService.createGroup(any(), eq(user)))
                    .thenReturn(ChatResponse.builder().id(uuid.toString()).build());
            when(chatRepository.findByUuid(uuid)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createTopicRoom(user, "Room", "gaming", null))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_925"));
            verify(chatRepository, never()).save(any());
        }
    }

    // ── createLanguageRoom ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("createLanguageRoom")
    class CreateLanguageRoom {

        @Test
        @DisplayName("encodes the language pair into the category and flips to LANGUAGE_PRACTICE")
        void createsLanguageRoom() {
            UUID uuid = UUID.randomUUID();
            when(groupService.createGroup(any(), eq(user)))
                    .thenReturn(ChatResponse.builder().id(uuid.toString()).build());
            Chat chat = chat(uuid, RoomMode.STANDARD, "lang:HI>EN");
            when(chatRepository.findByUuid(uuid)).thenReturn(Optional.of(chat));

            LiveRoomResponse resp = service.createLanguageRoom(user, null, " EN ", " HI ");

            assertThat(chat.getRoomMode()).isEqualTo(RoomMode.LANGUAGE_PRACTICE);
            verify(chatRepository).save(chat);

            ArgumentCaptor<CreateGroupRequest> req = ArgumentCaptor.forClass(CreateGroupRequest.class);
            verify(groupService).createGroup(req.capture(), eq(user));
            assertThat(req.getValue().getSubtype()).isEqualTo("room");
            assertThat(req.getValue().getVisibility()).isEqualTo("PUBLIC");
            assertThat(req.getValue().getCategory()).isEqualTo("lang:HI>EN");
            // default name derived from the language pair
            assertThat(req.getValue().getName()).contains("EN").contains("HI");

            assertThat(resp.getRoomMode()).isEqualTo("LANGUAGE_PRACTICE");
        }

        @Test
        @DisplayName("custom name is used verbatim")
        void usesCustomName() {
            UUID uuid = UUID.randomUUID();
            when(groupService.createGroup(any(), eq(user)))
                    .thenReturn(ChatResponse.builder().id(uuid.toString()).build());
            when(chatRepository.findByUuid(uuid)).thenReturn(Optional.of(chat(uuid, RoomMode.STANDARD, "lang:HI>EN")));

            service.createLanguageRoom(user, "  Hindi ↔ English  ", "EN", "HI");

            ArgumentCaptor<CreateGroupRequest> req = ArgumentCaptor.forClass(CreateGroupRequest.class);
            verify(groupService).createGroup(req.capture(), eq(user));
            assertThat(req.getValue().getName()).isEqualTo("Hindi ↔ English");
        }

        @Test
        @DisplayName("missing target/native language → BadRequestException TM_929, no room created")
        void missingLanguagesRejected() {
            assertThatThrownBy(() -> service.createLanguageRoom(user, "x", "EN", "  "))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_929"));
            assertThatThrownBy(() -> service.createLanguageRoom(user, "x", null, "HI"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_929"));
            verify(groupService, never()).createGroup(any(), any());
        }
    }

    // ── lists ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("listTopicRooms / listLanguageRooms")
    class Lists {

        @Test
        @DisplayName("topic list queries TOPIC mode and maps cards with live counts")
        void listsTopic() {
            Chat a = chat(UUID.randomUUID(), RoomMode.TOPIC, "coffee");
            when(topicRoomChatRepository.findActiveByRoomModeIn(List.of(RoomMode.TOPIC)))
                    .thenReturn(List.of(a));
            when(presenceService.getOnlineUsernames()).thenReturn(Set.of("alice", "bob"));
            when(setOps.members(anyString())).thenReturn(Set.of("alice", "carol"));
            when(memberCountCache.get(a)).thenReturn(3);

            List<LiveRoomResponse> out = service.listTopicRooms();

            assertThat(out).hasSize(1);
            assertThat(out.get(0).getRoomMode()).isEqualTo("TOPIC");
            assertThat(out.get(0).getMemberCount()).isEqualTo(3);
            assertThat(out.get(0).getLiveCount()).isEqualTo(1); // only alice is online ∩ present
        }

        @Test
        @DisplayName("language list queries LANGUAGE_PRACTICE mode")
        void listsLanguage() {
            Chat a = chat(UUID.randomUUID(), RoomMode.LANGUAGE_PRACTICE, "lang:HI>EN");
            when(topicRoomChatRepository.findActiveByRoomModeIn(List.of(RoomMode.LANGUAGE_PRACTICE)))
                    .thenReturn(List.of(a));

            List<LiveRoomResponse> out = service.listLanguageRooms();

            assertThat(out).hasSize(1);
            assertThat(out.get(0).getRoomMode()).isEqualTo("LANGUAGE_PRACTICE");
            assertThat(out.get(0).getCategory()).isEqualTo("lang:HI>EN");
        }

        @Test
        @DisplayName("null roomMode falls back to STANDARD in the mapper")
        void nullRoomModeFallsBack() {
            Chat a = chat(UUID.randomUUID(), null, "coffee");
            when(topicRoomChatRepository.findActiveByRoomModeIn(List.of(RoomMode.TOPIC)))
                    .thenReturn(List.of(a));

            assertThat(service.listTopicRooms().get(0).getRoomMode()).isEqualTo("STANDARD");
        }
    }

    // ── joinRoom ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("joinRoom")
    class JoinRoom {

        @Test
        @DisplayName("delegates to GroupService.joinChat then returns the room card")
        void joins() {
            UUID uuid = UUID.randomUUID();
            Chat chat = chat(uuid, RoomMode.TOPIC, "coffee");
            when(chatRepository.findByUuid(uuid)).thenReturn(Optional.of(chat));

            LiveRoomResponse resp = service.joinRoom(user, uuid.toString());

            verify(groupService).joinChat(uuid.toString(), user);
            assertThat(resp.getId()).isEqualTo(uuid.toString());
        }
    }

    // ── enterRoom / leaveRoom ────────────────────────────────────────────────

    @Nested
    @DisplayName("enterRoom / leaveRoom")
    class Presence {

        @Test
        @DisplayName("enter adds to the Redis set, broadcasts user_joined, returns card")
        void enters() {
            UUID uuid = UUID.randomUUID();
            Chat chat = chat(uuid, RoomMode.TOPIC, "coffee");
            when(chatRepository.findByUuid(uuid)).thenReturn(Optional.of(chat));

            LiveRoomResponse resp = service.enterRoom(user, uuid.toString());

            verify(setOps).add("liveroom:presence:" + uuid, "alice");
            verify(messagingTemplate).convertAndSend(eq("/topic/chat/" + uuid + "/room"), any(Object.class));
            assertThat(resp.getId()).isEqualTo(uuid.toString());
        }

        @Test
        @DisplayName("leave removes from the Redis set and broadcasts user_left")
        void leaves() {
            UUID uuid = UUID.randomUUID();
            when(chatRepository.findByUuid(uuid)).thenReturn(Optional.of(chat(uuid, RoomMode.TOPIC, "coffee")));

            service.leaveRoom(user, uuid.toString());

            verify(setOps).remove("liveroom:presence:" + uuid, "alice");
            verify(messagingTemplate).convertAndSend(eq("/topic/chat/" + uuid + "/room"), any(Object.class));
        }

        @Test
        @DisplayName("enter on an unknown room → NotFoundException TM_926")
        void enterUnknownRoom() {
            UUID uuid = UUID.randomUUID();
            when(chatRepository.findByUuid(uuid)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.enterRoom(user, uuid.toString()))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_926"));
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        @DisplayName("enter with a malformed uuid → NotFoundException TM_926")
        void enterMalformedUuid() {
            assertThatThrownBy(() -> service.enterRoom(user, "not-a-uuid"))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_926"));
        }
    }

    // ── translateInRoom ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("translateInRoom")
    class Translate {

        @Test
        @DisplayName("language room → delegates to TranslationService.translate")
        void translatesInLanguageRoom() {
            UUID uuid = UUID.randomUUID();
            when(chatRepository.findByUuid(uuid))
                    .thenReturn(Optional.of(chat(uuid, RoomMode.LANGUAGE_PRACTICE, "lang:HI>EN")));
            TranslateRequest req = TranslateRequest.builder().text("hola").target("en").build();
            TranslateResponse expected = TranslateResponse.builder().translatedText("hello").build();
            when(translationService.translate(user, req)).thenReturn(expected);

            TranslateResponse resp = service.translateInRoom(user, uuid.toString(), req);

            assertThat(resp).isSameAs(expected);
            verify(translationService).translate(user, req);
        }

        @Test
        @DisplayName("non-language room → BadRequestException TM_927, no translation")
        void rejectsNonLanguageRoom() {
            UUID uuid = UUID.randomUUID();
            when(chatRepository.findByUuid(uuid))
                    .thenReturn(Optional.of(chat(uuid, RoomMode.TOPIC, "coffee")));
            TranslateRequest req = TranslateRequest.builder().text("hi").target("en").build();

            assertThatThrownBy(() -> service.translateInRoom(user, uuid.toString(), req))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_927"));
            verify(translationService, never()).translate(any(), any());
        }

        @Test
        @DisplayName("unknown room → NotFoundException TM_926")
        void unknownRoom() {
            UUID uuid = UUID.randomUUID();
            when(chatRepository.findByUuid(uuid)).thenReturn(Optional.empty());
            TranslateRequest req = TranslateRequest.builder().text("hi").target("en").build();

            assertThatThrownBy(() -> service.translateInRoom(user, uuid.toString(), req))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_926"));
        }
    }

    // ── suggestTopics ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("suggestTopics")
    class SuggestTopics {

        @Test
        @DisplayName("returns non-empty conversation starters for an existing room")
        void returnsStarters() {
            UUID uuid = UUID.randomUUID();
            when(chatRepository.findByUuid(uuid)).thenReturn(Optional.of(chat(uuid, RoomMode.TOPIC, "coffee")));

            List<String> topics = service.suggestTopics(uuid.toString());

            assertThat(topics).isNotEmpty();
            assertThat(topics).doesNotContainNull();
        }

        @Test
        @DisplayName("unknown room → NotFoundException TM_926")
        void unknownRoom() {
            UUID uuid = UUID.randomUUID();
            when(chatRepository.findByUuid(uuid)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.suggestTopics(uuid.toString()))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_926"));
        }
    }
}
