package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.CreateGroupRequest;
import com.chat.talkMe.dto.response.ChatResponse;
import com.chat.talkMe.dto.response.SleepRoomResponse;
import com.chat.talkMe.enums.RoomMode;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.repository.ChatRepository;
import com.chat.talkMe.repository.SleepRoomChatRepository;
import com.chat.talkMe.service.GroupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
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
 * Pure Mockito unit test for {@link SleepRoomServiceImpl} — creates a public ROOM via
 * {@link GroupService#createGroup} then flips it into {@link RoomMode#SLEEP_COMPANION}, and
 * lists active sleep rooms. Covers the default-vs-custom name branch, the created-room lookup
 * miss, the CreateGroupRequest shape passed to GroupService, and the roomMode fallback in the
 * response mapper.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SleepRoomServiceImpl (unit)")
class SleepRoomServiceImplTest {

    @Mock
    private GroupService groupService;
    @Mock
    private ChatRepository chatRepository;
    @Mock
    private SleepRoomChatRepository sleepRoomChatRepository;

    private SleepRoomServiceImpl service;

    private User user;

    @BeforeEach
    void setUp() {
        service = new SleepRoomServiceImpl(groupService, chatRepository, sleepRoomChatRepository);
        user = new User();
        user.setId(1L);
        user.setUuid(UUID.randomUUID());
    }

    private Chat chat(UUID uuid, RoomMode mode) {
        Chat c = new Chat();
        c.setUuid(uuid);
        c.setName("Sleep together");
        c.setDescription("desc");
        c.setCategory("sleep");
        c.setRoomMode(mode);
        c.setCreatedAt(Instant.parse("2026-07-30T00:00:00Z"));
        return c;
    }

    @Nested
    @DisplayName("createSleepRoom")
    class CreateSleepRoom {

        @Test
        @DisplayName("custom name → room created, flipped to SLEEP_COMPANION, response returned")
        void createsWithCustomName() {
            UUID uuid = UUID.randomUUID();
            when(groupService.createGroup(any(), eq(user)))
                    .thenReturn(ChatResponse.builder().id(uuid.toString()).build());
            Chat chat = chat(uuid, RoomMode.STANDARD);
            when(chatRepository.findByUuid(uuid)).thenReturn(Optional.of(chat));

            SleepRoomResponse response = service.createSleepRoom(user, "  My cozy room  ");

            // The loaded chat is flipped to SLEEP_COMPANION and persisted.
            assertThat(chat.getRoomMode()).isEqualTo(RoomMode.SLEEP_COMPANION);
            verify(chatRepository).save(chat);

            // GroupService is asked to build a PUBLIC "room" in the "sleep" category with a trimmed name.
            ArgumentCaptor<CreateGroupRequest> req = ArgumentCaptor.forClass(CreateGroupRequest.class);
            verify(groupService).createGroup(req.capture(), eq(user));
            assertThat(req.getValue().getName()).isEqualTo("My cozy room");
            assertThat(req.getValue().getSubtype()).isEqualTo("room");
            assertThat(req.getValue().getVisibility()).isEqualTo("PUBLIC");
            assertThat(req.getValue().getCategory()).isEqualTo("sleep");

            assertThat(response.getId()).isEqualTo(uuid.toString());
            assertThat(response.getRoomMode()).isEqualTo("SLEEP_COMPANION");
            assertThat(response.getCategory()).isEqualTo("sleep");
        }

        @Test
        @DisplayName("null name → falls back to the default room name")
        void defaultsNullName() {
            UUID uuid = UUID.randomUUID();
            when(groupService.createGroup(any(), eq(user)))
                    .thenReturn(ChatResponse.builder().id(uuid.toString()).build());
            when(chatRepository.findByUuid(uuid)).thenReturn(Optional.of(chat(uuid, RoomMode.STANDARD)));

            service.createSleepRoom(user, null);

            ArgumentCaptor<CreateGroupRequest> req = ArgumentCaptor.forClass(CreateGroupRequest.class);
            verify(groupService).createGroup(req.capture(), eq(user));
            assertThat(req.getValue().getName()).isEqualTo("Sleep together");
        }

        @Test
        @DisplayName("blank name → falls back to the default room name")
        void defaultsBlankName() {
            UUID uuid = UUID.randomUUID();
            when(groupService.createGroup(any(), eq(user)))
                    .thenReturn(ChatResponse.builder().id(uuid.toString()).build());
            when(chatRepository.findByUuid(uuid)).thenReturn(Optional.of(chat(uuid, RoomMode.STANDARD)));

            service.createSleepRoom(user, "   ");

            ArgumentCaptor<CreateGroupRequest> req = ArgumentCaptor.forClass(CreateGroupRequest.class);
            verify(groupService).createGroup(req.capture(), eq(user));
            assertThat(req.getValue().getName()).isEqualTo("Sleep together");
        }

        @Test
        @DisplayName("created room cannot be re-loaded → NotFoundException TM_998, no save")
        void roomNotFound() {
            UUID uuid = UUID.randomUUID();
            when(groupService.createGroup(any(), eq(user)))
                    .thenReturn(ChatResponse.builder().id(uuid.toString()).build());
            when(chatRepository.findByUuid(uuid)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createSleepRoom(user, "x"))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_998"));
            verify(chatRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("listSleepRooms")
    class ListSleepRooms {

        @Test
        @DisplayName("maps each active sleep room; null roomMode falls back to STANDARD")
        void mapsActiveRooms() {
            Chat a = chat(UUID.randomUUID(), RoomMode.SLEEP_COMPANION);
            Chat b = chat(UUID.randomUUID(), null); // roomMode null → mapper falls back
            when(sleepRoomChatRepository.findActiveByRoomMode(RoomMode.SLEEP_COMPANION))
                    .thenReturn(List.of(a, b));

            List<SleepRoomResponse> rooms = service.listSleepRooms();

            assertThat(rooms).hasSize(2);
            assertThat(rooms.get(0).getRoomMode()).isEqualTo("SLEEP_COMPANION");
            assertThat(rooms.get(0).getId()).isEqualTo(a.getUuid().toString());
            assertThat(rooms.get(1).getRoomMode()).isEqualTo("STANDARD");
        }

        @Test
        @DisplayName("no active sleep rooms → empty list")
        void empty() {
            when(sleepRoomChatRepository.findActiveByRoomMode(RoomMode.SLEEP_COMPANION))
                    .thenReturn(List.of());

            assertThat(service.listSleepRooms()).isEmpty();
        }
    }
}
