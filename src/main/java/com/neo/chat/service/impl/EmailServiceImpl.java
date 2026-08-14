package com.neo.chat.service.impl;

import com.neo.chat.domain.User;
import com.neo.chat.dto.EmailUnreadPreview;
import com.neo.chat.repository.UserRepository;
import com.neo.chat.service.EmailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Transactional email sender with a three-link <b>primary → fallback</b> chain:
 * <ul>
 *   <li><b>Primary:</b> Resend HTTP API ({@code api.resend.com}) — free tier ~100 mails/day.</li>
 *   <li><b>Fallback 1:</b> Brevo HTTP API ({@code api.brevo.com}) — free tier ~300 mails/day.</li>
 *   <li><b>Fallback 2:</b> SMTP via {@link JavaMailSender} ({@code spring.mail.*}) — the
 *       last resort when both HTTP providers fail or are out of quota.</li>
 * </ul>
 *
 * <p><b>Quota-aware routing:</b> a per-provider daily counter in Redis
 * ({@code mail:quota:<provider>:<utc-date>}) reserves a send slot BEFORE calling the HTTP
 * providers, so once Resend's free-tier budget is spent the chain proactively moves to
 * Brevo (then SMTP) instead of burning requests into 429s. If Redis is unavailable the
 * counter is skipped (never block mail on cache health) and the provider's own rate-limit
 * response triggers the fallback anyway. SMTP is not quota-counted — it's the backstop.</p>
 *
 * <p>Resilient by design: {@code app.mail.enabled} gates real delivery; the
 * {@link JavaMailSender} is injected via {@link ObjectProvider} so the app boots even when
 * no SMTP bean is configured. When disabled or no provider is configured, the
 * password-reset link is logged (dev) rather than throwing — a failed email must never
 * break the password-reset request flow. Sends run {@code @Async} so the HTTP request
 * returns immediately and timing can't be used to probe which emails exist.</p>
 */
@Slf4j
@Service
public class EmailServiceImpl implements EmailService {

    private static final String RESEND_ENDPOINT = "https://api.resend.com/emails";
    private static final String BREVO_ENDPOINT = "https://api.brevo.com/v3/smtp/email";

    /**
     * Parses {@code Display Name <mailbox@domain>} into name + address.
     */
    private static final Pattern FROM_PATTERN = Pattern.compile("^\\s*(.*?)\\s*<\\s*(.+?)\\s*>\\s*$");

    private final ObjectMapper objectMapper;
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final EmailTemplates templates;
    private final UserRepository userRepository;
    private final DisposableEmailDomains disposableDomains;

    /**
     * Delivery policy per email type: only TRANSACTIONAL requires a verified recipient.
     */
    private enum MailCategory {
        VERIFICATION,     // the verify link itself — must reach unverified users
        WELCOME,          // sent right after verifying — recipient is already verified
        PASSWORD_RESET,   // critical — must work for unverified users (account recovery)
        ANNOUNCEMENT,     // product news — exempt from the verification gate (by request)
        TRANSACTIONAL     // login alerts, unread digests, password-changed, support — VERIFIED only
    }

    @Value("${app.mail.enabled:false}")
    private boolean mailEnabled;

    /**
     * When true, TRANSACTIONAL emails are only sent to verified addresses.
     */
    @Value("${app.mail.require-verification:true}")
    private boolean requireVerification;

    @Value("${app.mail.from:NeoChatHub <noreply@neochathub.com>}")
    private String from;

    @Value("${app.mail.timeout-ms:10000}")
    private long timeoutMs;

    // Primary provider: Resend.
    @Value("${app.mail.resend.api-key:}")
    private String resendApiKey;
    @Value("${app.mail.resend.daily-limit:100}")
    private int resendDailyLimit;

    // Fallback 1: Brevo. Used when Resend is out of quota or errors.
    @Value("${app.mail.brevo.api-key:}")
    private String brevoApiKey;
    @Value("${app.mail.brevo.daily-limit:300}")
    private int brevoDailyLimit;

    // Fallback 2: SMTP. Last resort when both HTTP providers fail.
    @Value("${app.mail.smtp.enabled:false}")
    private boolean smtpEnabled;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public EmailServiceImpl(ObjectMapper objectMapper,
                            ObjectProvider<StringRedisTemplate> redisProvider,
                            ObjectProvider<JavaMailSender> mailSenderProvider,
                            EmailTemplates templates,
                            UserRepository userRepository,
                            DisposableEmailDomains disposableDomains) {
        this.objectMapper = objectMapper;
        this.redisProvider = redisProvider;
        this.mailSenderProvider = mailSenderProvider;
        this.templates = templates;
        this.userRepository = userRepository;
        this.disposableDomains = disposableDomains;
    }

    /**
     * Asynchronously sends the password-reset email (PASSWORD_RESET — delivered even to unverified
     * addresses). When mail is disabled/unconfigured the flow never fails: the recipient is masked at WARN
     * and the live link is emitted only at DEBUG.
     *
     * @param toEmail       recipient address
     * @param recipientName display name for the greeting
     * @param resetLink     fully-qualified reset URL (token embedded)
     * @param expiryMinutes minutes the link stays valid (shown to the user)
     */
    @Async
    @Override
    public void sendPasswordResetEmail(String toEmail, String recipientName, String resetLink, long expiryMinutes) {
        if (!deliveryConfigured()) {
            // Never fail the flow. The link carries a live token, so keep it out of
            // prod-level logs — WARN masks the recipient and omits the token; the full
            // link is emitted only at DEBUG for local development.
            log.warn("[Mail] disabled or unconfigured — password reset link for {} (valid {}m) not sent",
                    maskEmail(toEmail), expiryMinutes);
            log.debug("[Mail] password reset link: {}", resetLink);
            return;
        }
        deliver(toEmail, recipientName, "Reset your NeoChatHub password",
                templates.passwordReset(recipientName, resetLink, expiryMinutes), MailCategory.PASSWORD_RESET);
    }

    /**
     * Asynchronously sends the onboarding welcome email (WELCOME category); no-op when mail is
     * disabled/unconfigured.
     *
     * @param toEmail       recipient address
     * @param recipientName display name for the greeting
     * @param openLink      deep link into the app
     */
    @Async
    @Override
    public void sendWelcomeEmail(String toEmail, String recipientName, String openLink) {
        if (!deliveryConfigured()) {
            log.warn("[Mail] disabled or unconfigured — skipping welcome email to {}", toEmail);
            return;
        }
        deliver(toEmail, recipientName, "Welcome to NeoChatHub 🎉",
                templates.welcome(recipientName, openLink), MailCategory.WELCOME);
    }

    /**
     * Asynchronously sends the email-verification message (VERIFICATION — must reach unverified users).
     * When mail is disabled/unconfigured the link is logged at DEBUG rather than sent.
     *
     * @param toEmail       recipient address
     * @param recipientName display name for the greeting
     * @param verifyLink    fully-qualified verification URL
     * @param expiryMinutes minutes the link stays valid
     */
    @Async
    @Override
    public void sendVerificationEmail(String toEmail, String recipientName, String verifyLink, long expiryMinutes) {
        if (!deliveryConfigured()) {
            log.warn("[Mail] disabled or unconfigured — verification link for {} (valid {}m) not sent",
                    maskEmail(toEmail), expiryMinutes);
            log.debug("[Mail] verification link: {}", verifyLink);
            return;
        }
        deliver(toEmail, recipientName, "Verify your email for NeoChatHub",
                templates.verifyEmail(recipientName, verifyLink, expiryMinutes), MailCategory.VERIFICATION);
    }

    /**
     * Asynchronously sends the unread-messages digest (TRANSACTIONAL — verified recipients only). Subject
     * is singular/plural by {@code totalUnread}; no-op when mail is disabled/unconfigured.
     *
     * @param toEmail       recipient address
     * @param recipientName display name for the greeting
     * @param previews      per-conversation preview rows
     * @param totalUnread   total unread count (drives subject/heading)
     * @param openLink      deep link into the app
     */
    @Async
    @Override
    public void sendUnreadMessagesEmail(String toEmail, String recipientName,
                                        List<EmailUnreadPreview> previews, int totalUnread, String openLink) {
        if (!deliveryConfigured()) {
            log.warn("[Mail] disabled or unconfigured — skipping unread digest to {}", toEmail);
            return;
        }
        String subject = totalUnread == 1
                ? "You have a new message on NeoChatHub"
                : "You have " + totalUnread + " new messages on NeoChatHub";
        deliver(toEmail, recipientName, subject,
                templates.unreadMessages(recipientName, previews, totalUnread, openLink), MailCategory.TRANSACTIONAL);
    }

    /**
     * Asynchronously sends the password-changed security notice (TRANSACTIONAL — verified recipients only);
     * no-op when mail is disabled/unconfigured.
     *
     * @param toEmail       recipient address
     * @param recipientName display name for the greeting
     */
    @Async
    @Override
    public void sendPasswordChangedEmail(String toEmail, String recipientName) {
        if (!deliveryConfigured()) {
            log.warn("[Mail] disabled or unconfigured — skipping password-changed notice to {}", toEmail);
            return;
        }
        deliver(toEmail, recipientName, "Your NeoChatHub password was changed",
                templates.passwordChanged(recipientName), MailCategory.TRANSACTIONAL);
    }

    /**
     * Asynchronously sends the new-sign-in alert (TRANSACTIONAL — verified recipients only); no-op when
     * mail is disabled/unconfigured. Device/location/when may be blank and are shown only when present.
     *
     * @param toEmail       recipient address
     * @param recipientName display name for the greeting
     * @param device        the signing-in device description
     * @param location      the approximate location
     * @param ip            the source IP
     * @param when          the sign-in time (human-readable)
     * @param secureLink    link to secure the account
     */
    @Async
    @Override
    public void sendLoginAlertEmail(String toEmail, String recipientName, String device,
                                    String location, String ip, String when, String secureLink) {
        if (!deliveryConfigured()) {
            log.warn("[Mail] disabled or unconfigured — skipping login alert to {}", toEmail);
            return;
        }
        deliver(toEmail, recipientName, "New sign-in to your NeoChatHub account",
                templates.loginAlert(recipientName, device, location, ip, when, secureLink), MailCategory.TRANSACTIONAL);
    }

    /**
     * Asynchronously sends the support-request acknowledgement (TRANSACTIONAL — verified recipients only);
     * no-op when mail is disabled/unconfigured. Subject includes the ticket reference when present.
     *
     * @param toEmail       recipient address
     * @param recipientName display name for the greeting
     * @param ticketId      the support ticket reference (optional)
     * @param subjectLine   the user's original subject line
     */
    @Async
    @Override
    public void sendSupportReceivedEmail(String toEmail, String recipientName, String ticketId, String subjectLine) {
        if (!deliveryConfigured()) {
            log.warn("[Mail] disabled or unconfigured — skipping support ack to {}", toEmail);
            return;
        }
        String subject = ticketId == null || ticketId.isBlank()
                ? "We've received your NeoChatHub support request"
                : "We've received your request (#" + ticketId + ")";
        deliver(toEmail, recipientName, subject,
                templates.supportReceived(recipientName, ticketId, subjectLine), MailCategory.TRANSACTIONAL);
    }

    /**
     * Asynchronously sends a product announcement (ANNOUNCEMENT — exempt from the verification gate); no-op
     * when mail is disabled/unconfigured. CTA button is omitted when label/link are blank.
     *
     * @param toEmail       recipient address
     * @param recipientName display name for the greeting
     * @param heading       the announcement heading (also the subject)
     * @param bodyHtml      caller-built HTML body
     * @param ctaLabel      optional call-to-action label
     * @param ctaLink       optional call-to-action URL
     */
    @Async
    @Override
    public void sendAnnouncementEmail(String toEmail, String recipientName, String heading,
                                      String bodyHtml, String ctaLabel, String ctaLink) {
        if (!deliveryConfigured()) {
            log.warn("[Mail] disabled or unconfigured — skipping announcement '{}' to {}", heading, toEmail);
            return;
        }
        deliver(toEmail, recipientName, heading,
                templates.announcement(recipientName, heading, bodyHtml, ctaLabel, ctaLink), MailCategory.ANNOUNCEMENT);
    }

    /**
     * Asynchronously sends an arbitrary pre-rendered HTML email (TRANSACTIONAL — verified recipients only);
     * no-op when mail is disabled/unconfigured.
     *
     * @param toEmail recipient address
     * @param toName  recipient display name
     * @param subject the email subject
     * @param html    the full HTML body
     */
    @Async
    @Override
    public void sendHtmlEmail(String toEmail, String toName, String subject, String html) {
        if (!deliveryConfigured()) {
            log.warn("[Mail] disabled or unconfigured — dropping email to {} (subject: {})", toEmail, subject);
            return;
        }
        deliver(toEmail, toName, subject, html, MailCategory.TRANSACTIONAL);
    }

    /**
     * Masks an email for logs: "jane.doe@example.com" → "ja***@example.com".
     */
    private static String maskEmail(String email) {
        if (email == null || email.isBlank()) return "(none)";
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        String local = email.substring(0, at);
        String domain = email.substring(at);
        String head = local.length() <= 2 ? local.substring(0, 1) : local.substring(0, 2);
        return head + "***" + domain;
    }

    /**
     * Whether real delivery is possible: mail enabled and at least one provider (Resend, Brevo or SMTP)
     * available.
     *
     * @return true when mail can actually be sent
     */
    private boolean deliveryConfigured() {
        if (!mailEnabled) {
            return false;
        }
        return hasText(resendApiKey) || hasText(brevoApiKey) || smtpAvailable();
    }

    /**
     * Whether SMTP is enabled and a {@link JavaMailSender} bean is present.
     *
     * @return true when the SMTP fallback can be used
     */
    private boolean smtpAvailable() {
        return smtpEnabled && mailSenderProvider.getIfAvailable() != null;
    }

    /**
     * Central gate: decide whether this recipient should receive this category of email.
     * Blocks (a) disposable/temporary addresses entirely — even if verified — and
     * (b) TRANSACTIONAL email to unverified addresses. Keeps provider quota from being
     * spent on mail nobody should get.
     */
    private boolean deliverable(String toEmail, MailCategory category) {
        if (!hasText(toEmail)) {
            return false;
        }
        if (disposableDomains.isDisposable(toEmail)) {
            log.info("[Mail] suppressed {} to {} — disposable/temporary address", category, toEmail);
            return false;
        }
        if (requireVerification && category == MailCategory.TRANSACTIONAL && !isVerifiedRecipient(toEmail)) {
            log.info("[Mail] suppressed {} to {} — email not verified", category, toEmail);
            return false;
        }
        return true;
    }

    /**
     * Whether the account behind this address exists and has a verified email.
     */
    private boolean isVerifiedRecipient(String toEmail) {
        try {
            return userRepository.findByEmailIgnoreCase(toEmail == null ? "" : toEmail.trim())
                    .map(User::isVerified).orElse(false);
        } catch (Exception e) {
            // On lookup error, fail closed for transactional mail (don't spend quota blindly).
            log.debug("[Mail] verification lookup failed for {}: {}", toEmail, e.getMessage());
            return false;
        }
    }

    /**
     * Walks the provider chain; best-effort — logs and swallows total failure.
     */
    private void deliver(String toEmail, String toName, String subject, String html, MailCategory category) {
        if (!deliverable(toEmail, category)) {
            return;
        }
        if (attemptHttp("resend", resendApiKey, resendDailyLimit,
                () -> sendViaResend(toEmail, subject, html))) {
            log.info("[Mail] '{}' sent to {} via Resend", subject, toEmail);
            return;
        }
        if (attemptHttp("brevo", brevoApiKey, brevoDailyLimit,
                () -> sendViaBrevo(toEmail, toName, subject, html))) {
            log.info("[Mail] '{}' sent to {} via Brevo (fallback)", subject, toEmail);
            return;
        }
        if (attemptSmtp(toEmail, subject, html)) {
            log.info("[Mail] '{}' sent to {} via SMTP (fallback)", subject, toEmail);
            return;
        }
        log.error("[Mail] All providers failed or out of quota — email to {} (subject: {}) NOT sent",
                toEmail, subject);
    }

    /**
     * Reserves a quota slot, runs an HTTP send, and returns the slot on failure so a
     * transient error doesn't permanently eat daily budget.
     */
    private boolean attemptHttp(String provider, String apiKey, int dailyLimit, HttpCall call) {
        if (!hasText(apiKey)) {
            return false;
        }
        if (!reserveQuotaSlot(provider, dailyLimit)) {
            log.warn("[Mail] {} daily quota ({}) exhausted — skipping", provider, dailyLimit);
            return false;
        }
        try {
            HttpResponse<String> response = call.execute();
            if (response.statusCode() / 100 == 2) {
                return true;
            }
            log.warn("[Mail] {} rejected send (HTTP {}): {}", provider, response.statusCode(),
                    abbreviate(response.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[Mail] {} send interrupted", provider);
        } catch (Exception e) {
            log.warn("[Mail] {} send failed: {}", provider, e.getMessage());
        }
        releaseQuotaSlot(provider);
        return false;
    }

    // ── HTTP providers ───────────────────────────────────────────────────────

    /**
     * Sends one email through the Resend HTTP API.
     *
     * @param toEmail recipient address
     * @param subject the subject
     * @param html    the HTML body
     * @return the raw HTTP response
     * @throws Exception on transport failure or interruption
     */
    private HttpResponse<String> sendViaResend(String toEmail, String subject, String html) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("from", from);
        body.putArray("to").add(toEmail);
        body.put("subject", subject);
        body.put("html", html);

        HttpRequest request = HttpRequest.newBuilder(URI.create(RESEND_ENDPOINT))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Authorization", "Bearer " + resendApiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Sends one email through the Brevo HTTP API, parsing the configured {@code from} into sender
     * name/email.
     *
     * @param toEmail recipient address
     * @param toName  recipient display name (optional)
     * @param subject the subject
     * @param html    the HTML body
     * @return the raw HTTP response
     * @throws Exception on transport failure or interruption
     */
    private HttpResponse<String> sendViaBrevo(String toEmail, String toName, String subject, String html)
            throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode sender = body.putObject("sender");
        Matcher m = FROM_PATTERN.matcher(from);
        if (m.matches()) {
            if (!m.group(1).isBlank()) {
                sender.put("name", m.group(1));
            }
            sender.put("email", m.group(2));
        } else {
            sender.put("email", from.trim());
        }
        ArrayNode to = body.putArray("to");
        ObjectNode recipient = to.addObject();
        recipient.put("email", toEmail);
        if (hasText(toName)) {
            recipient.put("name", toName);
        }
        body.put("subject", subject);
        body.put("htmlContent", html);

        HttpRequest request = HttpRequest.newBuilder(URI.create(BREVO_ENDPOINT))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("api-key", brevoApiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    // ── SMTP fallback ──────────────────────────────────────────────────────────

    /**
     * Last-resort SMTP send via {@link JavaMailSender}; returns false (logged, swallowed) when SMTP is
     * unavailable or the send fails.
     *
     * @param toEmail recipient address
     * @param subject the subject
     * @param html    the HTML body
     * @return true when the message was handed to the mail sender successfully
     */
    private boolean attemptSmtp(String toEmail, String subject, String html) {
        JavaMailSender mailSender = smtpEnabled ? mailSenderProvider.getIfAvailable() : null;
        if (mailSender == null) {
            return false;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
            return true;
        } catch (Exception e) {
            log.warn("[Mail] SMTP send failed: {}", e.getMessage());
            return false;
        }
    }

    // ── Daily quota (Redis) ──────────────────────────────────────────────────

    /**
     * Atomically claims one of today's send slots. Providers reset daily in UTC, so the
     * key is date-stamped in UTC and expires shortly after the day rolls over. Redis
     * being down means "assume allowed" — the provider's own 429 still triggers fallback.
     */
    private boolean reserveQuotaSlot(String provider, int dailyLimit) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return true;
        }
        try {
            String key = quotaKey(provider);
            Long used = redis.opsForValue().increment(key);
            if (used != null && used == 1L) {
                redis.expire(key, Duration.ofHours(26));
            }
            return used == null || used <= dailyLimit;
        } catch (Exception e) {
            log.debug("[Mail] quota check unavailable ({}), allowing to send", e.getMessage());
            return true;
        }
    }

    /**
     * Returns a previously-reserved daily quota slot after a failed send; a transient Redis error losing
     * one slot is acceptable and ignored.
     *
     * @param provider the provider whose slot to release
     */
    private void releaseQuotaSlot(String provider) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            redis.opsForValue().decrement(quotaKey(provider));
        } catch (Exception ignored) {
            // Losing one slot to a transient Redis error is acceptable.
        }
    }

    private String quotaKey(String provider) {
        return "mail:quota:" + provider + ":" + LocalDate.now(ZoneOffset.UTC);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface HttpCall {
        HttpResponse<String> execute() throws Exception;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 300 ? body.substring(0, 300) + "…" : body;
    }
}
