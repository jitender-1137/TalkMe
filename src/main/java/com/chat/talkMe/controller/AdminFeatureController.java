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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    /**
     * Apply a feature grant (decision/scope/cohort/expiry/note) to a target user.
     *
     * @param uuid    the target user's UUID
     * @param request the validated grant request (feature key wire name + grant parameters)
     * @return an empty success envelope confirming the grant was applied
     * @throws com.chat.talkMe.exception.NotFoundException   if no user matches the UUID
     * @throws com.chat.talkMe.exception.BadRequestException if the feature key is unknown
     */
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

    /**
     * Remove an existing feature grant from a target user.
     *
     * @param uuid the target user's UUID
     * @param key  the feature key wire name to revoke
     * @return an empty success envelope confirming the grant was removed
     * @throws com.chat.talkMe.exception.NotFoundException   if no user matches the UUID
     * @throws com.chat.talkMe.exception.BadRequestException if the feature key is unknown
     */
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
