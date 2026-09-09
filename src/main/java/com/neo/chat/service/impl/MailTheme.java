package com.neo.chat.service.impl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Single source of truth for every color, gradient and font used in transactional email.
 *
 * <p>{@link EmailTemplates} reads all of its visual tokens from this one object, so the entire
 * email palette — header, button, links, text, avatars — is tuned in one place (and can be
 * overridden per environment via {@code app.mail.theme.*} without touching markup).</p>
 */
@Component
public class MailTheme {

    /**
     * Buttons + solid header fallback (deep emerald).
     */
    private final String primary;

    /**
     * Header gradient start (deep emerald).
     */
    private final String headerFrom;

    /**
     * Header gradient end (teal).
     */
    private final String headerTo;

    /**
     * Links, checkmarks, chips (calm teal).
     */
    private final String accent;

    /**
     * Headings / strong text.
     */
    private final String ink;

    /**
     * Body copy.
     */
    private final String body;

    /**
     * Secondary / muted copy.
     */
    private final String muted;

    /**
     * Page background behind the card.
     */
    private final String pageBg;

    /**
     * Card + divider borders.
     */
    private final String cardBorder;

    /**
     * Footer background.
     */
    private final String footerBg;

    /**
     * Footer text.
     */
    private final String footerText;

    /**
     * Font stack for the whole email.
     */
    private final String font;

    /**
     * Palette for initial-circle avatars when no photo URL is available (comma-separated hex).
     */
    private final String avatarColorsCsv;

    public MailTheme(@Value("${app.mail.theme.primary:#047857}") String primary,
                     @Value("${app.mail.theme.header-from:#064e3b}") String headerFrom,
                     @Value("${app.mail.theme.header-to:#0f766e}") String headerTo,
                     @Value("${app.mail.theme.accent:#0f766e}") String accent,
                     @Value("${app.mail.theme.ink:#0f172a}") String ink,
                     @Value("${app.mail.theme.body:#334155}") String body,
                     @Value("${app.mail.theme.muted:#64748b}") String muted,
                     @Value("${app.mail.theme.page-bg:#eef2f6}") String pageBg,
                     @Value("${app.mail.theme.card-border:#e6ebf1}") String cardBorder,
                     @Value("${app.mail.theme.footer-bg:#f8fafc}") String footerBg,
                     @Value("${app.mail.theme.footer-text:#94a3b8}") String footerText,
                     @Value("${app.mail.theme.font:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif}") String font,
                     @Value("${app.mail.theme.avatar-colors:#6366f1,#0ea5e9,#0d9488,#f59e0b,#e11d48,#8b5cf6,#db2777,#0f766e}") String avatarColorsCsv) {
        this.primary = primary;
        this.headerFrom = headerFrom;
        this.headerTo = headerTo;
        this.accent = accent;
        this.ink = ink;
        this.body = body;
        this.muted = muted;
        this.pageBg = pageBg;
        this.cardBorder = cardBorder;
        this.footerBg = footerBg;
        this.footerText = footerText;
        this.font = font;
        this.avatarColorsCsv = avatarColorsCsv;
    }

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

    /**
     * Pick a stable avatar color for a seed by hashing it into the configured palette.
     *
     * @param seed the value to derive a color from (e.g. a name); null maps to the first color
     * @return a hex color string from the palette
     */
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
