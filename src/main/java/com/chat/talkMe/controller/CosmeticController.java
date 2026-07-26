package com.chat.talkMe.controller;

import com.chat.talkMe.dto.response.CosmeticResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.enums.CosmeticType;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.CosmeticService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Cosmetic rewards API (Phase 4 gamification surface). Gated by the {@code COSMETICS} feature.
 * Everything served is decoration — no endpoint here changes authorization or limits.
 */
@RestController
@RequestMapping("/cosmetics")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class CosmeticController {

    private final CosmeticService cosmeticService;

    /** Full catalog with owned/locked/equipped flags for the caller. */
    @GetMapping("/catalog")
    @PreAuthorize("@featureGuard.check('COSMETICS')")
    public ResponseEntity<ResponseDto<List<CosmeticResponse>>> catalog(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<CosmeticResponse> response = cosmeticService.catalog(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /** The caller's owned cosmetics. */
    @GetMapping("/me")
    @PreAuthorize("@featureGuard.check('COSMETICS')")
    public ResponseEntity<ResponseDto<List<CosmeticResponse>>> mine(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<CosmeticResponse> response = cosmeticService.myCosmetics(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /** Equip a cosmetic the caller owns. Body: {@code {"code": "..."}}. */
    @PutMapping("/equip")
    @PreAuthorize("@featureGuard.check('COSMETICS')")
    public ResponseEntity<ResponseDto<List<CosmeticResponse>>> equip(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        String code = body != null ? body.get("code") : null;
        List<CosmeticResponse> response = cosmeticService.equip(userDetails.getUser(), code);
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Cosmetic equipped", "TM_066"));
    }

    /** Unequip whatever is equipped in the given slot. */
    @DeleteMapping("/equip/{slot}")
    @PreAuthorize("@featureGuard.check('COSMETICS')")
    public ResponseEntity<ResponseDto<List<CosmeticResponse>>> unequip(
            @PathVariable("slot") String slot,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        CosmeticType parsed;
        try {
            parsed = CosmeticType.valueOf(slot.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BadRequestException("Unknown cosmetic slot: " + slot, "TM_934");
        }
        List<CosmeticResponse> response = cosmeticService.unequip(userDetails.getUser(), parsed);
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Cosmetic unequipped", "TM_066"));
    }
}
