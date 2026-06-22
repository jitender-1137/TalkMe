package com.chat.talkMe.service.impl;

import com.chat.talkMe.service.EmailService;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * SMTP-backed email sender.
 *
 * <p>Resilient by design: the {@link JavaMailSender} is injected via
 * {@link ObjectProvider} so the app boots even when no SMTP bean is configured,
 * and {@code app.mail.enabled} gates real delivery. When mail is disabled or
 * unavailable, the reset link is logged (dev) rather than throwing — a failed
 * email must never break the password-reset request flow. Sends run {@code @Async}
 * so the HTTP request returns immediately and timing can't be used to probe which
 * emails exist.</p>
 */
@Slf4j
@Service
public class EmailServiceImpl implements EmailService {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    @Value("${app.mail.enabled:false}")
    private boolean mailEnabled;

    @Value("${app.mail.from:TalkMe <noreply@talkme.app>}")
    private String from;

    public EmailServiceImpl(ObjectProvider<JavaMailSender> mailSenderProvider) {
        this.mailSenderProvider = mailSenderProvider;
    }

    @Async
    @Override
    public void sendPasswordResetEmail(String toEmail, String recipientName, String resetLink, long expiryMinutes) {
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (!mailEnabled || mailSender == null) {
            // Dev / unconfigured: never fail the flow — surface the link in logs only.
            log.warn("[Mail] disabled or unconfigured — password reset link for {} (valid {}m): {}",
                    toEmail, expiryMinutes, resetLink);
            return;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(toEmail);
            helper.setSubject("Reset your TalkMe password");
            helper.setText(buildHtml(recipientName, resetLink, expiryMinutes), true);
            mailSender.send(message);
            log.info("[Mail] Password reset email sent to {}", toEmail);
        } catch (Exception e) {
            // Best-effort: log and swallow so the caller (and the user) aren't exposed
            // to mail-server failures.
            log.error("[Mail] Failed to send password reset email to {}: {}", toEmail, e.getMessage());
        }
    }

    private String buildHtml(String name, String link, long expiryMinutes) {
        String safeName = name == null || name.isBlank() ? "there" : name;
        return """
                <div style="font-family:Arial,Helvetica,sans-serif;max-width:480px;margin:0 auto;padding:24px;color:#111">
                  <h2 style="margin:0 0 12px">Reset your password</h2>
                  <p>Hi %s,</p>
                  <p>We received a request to reset your TalkMe password. Click the button below to choose a new one. This link expires in %d minutes.</p>
                  <p style="text-align:center;margin:28px 0">
                    <a href="%s" style="background:#22c55e;color:#fff;text-decoration:none;padding:12px 24px;border-radius:10px;font-weight:600;display:inline-block">Reset password</a>
                  </p>
                  <p style="font-size:13px;color:#555">If the button doesn't work, paste this link into your browser:<br><a href="%s">%s</a></p>
                  <p style="font-size:13px;color:#555">If you didn't request this, you can safely ignore this email — your password won't change.</p>
                  <hr style="border:none;border-top:1px solid #eee;margin:24px 0">
                  <p style="font-size:12px;color:#999">© 2026 TalkMe. All rights reserved.</p>
                </div>
                """
                .formatted(safeName, expiryMinutes, link, link, link);
    }
}
