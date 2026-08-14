package com.neo.chat.controller;

import com.neo.chat.dto.response.CosmeticResponse;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.enums.CosmeticType;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.CosmeticService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    /**
     * Full catalog with owned/locked/equipped flags for the caller.
     *
     * @param userDetails the authenticated principal
     * @return the list of {@link CosmeticResponse} for the whole catalog
     */
    @GetMapping("/catalog")
    @PreAuthorize("@featureGuard.check('COSMETICS')")
    public ResponseEntity<ResponseDto<List<CosmeticResponse>>> catalog(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<CosmeticResponse> response = cosmeticService.catalog(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * The caller's owned cosmetics.
     *
     * @param userDetails the authenticated principal
     * @return the list of {@link CosmeticResponse} the caller owns
     */
    @GetMapping("/me")
    @PreAuthorize("@featureGuard.check('COSMETICS')")
    public ResponseEntity<ResponseDto<List<CosmeticResponse>>> mine(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<CosmeticResponse> response = cosmeticService.myCosmetics(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Equip a cosmetic the caller owns. Body: {@code {"code": "..."}}.
     *
     * @param body        request body carrying the cosmetic {@code code}
     * @param userDetails the authenticated principal
     * @return the caller's cosmetics list reflecting the new equipped state
     * @throws com.neo.chat.exception.BadRequestException if the code is missing or the cosmetic
     *                                                       is not yet unlocked by the caller
     * @throws com.neo.chat.exception.NotFoundException   if the code matches no known cosmetic
     */
    @PutMapping("/equip")
    @PreAuthorize("@featureGuard.check('COSMETICS')")
    public ResponseEntity<ResponseDto<List<CosmeticResponse>>> equip(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        String code = body != null ? body.get("code") : null;
        List<CosmeticResponse> response = cosmeticService.equip(userDetails.getUser(), code);
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Cosmetic equipped", "TM_066"));
    }

    /**
     * Unequip whatever is equipped in the given slot.
     *
     * @param slot        the cosmetic slot name (parsed to {@link CosmeticType})
     * @param userDetails the authenticated principal
     * @return the caller's cosmetics list reflecting the cleared slot
     * @throws com.neo.chat.exception.BadRequestException if the slot name is unknown
     */
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
