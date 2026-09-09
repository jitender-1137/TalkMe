package com.neo.chat.controller;

import com.neo.chat.dto.request.CreateLanguageRoomRequest;
import com.neo.chat.dto.request.CreateTopicRoomRequest;
import com.neo.chat.dto.request.TranslateRequest;
import com.neo.chat.dto.response.LiveRoomResponse;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.dto.response.TranslateResponse;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.LiveRoomService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;

/**
 * Live Rooms (Connect Wave-2): "third place" topic rooms and language-practice rooms. Topic
 * routes are gated by the TOPIC_ROOMS feature; language-specific routes (create/list language
 * rooms, in-room translate) by LANGUAGE_ROOMS. A room is a public ROOM flipped into TOPIC or
 * LANGUAGE_PRACTICE mode; enter/leave maintain a live Redis roster broadcast over WebSocket.
 */
@RestController
@Tag(name = "Live Room", description = "Live Rooms (Connect Wave-2): \"third place\" topic rooms and language-practice rooms")
@RequestMapping("/rooms/live")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class LiveRoomController {

    private final LiveRoomService liveRoomService;

    /**
     * Create a public "third place" TOPIC room owned by the caller.
     */
    @Operation(summary = "Create a public \"third place\" TOPIC room owned by the caller")
    @PostMapping(value = "/topic", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@featureGuard.check('TOPIC_ROOMS')")
    public ResponseEntity<ResponseDto<LiveRoomResponse>> createTopic(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody CreateTopicRoomRequest request) {
        LiveRoomResponse room = liveRoomService.createTopicRoom(
                userDetails.getUser(), request.getName(), request.getCategory(), request.getTags());
        return ResponseEntity.ok(SuccessResponseDto.success(room, "Topic room created", "TM_000"));
    }

    /**
     * Create a public LANGUAGE_PRACTICE room owned by the caller.
     */
    @Operation(summary = "Create a public LANGUAGE_PRACTICE room owned by the caller")
    @PostMapping(value = "/language", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@featureGuard.check('LANGUAGE_ROOMS')")
    public ResponseEntity<ResponseDto<LiveRoomResponse>> createLanguage(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody CreateLanguageRoomRequest request) {
        LiveRoomResponse room = liveRoomService.createLanguageRoom(
                userDetails.getUser(), request.getName(),
                request.getTargetLanguage(), request.getNativeLanguage());
        return ResponseEntity.ok(SuccessResponseDto.success(room, "Language room created", "TM_000"));
    }

    /**
     * List active TOPIC rooms.
     */
    @Operation(summary = "List active TOPIC rooms")
    @GetMapping("/topic")
    @PreAuthorize("@featureGuard.check('TOPIC_ROOMS')")
    public ResponseEntity<ResponseDto<List<LiveRoomResponse>>> listTopic() {
        return ResponseEntity.ok(SuccessResponseDto.success(liveRoomService.listTopicRooms()));
    }

    /**
     * List active LANGUAGE_PRACTICE rooms.
     */
    @Operation(summary = "List active LANGUAGE_PRACTICE rooms")
    @GetMapping("/language")
    @PreAuthorize("@featureGuard.check('LANGUAGE_ROOMS')")
    public ResponseEntity<ResponseDto<List<LiveRoomResponse>>> listLanguage() {
        return ResponseEntity.ok(SuccessResponseDto.success(liveRoomService.listLanguageRooms()));
    }

    /**
     * Join a live room (open-join). Returns the room card.
     */
    @Operation(summary = "Join a live room (open-join)")
    @PostMapping("/{uuid}/join")
    @PreAuthorize("@featureGuard.check('TOPIC_ROOMS')")
    public ResponseEntity<ResponseDto<LiveRoomResponse>> join(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable("uuid") String uuid) {
        LiveRoomResponse room = liveRoomService.joinRoom(userDetails.getUser(), uuid);
        return ResponseEntity.ok(SuccessResponseDto.success(room, "Joined room", "TM_000"));
    }

    /**
     * Mark the caller present in a room's live roster; returns the refreshed room card.
     */
    @Operation(summary = "Mark the caller present in a room's live roster; returns the refreshed room card")
    @PostMapping("/{uuid}/enter")
    @PreAuthorize("@featureGuard.check('TOPIC_ROOMS')")
    public ResponseEntity<ResponseDto<LiveRoomResponse>> enter(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable("uuid") String uuid) {
        LiveRoomResponse room = liveRoomService.enterRoom(userDetails.getUser(), uuid);
        return ResponseEntity.ok(SuccessResponseDto.success(room, "Entered room", "TM_000"));
    }

    /**
     * Mark the caller absent from a room's live roster.
     */
    @Operation(summary = "Mark the caller absent from a room's live roster")
    @PostMapping("/{uuid}/leave")
    @PreAuthorize("@featureGuard.check('TOPIC_ROOMS')")
    public ResponseEntity<ResponseDto<Void>> leave(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable("uuid") String uuid) {
        liveRoomService.leaveRoom(userDetails.getUser(), uuid);
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Left room", "TM_000"));
    }

    /**
     * Translate plaintext inside a language-practice room.
     */
    @Operation(summary = "Translate plaintext inside a language-practice room")
    @PostMapping(value = "/{uuid}/translate", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@featureGuard.check('LANGUAGE_ROOMS')")
    public ResponseEntity<ResponseDto<TranslateResponse>> translate(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable("uuid") String uuid,
            @Valid @RequestBody TranslateRequest request) {
        TranslateResponse response = liveRoomService.translateInRoom(userDetails.getUser(), uuid, request);
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Translated", "TM_000"));
    }

    /**
     * A few conversation-starter prompts for a room.
     */
    @Operation(summary = "A few conversation-starter prompts for a room")
    @GetMapping("/{uuid}/topics")
    @PreAuthorize("@featureGuard.check('TOPIC_ROOMS')")
    public ResponseEntity<ResponseDto<List<String>>> topics(
            @PathVariable("uuid") String uuid) {
        return ResponseEntity.ok(SuccessResponseDto.success(liveRoomService.suggestTopics(uuid)));
    }
}
