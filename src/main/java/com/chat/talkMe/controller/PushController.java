package com.chat.talkMe.controller;

import com.chat.talkMe.config.WebPushProperties;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.SavePushSubscriptionRequest;
import com.chat.talkMe.dto.request.UpdateInstallationRequest;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.security.JwtTokenProvider;
import com.chat.talkMe.service.ChatService;
import com.chat.talkMe.service.NotificationDispatchService;
import com.chat.talkMe.service.WebPushService;
import io.jsonwebtoken.Claims;
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
    private final JwtTokenProvider jwtTokenProvider;
    private final ChatService chatService;

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

    /**
     * Delivery acknowledgement from the service worker, fired when a push is
     * RECEIVED on the recipient's device (even while their tab/app is
     * backgrounder and the WebSocket is closed). Marks the chat delivered for the
     * recipient and broadcasts a DELIVERED receipt to the sender — the WhatsApp
     * "double tick" without the recipient having to reopen the app.
     *
     * Public (no Bearer auth): the signed, short-lived delivery token in the body
     * IS the authorization — it only grants "mark this one chat delivered for this
     * one user". Best-effort: always returns 200 so the SW never retries noisily.
     */
    @PostMapping("/delivered")
    public ResponseEntity<ResponseDto<Void>> ackDelivered(@RequestBody Map<String, String> body) {
        String token = body != null ? body.get("token") : null;
        if (token != null) {
            Claims claims = jwtTokenProvider.parseDeliveryToken(token);
            if (claims != null) {
                String username = claims.getSubject();
                String chatUuid = claims.get("chatUuid", String.class);
                if (username != null && chatUuid != null) {
                    userRepository.findByUsername(username).ifPresent(user ->
                            chatService.markDelivered(chatUuid, user));
                }
            }
        }
        return ResponseEntity.ok(SuccessResponseDto.success(null, "ok", "TM_283"));
    }
}
