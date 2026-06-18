package com.chat.talkMe.controller;

import com.chat.talkMe.config.WebPushProperties;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.SavePushSubscriptionRequest;
import com.chat.talkMe.dto.request.UpdateInstallationRequest;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.NotificationDispatchService;
import com.chat.talkMe.service.WebPushService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/push")
@RequiredArgsConstructor
public class PushController {

    private final WebPushService webPushService;
    private final WebPushProperties webPushProperties;
    private final UserRepository userRepository;
    private final NotificationDispatchService notificationDispatchService;

    /** VAPID public key the browser needs to create a push subscription. */
    @GetMapping("/vapid-public-key")
    public ResponseEntity<ResponseDto<Map<String, String>>> getVapidPublicKey() {
        return ResponseEntity.ok(SuccessResponseDto.success(
                Map.of("publicKey", webPushProperties.getVapid().getPublicKey())));
    }

    @PostMapping("/subscribe")
    public ResponseEntity<ResponseDto<Void>> subscribe(
            @Valid @RequestBody SavePushSubscriptionRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        webPushService.saveSubscription(userDetails.getUser(), request);
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Push subscription saved", "TM_280"));
    }

    @DeleteMapping("/subscribe")
    public ResponseEntity<ResponseDto<Void>> unsubscribe(@RequestParam("endpoint") String endpoint) {
        webPushService.removeSubscription(endpoint);
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Push subscription removed", "TM_281"));
    }

    /** Report how the user is accessing the app (BROWSER / PWA / IOS_HOME). */
    @PutMapping("/installation")
    public ResponseEntity<ResponseDto<Void>> updateInstallation(
            @Valid @RequestBody UpdateInstallationRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        User user = userDetails.getUser();
        user.setInstallationType(request.getInstallationType());
        userRepository.save(user);
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Installation type updated", "TM_282"));
    }

    /** Authoritative unread total (recomputed) — used on load and after reconnect/offline. */
    @GetMapping("/unread-count")
    public ResponseEntity<ResponseDto<Map<String, Integer>>> getUnreadCount(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        int count = notificationDispatchService.recomputeUnread(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(Map.of("totalUnread", count)));
    }
}
