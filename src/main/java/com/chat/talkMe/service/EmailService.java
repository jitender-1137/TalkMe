package com.chat.talkMe.service;

/** Transactional email delivery (password reset, etc.). */
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
}
