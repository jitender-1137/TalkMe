package com.chat.talkMe.controller;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.domain.UserPresence;
import com.chat.talkMe.dto.response.PresenceResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.enums.PresenceStatus;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.PresenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/presence")
@RequiredArgsConstructor
public class PresenceController {

    private final PresenceService presenceService;
    private final UserRepository userRepository;

    @PutMapping("/status")
    public ResponseEntity<ResponseDto<Void>> setStatus(
            @RequestParam("status") String statusStr,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        
        PresenceStatus status;
        try {
            status = PresenceStatus.valueOf(statusStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new com.chat.talkMe.exception.BadRequestException("Invalid presence status value. Allowed values are ONLINE, OFFLINE, AWAY, IDLE, INVISIBLE.", "TM_PRESENCE_INVALID_STATUS");
        }

        presenceService.setStatus(userDetails.getUser(), status);
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Presence status updated successfully", "TM_PRESENCE_001"));
    }

    @PutMapping("/ghost")
    public ResponseEntity<ResponseDto<Void>> toggleGhostMode(
            @RequestParam("enabled") boolean enabled,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        presenceService.toggleGhostMode(userDetails.getUser(), enabled);
        String msg = enabled ? "Ghost Mode enabled" : "Ghost Mode disabled";
        return ResponseEntity.ok(SuccessResponseDto.success(null, msg, "TM_PRESENCE_002"));
    }

    @PutMapping("/invisible")
    public ResponseEntity<ResponseDto<Void>> toggleInvisibleMode(
            @RequestParam("enabled") boolean enabled,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        presenceService.toggleInvisibleMode(userDetails.getUser(), enabled);
        String msg = enabled ? "Invisible Mode enabled" : "Invisible Mode disabled";
        return ResponseEntity.ok(SuccessResponseDto.success(null, msg, "TM_PRESENCE_003"));
    }

    @PutMapping("/hide-last-seen")
    public ResponseEntity<ResponseDto<Void>> toggleHideLastSeen(
            @RequestParam("enabled") boolean enabled,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        presenceService.toggleHideLastSeen(userDetails.getUser(), enabled);
        String msg = enabled ? "Hide Last Seen enabled" : "Hide Last Seen disabled";
        return ResponseEntity.ok(SuccessResponseDto.success(null, msg, "TM_PRESENCE_005"));
    }

    @DeleteMapping("/reset")
    public ResponseEntity<ResponseDto<Void>> resetPresence(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        presenceService.resetPresence(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Presence properties reset successfully", "TM_PRESENCE_004"));
    }

    @GetMapping("/{username}")
    public ResponseEntity<ResponseDto<PresenceResponse>> getPresence(
            @PathVariable("username") String username,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        User currentUser = userDetails.getUser();
        User targetUser = userRepository.findByUsername(username)
                .orElseThrow(() -> new NotFoundException("User not found with username: " + username, "TM_USER_NOT_FOUND"));

        UserPresence targetPresence = presenceService.getUserPresence(targetUser);

        PresenceResponse.PresenceResponseBuilder builder = PresenceResponse.builder()
                .username(targetUser.getUsername());

        // Privacy filters:
        if (currentUser.getId().equals(targetUser.getId())) {
            // Owner sees their TRUE live status + durable settings. Status/last-seen come
            // from Redis (the DB values are only written on OFFLINE and are otherwise stale);
            // flags come from the durable DB record. This is what the client uses to hydrate
            // the privacy toggles correctly on load (incl. a fresh browser/device).
            java.time.Instant lastSeen = presenceService.getLastSeen(targetUser);
            builder.status(presenceService.getRawStatus(targetUser).name())
                    .lastSeenAt(lastSeen != null ? lastSeen.toString() : null)
                    .ghostModeEnabled(targetPresence.isGhostModeEnabled())
                    .invisibleModeEnabled(targetPresence.isInvisibleModeEnabled())
                    .hideLastSeenEnabled(targetPresence.isHideLastSeenEnabled());
        } else {
            // Other users see apparent (Invisible-masked) status + apparent last-seen
            // (nulled by Invisible / Hide-last-seen) — the single privacy rule lives in
            // the service so every consumer is consistent.
            PresenceStatus apparentStatus = presenceService.getStatus(targetUser);
            builder.status(apparentStatus.name());
            java.time.Instant lastSeen = presenceService.getApparentLastSeen(targetUser);
            builder.lastSeenAt(lastSeen != null ? lastSeen.toString() : null);

            // Hide configuration flags for other users
            builder.ghostModeEnabled(false)
                    .invisibleModeEnabled(false)
                    .hideLastSeenEnabled(false);
        }

        return ResponseEntity.ok(SuccessResponseDto.success(builder.build()));
    }
}
