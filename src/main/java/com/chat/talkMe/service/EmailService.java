package com.chat.talkMe.service;

import com.chat.talkMe.dto.EmailUnreadPreview;

import java.util.List;

/** Transactional email delivery (welcome, verification, password reset, digests, support, etc.). */
public interface EmailService {

    /**
     * Send a password-reset email containing a one-time reset link.
     *
     * @param toEmail        recipient address
     * @param recipientName  display name for the greeting
     * @param resetLink      fully-qualified reset URL (token embedded)
     * @param expiryMinutes  how long the link stays valid (shown to the user)
     */
    void sendPasswordResetEmail(String toEmail, String recipientName, String resetLink, long expiryMinutes);

    /** Onboarding welcome, sent once after signup. {@code openLink} deep-links into the app. */
    void sendWelcomeEmail(String toEmail, String recipientName, String openLink);

    /** Email-address verification with a confirm link. */
    void sendVerificationEmail(String toEmail, String recipientName, String verifyLink, long expiryMinutes);

    /**
     * LinkedIn-style "you have unread messages" digest.
     *
     * @param previews    per-sender preview rows (rendered; the template caps how many are shown)
     * @param totalUnread total unread count across all conversations (drives the headline)
     * @param openLink    deep-link to open the inbox
     */
    void sendUnreadMessagesEmail(String toEmail, String recipientName,
                                 List<EmailUnreadPreview> previews, int totalUnread, String openLink);

    /** Security confirmation sent after a successful password change. */
    void sendPasswordChangedEmail(String toEmail, String recipientName);

    /**
     * New-sign-in security alert. {@code device}, {@code location}, {@code ip} and
     * {@code when} may each be null/blank and are simply omitted from the email when absent.
     *
     * @param secureLink deep-link to the change-password / security screen
     */
    void sendLoginAlertEmail(String toEmail, String recipientName, String device,
                             String location, String ip, String when, String secureLink);

    /** Acknowledgement that a support request was received. */
    void sendSupportReceivedEmail(String toEmail, String recipientName, String ticketId, String subjectLine);

    /**
     * Generic informational / announcement email. {@code bodyHtml} is trusted, pre-formatted
     * content; {@code ctaLabel}/{@code ctaLink} are optional (pass null/blank to omit the button).
     */
    void sendAnnouncementEmail(String toEmail, String recipientName, String heading,
                               String bodyHtml, String ctaLabel, String ctaLink);

    /**
     * Send an arbitrary transactional HTML email through the provider chain
     * (Resend → Brevo → SMTP). Best-effort: failures are logged, never thrown.
     *
     * @param toEmail recipient address
     * @param toName  recipient display name (may be null/blank)
     * @param subject email subject
     * @param html    full HTML body
     */
    void sendHtmlEmail(String toEmail, String toName, String subject, String html);
}
