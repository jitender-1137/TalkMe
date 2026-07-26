package com.chat.talkMe.controller;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.FeatureGrantRequest;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.enums.FeatureKey;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.FeatureAccessService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * SuperAdmin feature-grant management. Served at {@code /api/v1/admin/features}
 * (covered by the {@code /api/v1/admin/**} security rule + the class-level guard).
 */
@RestController
@RequestMapping("/admin/features")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminFeatureController {

    private final FeatureAccessService featureAccessService;
    private final UserRepository userRepository;

    @PostMapping("/users/{uuid}")
    public ResponseEntity<ResponseDto<Void>> grant(
            @PathVariable("uuid") String uuid,
            @Valid @RequestBody FeatureGrantRequest request) {
        User target = findUser(uuid);
        FeatureKey key = FeatureKey.fromWire(request.getKey());
        if (key == null) {
            throw new BadRequestException("Unknown feature: " + request.getKey(), "TM_002");
        }
        featureAccessService.grant(target, key, request.getDecision(), request.getScope(),
                request.getCohort(), request.getExpiresAt(), request.getNote());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Feature grant applied", "TM_000"));
    }

    @DeleteMapping("/users/{uuid}/{key}")
    public ResponseEntity<ResponseDto<Void>> revoke(
            @PathVariable("uuid") String uuid,
            @PathVariable("key") String key) {
        User target = findUser(uuid);
        FeatureKey fk = FeatureKey.fromWire(key);
        if (fk == null) {
            throw new BadRequestException("Unknown feature: " + key, "TM_002");
        }
        featureAccessService.revoke(target, fk);
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Feature grant removed", "TM_000"));
    }

    private User findUser(String uuid) {
        return userRepository.findByUuid(UUID.fromString(uuid))
                .orElseThrow(() -> new NotFoundException("User not found", "TM_024"));
    }
}
