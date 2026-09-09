package com.neo.chat.service.impl;

import com.neo.chat.domain.PushSubscription;
import com.neo.chat.repository.PushSubscriptionRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Urgency;
import org.apache.http.HttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Transactional fan-out of one Web Push payload to every subscription of a user, pruning
 * endpoints the push service reports as gone (404/410).
 *
 * <p>Kept as its own bean so {@link WebPushServiceImpl#sendToUser} can stay purely
 * {@code @Async} and call this through the Spring proxy — the transaction is then scoped to the
 * async worker thread instead of being declared on the same method as {@code @Async}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebPushDelivery {

    private final PushSubscriptionRepository subscriptionRepository;
    private final PushService pushService;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    /**
     * Sends the payload to each of the user's subscriptions; per-endpoint failures are logged and
     * never abort the loop.
     *
     * @param userId      the recipient
     * @param payloadJson the notification payload (JSON)
     */
    @Transactional
    public void deliverToUser(Long userId, String payloadJson) {
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

    private int sendOne(PushSubscription sub, byte[] payload) throws Exception {
        Notification notification = Notification.builder()
                .endpoint(sub.getEndpoint())
                .userPublicKey(sub.getP256dh())
                .userAuth(sub.getAuth())
                .payload(payload)
                // HIGH urgency + a TTL so push services still deliver to a
                // closed/dozing device instead of dropping the message.
                .urgency(Urgency.HIGH)
                .ttl((int) TimeUnit.HOURS.toSeconds(24))
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
