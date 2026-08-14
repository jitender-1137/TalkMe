package com.neo.chat.service.impl;

import com.neo.chat.config.WebPushProperties;
import com.neo.chat.domain.PushSubscription;
import com.neo.chat.domain.User;
import com.neo.chat.dto.request.SavePushSubscriptionRequest;
import com.neo.chat.enums.InstallationType;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.repository.PushSubscriptionRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link WebPushServiceImpl} — persistence of W3C push
 * subscriptions plus the fan-out send loop.
 *
 * <p>Key invariants: (1) {@code saveSubscription} SSRF-guards the endpoint (only public
 * https allowed) → {@code TM_PUSH_ENDPOINT} otherwise, upserts by endpoint, and defaults
 * the installation type to PWA; (2) {@code sendToUser} is gated on the feature flag,
 * no-ops on an empty subscription set, prunes 404/410 endpoints, leaves other 4xx/5xx in
 * place, and isolates a per-subscription send failure from the rest of the batch.
 *
 * <p>The real push HTTP call is short-circuited by mocking the resilience4j circuit
 * breaker's {@code executeCallable}, so {@code sendOne} builds a real Web Push
 * {@link nl.martijndwars.webpush.Notification} (requires a valid P-256 key, generated
 * once below) but never touches the network.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WebPushServiceImpl (unit)")
class WebPushServiceImplTest {

    static {
        // The webpush library decodes p256dh via the BouncyCastle "BC" provider
        // (registered by WebPushConfig in prod); ensure it is present for sendOne.
        if (Security.getProvider(
                BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    /**
     * A real, curve-valid P-256 public point (base64url) — the webpush lib decodes it.
     */
    private static final String VALID_P256DH = generateP256dh();
    private static final String VALID_AUTH =
            Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(16));
    /**
     * Public IP literal → passes the SSRF guard with no DNS lookup.
     */
    private static final String SAFE_ENDPOINT = "https://93.184.216.34/push/abc";

    @Mock
    private PushSubscriptionRepository subscriptionRepository;
    @Mock
    private PushService pushService;
    @Mock
    private WebPushProperties properties;
    @Mock
    private CircuitBreakerRegistry circuitBreakerRegistry;
    @Mock
    private CircuitBreaker circuitBreaker;

    private WebPushServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new WebPushServiceImpl(subscriptionRepository, pushService, properties, circuitBreakerRegistry);
    }

    private static SavePushSubscriptionRequest request(InstallationType type) {
        return SavePushSubscriptionRequest.builder()
                .endpoint(SAFE_ENDPOINT).p256dh(VALID_P256DH).auth(VALID_AUTH)
                .installationType(type).build();
    }

    private static PushSubscription subscription(String endpoint) {
        PushSubscription sub = new PushSubscription();
        sub.setEndpoint(endpoint);
        sub.setP256dh(VALID_P256DH);
        sub.setAuth(VALID_AUTH);
        return sub;
    }

    private HttpResponse response(int status) {
        HttpResponse resp = mock(HttpResponse.class);
        StatusLine line = mock(StatusLine.class);
        when(line.getStatusCode()).thenReturn(status);
        when(resp.getStatusLine()).thenReturn(line);
        return resp;
    }

    @Nested
    @DisplayName("saveSubscription")
    class SaveSubscription {

        @Test
        @DisplayName("new endpoint → upserts a fresh subscription, defaulting type to PWA")
        void savesNewDefaultingType() {
            User user = new User();
            user.setId(3L);
            when(subscriptionRepository.findByEndpoint(SAFE_ENDPOINT)).thenReturn(Optional.empty());

            service.saveSubscription(user, request(null));

            ArgumentCaptor<PushSubscription> captor = ArgumentCaptor.forClass(PushSubscription.class);
            verify(subscriptionRepository).save(captor.capture());
            PushSubscription saved = captor.getValue();
            assertThat(saved.getUser()).isSameAs(user);
            assertThat(saved.getEndpoint()).isEqualTo(SAFE_ENDPOINT);
            assertThat(saved.getP256dh()).isEqualTo(VALID_P256DH);
            assertThat(saved.getAuth()).isEqualTo(VALID_AUTH);
            assertThat(saved.getInstallationType()).isEqualTo(InstallationType.PWA);
        }

        @Test
        @DisplayName("explicit installation type is honoured")
        void honoursExplicitType() {
            User user = new User();
            user.setId(3L);
            when(subscriptionRepository.findByEndpoint(SAFE_ENDPOINT)).thenReturn(Optional.empty());

            service.saveSubscription(user, request(InstallationType.IOS_HOME));

            ArgumentCaptor<PushSubscription> captor = ArgumentCaptor.forClass(PushSubscription.class);
            verify(subscriptionRepository).save(captor.capture());
            assertThat(captor.getValue().getInstallationType()).isEqualTo(InstallationType.IOS_HOME);
        }

        @Test
        @DisplayName("existing endpoint → reuses and updates the same row")
        void reusesExisting() {
            User user = new User();
            user.setId(3L);
            PushSubscription existing = subscription(SAFE_ENDPOINT);
            when(subscriptionRepository.findByEndpoint(SAFE_ENDPOINT)).thenReturn(Optional.of(existing));

            service.saveSubscription(user, request(InstallationType.PWA));

            ArgumentCaptor<PushSubscription> captor = ArgumentCaptor.forClass(PushSubscription.class);
            verify(subscriptionRepository).save(captor.capture());
            assertThat(captor.getValue()).isSameAs(existing);
            assertThat(captor.getValue().getUser()).isSameAs(user);
        }

        @Test
        @DisplayName("loopback / internal endpoint → BadRequestException TM_PUSH_ENDPOINT, no save")
        void rejectsInternalEndpoint() {
            User user = new User();
            user.setId(3L);
            SavePushSubscriptionRequest req = SavePushSubscriptionRequest.builder()
                    .endpoint("http://127.0.0.1/push").p256dh(VALID_P256DH).auth(VALID_AUTH).build();

            assertThatThrownBy(() -> service.saveSubscription(user, req))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_PUSH_ENDPOINT"));
            verify(subscriptionRepository, never()).save(any());
        }

        @Test
        @DisplayName("non-https endpoint → BadRequestException TM_PUSH_ENDPOINT, no save")
        void rejectsNonHttps() {
            User user = new User();
            user.setId(3L);
            SavePushSubscriptionRequest req = SavePushSubscriptionRequest.builder()
                    .endpoint("http://93.184.216.34/push").p256dh(VALID_P256DH).auth(VALID_AUTH).build();

            assertThatThrownBy(() -> service.saveSubscription(user, req))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_PUSH_ENDPOINT"));
            verify(subscriptionRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("removeSubscription")
    class RemoveSubscription {

        @Test
        @DisplayName("delegates to deleteByEndpoint")
        void delegates() {
            service.removeSubscription("https://x/y");

            verify(subscriptionRepository).deleteByEndpoint("https://x/y");
        }
    }

    @Nested
    @DisplayName("removeAllSubscriptionsForUser")
    class RemoveAllSubscriptionsForUser {

        @Test
        @DisplayName("delegates to deleteByUserId (rows removed)")
        void delegatesWithRows() {
            when(subscriptionRepository.deleteByUserId(9L)).thenReturn(2);

            service.removeAllSubscriptionsForUser(9L);

            verify(subscriptionRepository).deleteByUserId(9L);
        }

        @Test
        @DisplayName("delegates to deleteByUserId (no rows) without error")
        void delegatesWithNoRows() {
            when(subscriptionRepository.deleteByUserId(9L)).thenReturn(0);

            service.removeAllSubscriptionsForUser(9L);

            verify(subscriptionRepository).deleteByUserId(9L);
        }
    }

    @Nested
    @DisplayName("sendToUser")
    class SendToUser {

        @Test
        @DisplayName("feature disabled → no repository access")
        void disabledNoop() {
            when(properties.isEnabled()).thenReturn(false);

            service.sendToUser(5L, "{}");

            verify(subscriptionRepository, never()).findByUser_Id(anyLong());
        }

        @Test
        @DisplayName("no subscriptions → nothing sent, nothing pruned")
        void emptySubscriptions() {
            when(properties.isEnabled()).thenReturn(true);
            when(subscriptionRepository.findByUser_Id(5L)).thenReturn(List.of());

            service.sendToUser(5L, "{}");

            verify(subscriptionRepository, never()).delete(any());
        }

        @Test
        @DisplayName("404 response → subscription pruned")
        void pruneOn404() throws Exception {
            PushSubscription sub = subscription("https://push/1");
            arrangeSend(sub, response(404));

            service.sendToUser(5L, "{\"a\":1}");

            verify(subscriptionRepository).delete(sub);
        }

        @Test
        @DisplayName("410 response → subscription pruned")
        void pruneOn410() throws Exception {
            PushSubscription sub = subscription("https://push/1");
            arrangeSend(sub, response(410));

            service.sendToUser(5L, "{}");

            verify(subscriptionRepository).delete(sub);
        }

        @Test
        @DisplayName("other 4xx/5xx (e.g. 500) → NOT pruned")
        void keepsOnServerError() throws Exception {
            PushSubscription sub = subscription("https://push/1");
            arrangeSend(sub, response(500));

            service.sendToUser(5L, "{}");

            verify(subscriptionRepository, never()).delete(any());
        }

        @Test
        @DisplayName("success (201) → NOT pruned")
        void keepsOnSuccess() throws Exception {
            PushSubscription sub = subscription("https://push/1");
            arrangeSend(sub, response(201));

            service.sendToUser(5L, "{}");

            verify(subscriptionRepository, never()).delete(any());
        }

        @Test
        @DisplayName("one send failure is isolated — the other subscription still processes")
        void perSubscriptionFailureIsolated() throws Exception {
            PushSubscription bad = subscription("https://push/bad");
            PushSubscription good = subscription("https://push/good");
            when(properties.isEnabled()).thenReturn(true);
            when(subscriptionRepository.findByUser_Id(5L)).thenReturn(List.of(bad, good));
            when(circuitBreakerRegistry.circuitBreaker("webpush")).thenReturn(circuitBreaker);
            when(circuitBreaker.executeCallable(any()))
                    .thenThrow(new RuntimeException("relay down"))
                    .thenReturn(response(410));

            service.sendToUser(5L, "{}");

            // bad threw (no prune), good returned 410 (pruned) → batch not aborted
            verify(subscriptionRepository).delete(good);
            verify(subscriptionRepository, never()).delete(bad);
        }
    }

    /**
     * Wire up an enabled, single-subscription send whose HTTP status is {@code resp}.
     */
    private void arrangeSend(PushSubscription sub, HttpResponse resp) throws Exception {
        when(properties.isEnabled()).thenReturn(true);
        when(subscriptionRepository.findByUser_Id(5L)).thenReturn(List.of(sub));
        when(circuitBreakerRegistry.circuitBreaker("webpush")).thenReturn(circuitBreaker);
        when(circuitBreaker.executeCallable(any())).thenReturn(resp);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    /**
     * Generate a valid secp256r1 public key encoded as an uncompressed base64url point.
     */
    private static String generateP256dh() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"));
            ECPublicKey pub = (ECPublicKey) kpg.generateKeyPair().getPublic();
            byte[] x = to32(pub.getW().getAffineX());
            byte[] y = to32(pub.getW().getAffineY());
            byte[] point = new byte[65];
            point[0] = 0x04;
            System.arraycopy(x, 0, point, 1, 32);
            System.arraycopy(y, 0, point, 33, 32);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(point);
        } catch (Exception e) {
            throw new IllegalStateException("could not generate test P-256 key", e);
        }
    }

    private static byte[] to32(BigInteger v) {
        byte[] b = v.toByteArray();
        byte[] out = new byte[32];
        if (b.length == 32) {
            return b;
        } else if (b.length > 32) {
            System.arraycopy(b, b.length - 32, out, 0, 32);
        } else {
            System.arraycopy(b, 0, out, 32 - b.length, b.length);
        }
        return out;
    }
}
