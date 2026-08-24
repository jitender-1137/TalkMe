package com.neo.chat.controller;

import com.neo.chat.dto.request.CreateStudyRoomRequest;
import com.neo.chat.dto.request.SetGoalRequest;
import com.neo.chat.dto.request.StuckRequest;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.StudyRoomResponse;
import com.neo.chat.dto.response.StudySessionResponse;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.StudyRoomService;
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

/**
 * Study rooms with a shared Pomodoro timer (STUDY_ROOMS). A study room is a public ROOM in
 * STUDY_POMODORO mode: ephemeral (not recorded) and driven by a server-authoritative Pomodoro that
 * flips FOCUS ⇄ BREAK and broadcasts to the room. Every route is gated by the STUDY_ROOMS feature.
 */
@RestController
@RequestMapping("/rooms/study")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class StudyRoomController {

    private final StudyRoomService studyRoomService;

    /**
     * Create a study room (public ROOM in STUDY_POMODORO mode) owned by the caller.
     */
    @PostMapping
    @PreAuthorize("@featureGuard.check('STUDY_ROOMS')")
    public ResponseEntity<ResponseDto<StudyRoomResponse>> create(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody(required = false) CreateStudyRoomRequest request) {
        CreateStudyRoomRequest req = request != null ? request : new CreateStudyRoomRequest();
        StudyRoomResponse room = studyRoomService.createStudyRoom(
                userDetails.getUser(), req.getName(), req.getSubject(),
                req.getFocusMinutes(), req.getBreakMinutes());
        return ResponseEntity.ok(SuccessResponseDto.success(room, "Study room created", "TM_906"));
    }

    /**
     * List active study rooms with a live Pomodoro snapshot and participant count.
     */
    @GetMapping
    @PreAuthorize("@featureGuard.check('STUDY_ROOMS')")
    public ResponseEntity<ResponseDto<List<StudyRoomResponse>>> list() {
        return ResponseEntity.ok(SuccessResponseDto.success(studyRoomService.listStudyRooms()));
    }

    /**
     * Join the study room's underlying chat (real membership).
     */
    @PostMapping("/{roomUuid}/join")
    @PreAuthorize("@featureGuard.check('STUDY_ROOMS')")
    public ResponseEntity<ResponseDto<StudyRoomResponse>> join(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable("roomUuid") String roomUuid) {
        StudyRoomResponse room = studyRoomService.joinStudyRoom(userDetails.getUser(), roomUuid);
        return ResponseEntity.ok(SuccessResponseDto.success(room, "Joined study room", "TM_000"));
    }

    /**
     * Mark the caller present in the room's live roster (broadcasts user_joined).
     */
    @PostMapping("/{roomUuid}/enter")
    @PreAuthorize("@featureGuard.check('STUDY_ROOMS')")
    public ResponseEntity<ResponseDto<StudyRoomResponse>> enter(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable("roomUuid") String roomUuid) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                studyRoomService.enter(userDetails.getUser(), roomUuid)));
    }

    /**
     * Remove the caller from the room's live roster (broadcasts user_left).
     */
    @PostMapping("/{roomUuid}/leave")
    @PreAuthorize("@featureGuard.check('STUDY_ROOMS')")
    public ResponseEntity<ResponseDto<Void>> leave(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable("roomUuid") String roomUuid) {
        studyRoomService.leave(userDetails.getUser(), roomUuid);
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Left study room", "TM_000"));
    }

    /**
     * Owner-only: start the Pomodoro timer for the room.
     */
    @PostMapping("/{roomUuid}/start")
    @PreAuthorize("@featureGuard.check('STUDY_ROOMS')")
    public ResponseEntity<ResponseDto<StudySessionResponse>> start(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable("roomUuid") String roomUuid) {
        StudySessionResponse session = studyRoomService.startTimer(userDetails.getUser(), roomUuid);
        return ResponseEntity.ok(SuccessResponseDto.success(session, "Pomodoro started", "TM_907"));
    }

    /**
     * Current Pomodoro session for a room (phase + remaining seconds).
     */
    @GetMapping("/{roomUuid}/session")
    @PreAuthorize("@featureGuard.check('STUDY_ROOMS')")
    public ResponseEntity<ResponseDto<StudySessionResponse>> session(
            @PathVariable("roomUuid") String roomUuid) {
        return ResponseEntity.ok(SuccessResponseDto.success(studyRoomService.getSession(roomUuid)));
    }

    /**
     * The "I'm stuck" button: broadcast a stuck event (who + optional note) to the room.
     */
    @PostMapping("/{roomUuid}/stuck")
    @PreAuthorize("@featureGuard.check('STUDY_ROOMS')")
    public ResponseEntity<ResponseDto<StudySessionResponse>> stuck(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable("roomUuid") String roomUuid,
            @RequestBody(required = false) StuckRequest request) {
        String note = request != null ? request.getNote() : null;
        StudySessionResponse session = studyRoomService.imStuck(userDetails.getUser(), roomUuid, note);
        return ResponseEntity.ok(SuccessResponseDto.success(session, "Marked as stuck", "TM_000"));
    }

    /**
     * Set the caller's shared session goal.
     */
    @PostMapping("/{roomUuid}/goal")
    @PreAuthorize("@featureGuard.check('STUDY_ROOMS')")
    public ResponseEntity<ResponseDto<StudySessionResponse>> setGoal(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable("roomUuid") String roomUuid,
            @RequestBody SetGoalRequest request) {
        StudySessionResponse session = studyRoomService.setGoal(
                userDetails.getUser(), roomUuid, request != null ? request.getText() : null);
        return ResponseEntity.ok(SuccessResponseDto.success(session, "Goal set", "TM_000"));
    }
}
