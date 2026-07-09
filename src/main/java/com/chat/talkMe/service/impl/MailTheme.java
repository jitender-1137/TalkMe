package com.chat.talkMe.service.impl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Single source of truth for every colour, gradient and font used in transactional email.
 *
 * <p>{@link EmailTemplates} reads all of its visual tokens from this one object, so the entire
 * email palette — header, button, links, text, avatars — is tuned in one place (and can be
 * overridden per environment via {@code app.mail.theme.*} without touching markup).</p>
 */
@Component
public class MailTheme {

    /** Buttons + solid header fallback (deep emerald). */
    @Value("${app.mail.theme.primary:#047857}")
    private String primary;

    /** Header gradient start (deep emerald). */
    @Value("${app.mail.theme.header-from:#064e3b}")
    private String headerFrom;

    /** Header gradient end (teal). */
    @Value("${app.mail.theme.header-to:#0f766e}")
    private String headerTo;

    /** Links, checkmarks, chips (calm teal). */
    @Value("${app.mail.theme.accent:#0f766e}")
    private String accent;

    /** Headings / strong text. */
    @Value("${app.mail.theme.ink:#0f172a}")
    private String ink;

    /** Body copy. */
    @Value("${app.mail.theme.body:#334155}")
    private String body;

    /** Secondary / muted copy. */
    @Value("${app.mail.theme.muted:#64748b}")
    private String muted;

    /** Page background behind the card. */
    @Value("${app.mail.theme.page-bg:#eef2f6}")
    private String pageBg;

    /** Card + divider borders. */
    @Value("${app.mail.theme.card-border:#e6ebf1}")
    private String cardBorder;

    /** Footer background. */
    @Value("${app.mail.theme.footer-bg:#f8fafc}")
    private String footerBg;

    /** Footer text. */
    @Value("${app.mail.theme.footer-text:#94a3b8}")
    private String footerText;

    /** Font stack for the whole email. */
    @Value("${app.mail.theme.font:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif}")
    private String font;

    /** Palette for initial-circle avatars when no photo URL is available (comma-separated hex). */
    @Value("${app.mail.theme.avatar-colors:#6366f1,#0ea5e9,#0d9488,#f59e0b,#e11d48,#8b5cf6,#db2777,#0f766e}")
    private String avatarColorsCsv;

    public String primary() {
        return primary;
    }

    public String headerFrom() {
        return headerFrom;
    }

    public String headerTo() {
        return headerTo;
    }

    public String accent() {
        return accent;
    }

    public String ink() {
        return ink;
    }

    public String body() {
        return body;
    }

    public String muted() {
        return muted;
    }

    public String pageBg() {
        return pageBg;
    }

    public String cardBorder() {
        return cardBorder;
    }

    public String footerBg() {
        return footerBg;
    }

    public String footerText() {
        return footerText;
    }

    public String font() {
        return font;
    }

    /** Pick a stable avatar colour for a name. */
    public String avatarColor(String seed) {
        String[] colors = avatarColors();
        int idx = Math.floorMod(seed == null ? 0 : seed.hashCode(), colors.length);
        return colors[idx];
    }

    private String[] avatarColors() {
        return Arrays.stream(avatarColorsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }
}
