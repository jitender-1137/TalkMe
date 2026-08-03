package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.EmailUnreadPreview;
import com.chat.talkMe.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link EmailServiceImpl} — the Resend → Brevo → SMTP
 * transactional-mail chain with Redis daily-quota routing.
 *
 * <p>The internal {@link HttpClient} is a private final field created inline; the test swaps a
 * mock over it via {@link ReflectionTestUtils} and drives responses per-request. A REAL
 * {@link ObjectMapper} serialises the provider bodies. {@link StringRedisTemplate} and
 * {@link JavaMailSender} are reached through mocked {@link ObjectProvider}s so the "no bean"
 * paths are exercised too.</p>
 *
 * <p>Invariants covered: the {@code app.mail.enabled} + provider-configured gate; the
 * primary→fallback walk (Resend ok / Resend fails → Brevo / both fail → SMTP / all fail);
 * per-provider daily quota reserve/release; disposable-address and unverified-recipient
 * suppression; and best-effort fail-safety (a provider throwing never propagates).</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmailServiceImpl (unit)")
class EmailServiceImplTest {

    private static final String TO = "jane@example.com";
    private static final String NAME = "Jane";
    private static final String RESEND_HOST = "api.resend.com";
    private static final String BREVO_HOST = "api.brevo.com";

    @Mock private ObjectProvider<StringRedisTemplate> redisProvider;
    @Mock private ObjectProvider<JavaMailSender> mailSenderProvider;
    @Mock private EmailTemplates templates;
    @Mock private UserRepository userRepository;
    @Mock private DisposableEmailDomains disposableDomains;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private JavaMailSender mailSender;
    @Mock private HttpClient http;

    private EmailServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new EmailServiceImpl(new ObjectMapper(), redisProvider, mailSenderProvider,
                templates, userRepository, disposableDomains);
        ReflectionTestUtils.setField(service, "http", http);
        // Sensible default config: mail on, verification required, only the from address set.
        ReflectionTestUtils.setField(service, "mailEnabled", true);
        ReflectionTestUtils.setField(service, "requireVerification", true);
        ReflectionTestUtils.setField(service, "from", "NeoChatHub <noreply@neochathub.com>");
        ReflectionTestUtils.setField(service, "timeoutMs", 10_000L);
        ReflectionTestUtils.setField(service, "resendApiKey", "");
        ReflectionTestUtils.setField(service, "resendDailyLimit", 100);
        ReflectionTestUtils.setField(service, "brevoApiKey", "");
        ReflectionTestUtils.setField(service, "brevoDailyLimit", 300);
        ReflectionTestUtils.setField(service, "smtpEnabled", false);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private void withResend() {
        ReflectionTestUtils.setField(service, "resendApiKey", "re_key");
    }

    private void withBrevo() {
        ReflectionTestUtils.setField(service, "brevoApiKey", "brevo_key");
    }

    private void withSmtp() {
        ReflectionTestUtils.setField(service, "smtpEnabled", true);
        lenient().when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);
        lenient().when(mailSender.createMimeMessage()).thenReturn(mock(MimeMessage.class));
    }

    private static HttpResponse<String> response(int status) {
        @SuppressWarnings("unchecked")
        HttpResponse<String> resp = mock(HttpResponse.class);
        lenient().when(resp.statusCode()).thenReturn(status);
        lenient().when(resp.body()).thenReturn("body-" + status);
        return resp;
    }

    /** Route each send by the request host so provider order is asserted, not assumed. */
    @SuppressWarnings("unchecked")
    private void stubByHost(int resendStatus, int brevoStatus) throws Exception {
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(inv -> {
            HttpRequest req = inv.getArgument(0);
            String host = req.uri().getHost();
            if (RESEND_HOST.equals(host)) return response(resendStatus);
            if (BREVO_HOST.equals(host)) return response(brevoStatus);
            throw new IllegalStateException("unexpected host " + host);
        });
    }

    @SuppressWarnings("unchecked")
    private void stubSend(HttpResponse<String> resp) throws Exception {
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);
    }

    private static User verifiedUser() {
        User u = User.builder().email(TO).name(NAME).build();
        u.setVerified(true);
        return u;
    }

    // ── delivery gate ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("delivery gate (app.mail.enabled + provider configured)")
    class DeliveryGate {

        @Test
        @DisplayName("mail disabled → nothing rendered or sent")
        void disabledIsNoop() throws Exception {
            ReflectionTestUtils.setField(service, "mailEnabled", false);
            withResend();

            service.sendPasswordResetEmail(TO, NAME, "https://x/reset", 30);

            verify(templates, never()).passwordReset(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong());
            verify(http, never()).send(any(), any());
        }

        @Test
        @DisplayName("enabled but no provider configured (no keys, SMTP off) → no-op")
        void enabledButUnconfiguredIsNoop() throws Exception {
            service.sendWelcomeEmail(TO, NAME, "https://x/open");

            verify(templates, never()).welcome(anyString(), anyString());
            verify(http, never()).send(any(), any());
        }
    }

    // ── provider chain ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("provider fallthrough chain")
    class ProviderChain {

        @Test
        @DisplayName("Resend accepts (2xx) → sent via Resend, Brevo not attempted")
        void resendSucceeds() throws Exception {
            withResend();
            withBrevo();
            stubByHost(200, 200);
            when(templates.passwordReset(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong()))
                    .thenReturn("<html>");

            service.sendPasswordResetEmail(TO, NAME, "https://x/reset", 30);

            ArgumentCaptor<HttpRequest> req = ArgumentCaptor.forClass(HttpRequest.class);
            verify(http, times(1)).send(req.capture(), any());
            assertThat(req.getValue().uri().getHost()).isEqualTo(RESEND_HOST);
        }

        @Test
        @DisplayName("Resend rejects (non-2xx) → falls through to Brevo which accepts")
        void resendFailsThenBrevo() throws Exception {
            withResend();
            withBrevo();
            stubByHost(500, 200);
            when(templates.passwordReset(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong()))
                    .thenReturn("<html>");

            service.sendPasswordResetEmail(TO, NAME, "https://x/reset", 30);

            ArgumentCaptor<HttpRequest> req = ArgumentCaptor.forClass(HttpRequest.class);
            verify(http, times(2)).send(req.capture(), any());
            assertThat(req.getAllValues().get(0).uri().getHost()).isEqualTo(RESEND_HOST);
            assertThat(req.getAllValues().get(1).uri().getHost()).isEqualTo(BREVO_HOST);
        }

        @Test
        @DisplayName("both HTTP providers fail → SMTP backstop sends")
        void bothHttpFailThenSmtp() throws Exception {
            withResend();
            withBrevo();
            withSmtp();
            stubByHost(500, 502);
            when(templates.passwordReset(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong()))
                    .thenReturn("<html>");

            service.sendPasswordResetEmail(TO, NAME, "https://x/reset", 30);

            verify(http, times(2)).send(any(), any());
            verify(mailSender).send(any(MimeMessage.class));
        }

        @Test
        @DisplayName("all providers fail → nothing sent, no exception")
        void allFail() throws Exception {
            withResend();
            withBrevo();
            withSmtp();
            stubByHost(500, 502);
            org.mockito.Mockito.doThrow(new RuntimeException("smtp down"))
                    .when(mailSender).send(any(MimeMessage.class));
            when(templates.passwordReset(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong()))
                    .thenReturn("<html>");

            assertThatCode(() -> service.sendPasswordResetEmail(TO, NAME, "https://x/reset", 30))
                    .doesNotThrowAnyException();

            verify(http, times(2)).send(any(), any());
        }

        @Test
        @DisplayName("no Resend key → chain starts at Brevo (Resend skipped entirely)")
        void noResendKeySkipsToBrevo() throws Exception {
            withBrevo();
            stubByHost(200, 200);
            when(templates.passwordReset(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong()))
                    .thenReturn("<html>");

            service.sendPasswordResetEmail(TO, NAME, "https://x/reset", 30);

            ArgumentCaptor<HttpRequest> req = ArgumentCaptor.forClass(HttpRequest.class);
            verify(http, times(1)).send(req.capture(), any());
            assertThat(req.getValue().uri().getHost()).isEqualTo(BREVO_HOST);
        }

        @Test
        @DisplayName("Brevo with a bare 'from' (no display name) still builds a valid sender")
        void brevoBareFromAddress() throws Exception {
            ReflectionTestUtils.setField(service, "from", "noreply@neochathub.com");
            withBrevo();
            stubByHost(200, 200);
            when(templates.passwordReset(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong()))
                    .thenReturn("<html>");

            service.sendPasswordResetEmail(TO, NAME, "https://x/reset", 30);

            ArgumentCaptor<HttpRequest> req = ArgumentCaptor.forClass(HttpRequest.class);
            verify(http).send(req.capture(), any());
            assertThat(req.getValue().uri().getHost()).isEqualTo(BREVO_HOST);
        }

        @Test
        @DisplayName("Resend throws IOException → caught, falls through to Brevo")
        void resendThrowsThenBrevo() throws Exception {
            withResend();
            withBrevo();
            when(http.send(any(HttpRequest.class), any())).thenAnswer(inv -> {
                HttpRequest req = inv.getArgument(0);
                if (RESEND_HOST.equals(req.uri().getHost())) {
                    throw new IOException("resend down");
                }
                return response(200);
            });
            when(templates.passwordReset(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong()))
                    .thenReturn("<html>");

            service.sendPasswordResetEmail(TO, NAME, "https://x/reset", 30);

            verify(http, times(2)).send(any(), any());
        }

        @Test
        @DisplayName("Resend interrupted → caught (thread re-interrupted), falls through to Brevo")
        void resendInterruptedThenBrevo() throws Exception {
            withResend();
            withBrevo();
            when(http.send(any(HttpRequest.class), any())).thenAnswer(inv -> {
                HttpRequest req = inv.getArgument(0);
                if (RESEND_HOST.equals(req.uri().getHost())) {
                    throw new InterruptedException("interrupted");
                }
                return response(200);
            });
            when(templates.passwordReset(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong()))
                    .thenReturn("<html>");

            assertThatCode(() -> service.sendPasswordResetEmail(TO, NAME, "https://x/reset", 30))
                    .doesNotThrowAnyException();
            verify(http, times(2)).send(any(), any());
        }
    }

    // ── quota ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Redis daily quota")
    class Quota {

        @BeforeEach
        void redisAvailable() {
            when(redisProvider.getIfAvailable()).thenReturn(redis);
            lenient().when(redis.opsForValue()).thenReturn(valueOps);
        }

        @Test
        @DisplayName("first slot of the day (used==1) sets a 26h expiry then sends")
        void firstSlotSetsExpiry() throws Exception {
            withResend();
            when(valueOps.increment(anyString())).thenReturn(1L);
            stubSend(response(200));
            when(templates.passwordReset(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong()))
                    .thenReturn("<html>");

            service.sendPasswordResetEmail(TO, NAME, "https://x/reset", 30);

            verify(redis).expire(anyString(), eq(Duration.ofHours(26)));
            verify(http).send(any(), any());
        }

        @Test
        @DisplayName("quota exhausted (used > limit) → provider skipped, no HTTP send")
        void quotaExhaustedSkips() throws Exception {
            withResend();
            when(valueOps.increment(anyString())).thenReturn(101L);

            service.sendPasswordResetEmail(TO, NAME, "https://x/reset", 30);

            verify(http, never()).send(any(), any());
            verify(redis, never()).expire(anyString(), any());
        }

        @Test
        @DisplayName("increment returns null → treated as allowed, no expiry set")
        void nullIncrementAllows() throws Exception {
            withResend();
            when(valueOps.increment(anyString())).thenReturn(null);
            stubSend(response(200));
            when(templates.passwordReset(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong()))
                    .thenReturn("<html>");

            service.sendPasswordResetEmail(TO, NAME, "https://x/reset", 30);

            verify(http).send(any(), any());
            verify(redis, never()).expire(anyString(), any());
        }

        @Test
        @DisplayName("Redis increment throws → fail-open (allowed), still sends")
        void redisErrorAllows() throws Exception {
            withResend();
            when(valueOps.increment(anyString())).thenThrow(new RuntimeException("redis down"));
            stubSend(response(200));
            when(templates.passwordReset(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong()))
                    .thenReturn("<html>");

            service.sendPasswordResetEmail(TO, NAME, "https://x/reset", 30);

            verify(http).send(any(), any());
        }

        @Test
        @DisplayName("HTTP failure releases the reserved slot (decrement)")
        void failureReleasesSlot() throws Exception {
            withResend();
            when(valueOps.increment(anyString())).thenReturn(1L);
            stubSend(response(500));
            when(templates.passwordReset(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong()))
                    .thenReturn("<html>");

            service.sendPasswordResetEmail(TO, NAME, "https://x/reset", 30);

            verify(valueOps).decrement(anyString());
        }
    }

    // ── deliverability gate ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("recipient deliverability")
    class Deliverability {

        @Test
        @DisplayName("null recipient → suppressed, no send")
        void nullRecipientSuppressed() throws Exception {
            withResend();

            service.sendHtmlEmail(null, NAME, "Subject", "<html>");

            verify(http, never()).send(any(), any());
        }

        @Test
        @DisplayName("disposable address → suppressed even when configured")
        void disposableSuppressed() throws Exception {
            withResend();
            when(disposableDomains.isDisposable(TO)).thenReturn(true);

            service.sendWelcomeEmail(TO, NAME, "https://x/open");

            verify(http, never()).send(any(), any());
        }

        @Test
        @DisplayName("TRANSACTIONAL to unverified address → suppressed")
        void unverifiedTransactionalSuppressed() throws Exception {
            withResend();
            when(userRepository.findByEmailIgnoreCase(TO)).thenReturn(Optional.empty());

            service.sendPasswordChangedEmail(TO, NAME);

            verify(http, never()).send(any(), any());
        }

        @Test
        @DisplayName("TRANSACTIONAL to verified address → delivered")
        void verifiedTransactionalDelivered() throws Exception {
            withResend();
            when(userRepository.findByEmailIgnoreCase(TO)).thenReturn(Optional.of(verifiedUser()));
            stubSend(response(200));
            when(templates.passwordChanged(NAME)).thenReturn("<html>");

            service.sendPasswordChangedEmail(TO, NAME);

            verify(http).send(any(), any());
        }

        @Test
        @DisplayName("require-verification off → TRANSACTIONAL to unverified still sends")
        void requireVerificationOffSends() throws Exception {
            ReflectionTestUtils.setField(service, "requireVerification", false);
            withResend();
            stubSend(response(200));
            when(templates.passwordChanged(NAME)).thenReturn("<html>");

            service.sendPasswordChangedEmail(TO, NAME);

            verify(http).send(any(), any());
            verify(userRepository, never()).findByEmailIgnoreCase(anyString());
        }

        @Test
        @DisplayName("verification lookup throws → fails closed, TRANSACTIONAL suppressed")
        void verificationLookupErrorFailsClosed() throws Exception {
            withResend();
            when(userRepository.findByEmailIgnoreCase(TO)).thenThrow(new RuntimeException("db down"));

            service.sendPasswordChangedEmail(TO, NAME);

            verify(http, never()).send(any(), any());
        }

        @Test
        @DisplayName("PASSWORD_RESET is exempt from the verification gate (account recovery)")
        void passwordResetExemptFromVerification() throws Exception {
            withResend();
            stubSend(response(200));
            when(templates.passwordReset(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong()))
                    .thenReturn("<html>");

            service.sendPasswordResetEmail(TO, NAME, "https://x/reset", 30);

            verify(http).send(any(), any());
            verify(userRepository, never()).findByEmailIgnoreCase(anyString());
        }
    }

    // ── per-template rendering ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("per-template rendering + dispatch")
    class PerTemplate {

        @BeforeEach
        void configure() throws Exception {
            withResend();
            // Skip the verification gate so TRANSACTIONAL types deliver without stubbing a user.
            ReflectionTestUtils.setField(service, "requireVerification", false);
            stubSend(response(200));
        }

        @Test
        @DisplayName("password reset → templates.passwordReset + delivered")
        void passwordReset() {
            when(templates.passwordReset(NAME, "https://x/reset", 45)).thenReturn("<html>");

            service.sendPasswordResetEmail(TO, NAME, "https://x/reset", 45);

            verify(templates).passwordReset(NAME, "https://x/reset", 45);
        }

        @Test
        @DisplayName("welcome → templates.welcome + delivered")
        void welcome() {
            when(templates.welcome(NAME, "https://x/open")).thenReturn("<html>");

            service.sendWelcomeEmail(TO, NAME, "https://x/open");

            verify(templates).welcome(NAME, "https://x/open");
        }

        @Test
        @DisplayName("verification → templates.verifyEmail + delivered")
        void verification() {
            when(templates.verifyEmail(NAME, "https://x/verify", 60)).thenReturn("<html>");

            service.sendVerificationEmail(TO, NAME, "https://x/verify", 60);

            verify(templates).verifyEmail(NAME, "https://x/verify", 60);
        }

        @Test
        @DisplayName("unread digest, single message → singular subject branch + delivered")
        void unreadSingle() throws Exception {
            List<EmailUnreadPreview> previews = List.of(new EmailUnreadPreview("Bob", "hi", null, "2m ago"));
            when(templates.unreadMessages(NAME, previews, 1, "https://x/open")).thenReturn("<html>");

            service.sendUnreadMessagesEmail(TO, NAME, previews, 1, "https://x/open");

            verify(templates).unreadMessages(NAME, previews, 1, "https://x/open");
            verify(http).send(any(), any());
        }

        @Test
        @DisplayName("unread digest, many messages → plural subject branch + delivered")
        void unreadPlural() throws Exception {
            List<EmailUnreadPreview> previews = List.of(new EmailUnreadPreview("Bob", "hi", null, "2m ago"));
            when(templates.unreadMessages(NAME, previews, 4, "https://x/open")).thenReturn("<html>");

            service.sendUnreadMessagesEmail(TO, NAME, previews, 4, "https://x/open");

            verify(templates).unreadMessages(NAME, previews, 4, "https://x/open");
            verify(http).send(any(), any());
        }

        @Test
        @DisplayName("password changed → templates.passwordChanged + delivered")
        void passwordChanged() {
            when(templates.passwordChanged(NAME)).thenReturn("<html>");

            service.sendPasswordChangedEmail(TO, NAME);

            verify(templates).passwordChanged(NAME);
        }

        @Test
        @DisplayName("login alert → templates.loginAlert with all fields + delivered")
        void loginAlert() {
            when(templates.loginAlert(NAME, "iPhone", "NY", "1.2.3.4", "now", "https://x/secure"))
                    .thenReturn("<html>");

            service.sendLoginAlertEmail(TO, NAME, "iPhone", "NY", "1.2.3.4", "now", "https://x/secure");

            verify(templates).loginAlert(NAME, "iPhone", "NY", "1.2.3.4", "now", "https://x/secure");
        }

        @Test
        @DisplayName("support received with ticket id → ref subject branch + delivered")
        void supportWithTicket() throws Exception {
            when(templates.supportReceived(NAME, "T-1", "Help")).thenReturn("<html>");

            service.sendSupportReceivedEmail(TO, NAME, "T-1", "Help");

            verify(templates).supportReceived(NAME, "T-1", "Help");
            verify(http).send(any(), any());
        }

        @Test
        @DisplayName("support received without ticket id → generic subject branch + delivered")
        void supportWithoutTicket() throws Exception {
            when(templates.supportReceived(NAME, null, "Help")).thenReturn("<html>");

            service.sendSupportReceivedEmail(TO, NAME, null, "Help");

            verify(templates).supportReceived(NAME, null, "Help");
            verify(http).send(any(), any());
        }

        @Test
        @DisplayName("announcement → templates.announcement + delivered (verification-exempt)")
        void announcement() {
            when(templates.announcement(NAME, "News", "<p>hi</p>", "Read", "https://x/read"))
                    .thenReturn("<html>");

            service.sendAnnouncementEmail(TO, NAME, "News", "<p>hi</p>", "Read", "https://x/read");

            verify(templates).announcement(NAME, "News", "<p>hi</p>", "Read", "https://x/read");
        }

        @Test
        @DisplayName("raw html → delivered with the caller's html, no template call")
        void rawHtml() throws Exception {
            service.sendHtmlEmail(TO, NAME, "Subject", "<h1>raw</h1>");

            verify(http).send(any(), any());
        }
    }

    // ── branch backfill (uncovered negative / edge branches) ────────────────────────

    @Nested
    @DisplayName("branch backfill")
    class BranchBackfill {

        @Test
        @DisplayName("maskEmail covers null / blank / no-@ / short-local recipients (disabled-log path)")
        void maskEmailEdgeCases() throws Exception {
            // Mail disabled routes every reset call through log.warn("... {}", maskEmail(toEmail)),
            // which is evaluated eagerly, so each recipient shape exercises a maskEmail branch.
            ReflectionTestUtils.setField(service, "mailEnabled", false);

            service.sendPasswordResetEmail(null, NAME, "https://x/reset", 30);        // email == null
            service.sendPasswordResetEmail("", NAME, "https://x/reset", 30);           // isBlank
            service.sendPasswordResetEmail("noatsign", NAME, "https://x/reset", 30);   // indexOf('@') <= 0
            service.sendPasswordResetEmail("a@x.com", NAME, "https://x/reset", 30);    // local.length() <= 2

            verify(http, never()).send(any(), any());
            verifyNoInteractions(templates);
        }

        @Test
        @DisplayName("SMTP-only config (no HTTP keys) → deliveryConfigured via smtpAvailable, SMTP sends")
        void smtpOnlyDelivers() throws Exception {
            ReflectionTestUtils.setField(service, "requireVerification", false);
            withSmtp(); // smtpEnabled=true + provider returns a mail sender

            service.sendHtmlEmail(TO, NAME, "Subject", "<html>");

            verify(mailSender).send(any(MimeMessage.class));
            verify(http, never()).send(any(), any());
        }

        @Test
        @DisplayName("SMTP enabled but no mail-sender bean → smtpAvailable false, delivery not configured")
        void smtpEnabledButNoBeanIsNoop() throws Exception {
            ReflectionTestUtils.setField(service, "smtpEnabled", true);
            // mailSenderProvider.getIfAvailable() returns null (unstubbed mock default)

            service.sendHtmlEmail(TO, NAME, "Subject", "<html>");

            verify(http, never()).send(any(), any());
            verify(mailSender, never()).send(any(MimeMessage.class));
        }

        @Test
        @DisplayName("Brevo sender with an empty display name (from='<addr>') omits the name field")
        void brevoEmptyDisplayName() throws Exception {
            ReflectionTestUtils.setField(service, "from", "  <noreply@neochathub.com>");
            ReflectionTestUtils.setField(service, "requireVerification", false);
            withBrevo();
            stubByHost(200, 200);

            service.sendHtmlEmail(TO, NAME, "Subject", "<html>");

            ArgumentCaptor<HttpRequest> req = ArgumentCaptor.forClass(HttpRequest.class);
            verify(http).send(req.capture(), any());
            assertThat(req.getValue().uri().getHost()).isEqualTo(BREVO_HOST);
        }

        @Test
        @DisplayName("Brevo recipient with a null name omits the recipient name field")
        void brevoNullRecipientName() throws Exception {
            ReflectionTestUtils.setField(service, "requireVerification", false);
            withBrevo();
            stubByHost(200, 200);

            service.sendHtmlEmail(TO, null, "Subject", "<html>");

            verify(http).send(any(), any());
        }

        @Test
        @DisplayName("provider error with a null response body → abbreviate returns empty, no throw")
        void nullResponseBodyAbbreviated() throws Exception {
            withResend();
            @SuppressWarnings("unchecked")
            HttpResponse<String> resp = mock(HttpResponse.class);
            when(resp.statusCode()).thenReturn(500);
            when(resp.body()).thenReturn(null);
            stubSend(resp);
            when(templates.passwordReset(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong()))
                    .thenReturn("<html>");

            assertThatCode(() -> service.sendPasswordResetEmail(TO, NAME, "https://x/reset", 30))
                    .doesNotThrowAnyException();
            verify(http).send(any(), any());
        }

        @Test
        @DisplayName("provider error with a >300 char body → abbreviate truncates, no throw")
        void longResponseBodyAbbreviated() throws Exception {
            withResend();
            @SuppressWarnings("unchecked")
            HttpResponse<String> resp = mock(HttpResponse.class);
            when(resp.statusCode()).thenReturn(500);
            when(resp.body()).thenReturn("x".repeat(301));
            stubSend(resp);
            when(templates.passwordReset(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong()))
                    .thenReturn("<html>");

            assertThatCode(() -> service.sendPasswordResetEmail(TO, NAME, "https://x/reset", 30))
                    .doesNotThrowAnyException();
            verify(http).send(any(), any());
        }

        @Test
        @DisplayName("support ack with a blank ticket id → generic subject branch")
        void supportBlankTicket() throws Exception {
            withResend();
            ReflectionTestUtils.setField(service, "requireVerification", false);
            stubSend(response(200));
            when(templates.supportReceived(NAME, "   ", "Help")).thenReturn("<html>");

            service.sendSupportReceivedEmail(TO, NAME, "   ", "Help");

            verify(templates).supportReceived(NAME, "   ", "Help");
            verify(http).send(any(), any());
        }

        @Test
        @DisplayName("mail disabled → unread/password-changed/support/announcement/raw/login/welcome/verify all no-op")
        void disabledSendersAreNoops() throws Exception {
            ReflectionTestUtils.setField(service, "mailEnabled", false);

            service.sendUnreadMessagesEmail(TO, NAME, List.of(), 2, "https://x/open");
            service.sendPasswordChangedEmail(TO, NAME);
            service.sendSupportReceivedEmail(TO, NAME, "T-1", "Help");
            service.sendAnnouncementEmail(TO, NAME, "News", "<p>hi</p>", "Read", "https://x/read");
            service.sendHtmlEmail(TO, NAME, "Subject", "<html>");
            service.sendLoginAlertEmail(TO, NAME, "iPhone", "NY", "1.2.3.4", "now", "https://x/secure");
            service.sendWelcomeEmail(TO, NAME, "https://x/open");
            service.sendVerificationEmail(TO, NAME, "https://x/verify", 30);

            verifyNoInteractions(templates);
            verify(http, never()).send(any(), any());
        }

        @Test
        @DisplayName("verification lookup with a null address trims to empty (defensive null-guard)")
        void isVerifiedRecipientNullAddress() {
            when(userRepository.findByEmailIgnoreCase("")).thenReturn(Optional.empty());

            Boolean result = ReflectionTestUtils.invokeMethod(service, "isVerifiedRecipient", (String) null);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("releasing a quota slot swallows a Redis decrement error")
        void releaseSlotSwallowsRedisError() throws Exception {
            withResend();
            when(redisProvider.getIfAvailable()).thenReturn(redis);
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.increment(anyString())).thenReturn(1L);
            when(valueOps.decrement(anyString())).thenThrow(new RuntimeException("redis down"));
            stubSend(response(500)); // Resend rejects → the reserved slot is released
            when(templates.passwordReset(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong()))
                    .thenReturn("<html>");

            assertThatCode(() -> service.sendPasswordResetEmail(TO, NAME, "https://x/reset", 30))
                    .doesNotThrowAnyException();
            verify(valueOps).decrement(anyString());
        }
    }
}
