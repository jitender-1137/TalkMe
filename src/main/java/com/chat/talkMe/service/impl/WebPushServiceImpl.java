package com.chat.talkMe.service.impl;

import com.chat.talkMe.config.WebPushProperties;
import com.chat.talkMe.domain.PushSubscription;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.SavePushSubscriptionRequest;
import com.chat.talkMe.enums.InstallationType;
import com.chat.talkMe.repository.PushSubscriptionRepository;
import com.chat.talkMe.service.WebPushService;
import com.chat.talkMe.util.SsrfGuard;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Urgency;
import org.apache.http.HttpResponse;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebPushServiceImpl implements WebPushService {

    private final PushSubscriptionRepository subscriptionRepository;
    private final PushService pushService;
    private final WebPushProperties properties;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    @Override
    @Transactional
    public void saveSubscription(User user, SavePushSubscriptionRequest request) {
        // SSRF guard: the server POSTs to this endpoint on every push. A legitimate
        // push endpoint is always a https URL on a public push-service host — reject
        // internal/loopback/link-local/private targets so a client can't turn the
        // dispatcher into an internal-request primitive.
        try {
            SsrfGuard.assertSafeHttps(request.getEndpoint());
        } catch (IllegalArgumentException e) {
            throw new com.chat.talkMe.exception.BadRequestException(
                    "Invalid push subscription endpoint", "TM_PUSH_ENDPOINT");
        }
        PushSubscription sub = subscriptionRepository.findByEndpoint(request.getEndpoint())
                .orElseGet(PushSubscription::new);
        sub.setUser(user);
        sub.setEndpoint(request.getEndpoint());
        sub.setP256dh(request.getP256dh());
        sub.setAuth(request.getAuth());
        sub.setInstallationType(
                request.getInstallationType() != null ? request.getInstallationType() : InstallationType.PWA);
        subscriptionRepository.save(sub);
        log.debug("[WebPush] Saved subscription for user {} ({} total)", user.getId(), request.getEndpoint());
    }

    @Override
    @Transactional
    public void removeSubscription(String endpoint) {
        subscriptionRepository.deleteByEndpoint(endpoint);
    }

    @Override
    @Transactional
    public void removeAllSubscriptionsForUser(Long userId) {
        int removed = subscriptionRepository.deleteByUserId(userId);
        if (removed > 0) {
            log.info("[WebPush] Cleared {} push subscription(s) for user {} on new login", removed, userId);
        }
    }

    @Async
    @Override
    @Transactional
    public void sendToUser(Long userId, String payloadJson) {
        if (!properties.isEnabled()) return;

        List<PushSubscription> subs = subscriptionRepository.findByUser_Id(userId);
        if (subs.isEmpty()) {
            log.debug("[WebPush] No subscriptions for user {} — nothing to send", userId);
            return;
        }
        byte[] payload = payloadJson.getBytes(StandardCharsets.UTF_8);
        for (PushSubscription sub : subs) {
            try {
                int status = sendOne(sub, payload);
                if (status == 404 || status == 410) {
                    subscriptionRepository.delete(sub);
                    log.info("[WebPush] Pruned expired subscription {} (status {})", sub.getEndpoint(), status);
                } else if (status >= 400) {
                    log.warn("[WebPush] Push FAILED status={} endpoint={}", status, sub.getEndpoint());
                } else {
                    log.info("[WebPush] Push sent (status {}) to {}", status, sub.getEndpoint());
                }
            } catch (Exception e) {
                log.error("[WebPush] Error sending push to {}", sub.getEndpoint(), e);
            }
        }
    }

    /**
     * Build and send a single Web Push; returns the push service HTTP status.
     */
    private int sendOne(PushSubscription sub, byte[] payload) throws Exception {
        Notification notification = Notification.builder()
                .endpoint(sub.getEndpoint())
                .userPublicKey(sub.getP256dh())
                .userAuth(sub.getAuth())
                .payload(payload)
                // HIGH urgency + a TTL so push services still deliver to a
                // closed/dozing device instead of dropping the message.
                .urgency(Urgency.HIGH)
                .ttl((int) java.util.concurrent.TimeUnit.HOURS.toSeconds(24))
                .build();
        // Guard the outbound push with a circuit breaker: if the push relays are
        // failing/slow, the breaker opens and these calls fail fast (throwing
        // CallNotPermittedException, handled by the per-subscription catch in the
        // caller) instead of tying up async threads.
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker("webpush");
        HttpResponse response = breaker.executeCallable(() -> pushService.send(notification));
        return response.getStatusLine().getStatusCode();
    }
}
