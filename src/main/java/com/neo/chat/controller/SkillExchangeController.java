package com.neo.chat.controller;

import com.neo.chat.dto.request.UpdateSkillsRequest;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SkillMatchResponse;
import com.neo.chat.dto.response.SkillProfileResponse;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.SkillExchangeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Skill / Study Exchange (feature SKILL_EXCHANGE). Users list skills they can teach and skills
 * they want to learn, then get matched to complementary peers. Gated by the SKILL_EXCHANGE
 * entitlement per method.
 */
@RestController
@RequestMapping("/skills")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
@Tag(name = "Skill Exchange", description = "Skills to teach and learn, matched to complementary peers")
public class SkillExchangeController {

    private final SkillExchangeService skillExchangeService;

    /**
     * The caller's own skill profile (offers + wants).
     */
    @Operation(summary = "The caller's own skill profile (offers + wants)")
    @GetMapping("/mine")
    @PreAuthorize("@featureGuard.check('SKILL_EXCHANGE')")
    public ResponseEntity<ResponseDto<SkillProfileResponse>> getMine(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        SkillProfileResponse response = skillExchangeService.getMine(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Replace the caller's entire skill set. Returns the resulting profile.
     */
    @Operation(summary = "Replace the caller's entire skill set")
    @PutMapping(value = "/mine", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@featureGuard.check('SKILL_EXCHANGE')")
    public ResponseEntity<ResponseDto<SkillProfileResponse>> updateMine(
            @Valid @RequestBody UpdateSkillsRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        SkillProfileResponse response = skillExchangeService.updateSkills(
                userDetails.getUser(), request.getOffers(), request.getWants());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Skills updated", "TM_000"));
    }

    /**
     * Users the caller can exchange skills with, reciprocal matches ranked first.
     */
    @Operation(summary = "Users the caller can exchange skills with, reciprocal matches ranked first")
    @GetMapping("/matches")
    @PreAuthorize("@featureGuard.check('SKILL_EXCHANGE')")
    public ResponseEntity<ResponseDto<List<SkillMatchResponse>>> getMatches(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<SkillMatchResponse> matches = skillExchangeService.findMatches(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(matches));
    }

    /**
     * Begin a study session with another user; returns their public match card so the client
     * can open a 1:1 chat.
     */
    @Operation(summary = "Begin a study session with another user; returns their public match card so the client can open a 1:1 chat")
    @PostMapping("/{userUuid}/study")
    @PreAuthorize("@featureGuard.check('SKILL_EXCHANGE')")
    public ResponseEntity<ResponseDto<SkillMatchResponse>> startStudy(
            @PathVariable("userUuid") String userUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        SkillMatchResponse response = skillExchangeService.startStudySession(userDetails.getUser(), userUuid);
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Study session ready", "TM_000"));
    }
}
