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
import com.chat.talkMe.service.SleepRoomService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Calm, wind-down "sleep companion" rooms (features #26/#27). A sleep room is an ordinary public
 * ROOM flipped into {@link RoomMode#SLEEP_COMPANION} so its messages are never recorded (enforced
 * server-side in the message send path). We reuse {@link GroupService#createGroup} to build the
 * room, then set the mode on the loaded Chat — never editing the shared Chat definition.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SleepRoomServiceImpl implements SleepRoomService {

    private static final String DEFAULT_NAME = "Sleep together";

    private final GroupService groupService;
    private final ChatRepository chatRepository;
    private final SleepRoomChatRepository sleepRoomChatRepository;

    @Override
    @Transactional
    public SleepRoomResponse createSleepRoom(User user, String name) {
        String roomName = (name != null && !name.isBlank()) ? name.trim() : DEFAULT_NAME;

        CreateGroupRequest req = new CreateGroupRequest();
        req.setName(roomName);
        req.setDescription("A calm space to wind down together. Nothing said here is recorded.");
        req.setSubtype("room");
        req.setVisibility("PUBLIC");
        req.setCategory("sleep");
        ChatResponse room = groupService.createGroup(req, user);

        Chat chat = chatRepository.findByUuid(UUID.fromString(room.getId()))
                .orElseThrow(() -> new NotFoundException("Room not found", "TM_998"));
        chat.setRoomMode(RoomMode.SLEEP_COMPANION);
        chatRepository.save(chat);

        return toResponse(chat);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SleepRoomResponse> listSleepRooms() {
        List<SleepRoomResponse> out = new ArrayList<>();
        for (Chat chat : sleepRoomChatRepository.findActiveByRoomMode(RoomMode.SLEEP_COMPANION)) {
            out.add(toResponse(chat));
        }
        return out;
    }

    private SleepRoomResponse toResponse(Chat chat) {
        return SleepRoomResponse.builder()
                .id(chat.getUuid().toString())
                .name(chat.getName())
                .description(chat.getDescription())
                .category(chat.getCategory())
                .roomMode(chat.getRoomMode() != null ? chat.getRoomMode().name() : RoomMode.STANDARD.name())
                .createdAt(chat.getCreatedAt())
                .build();
    }
}
