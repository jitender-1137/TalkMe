package com.chat.talkMe.service.impl;

import com.chat.talkMe.dto.EmailUnreadPreview;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Year;
import java.util.List;

/**
 * Builds branded, email-client-safe HTML for every NeoChatHub transactional email.
 *
 * <p>All templates share one {@link #layout(String, String) shell} — a 600px, table-based,
 * inline-styled layout that renders consistently across Gmail, Outlook, Apple Mail and
 * mobile clients (no flexbox/grid, no external CSS). A hidden preheader controls the inbox
 * preview line. Every value interpolated from user data is HTML-escaped, so names and
 * message snippets can't break the markup or inject content.</p>
 *
 * <p>Every colour and font comes from the single {@link MailTheme} object — the whole palette
 * (header, button, links, avatars) is tuned in one place. Brand identity (name, tagline,
 * support address) is config-driven via {@code app.mail.brand.*}. Links are passed in
 * fully-qualified by the caller (the service knows the frontend base URL).</p>
 */
@Component
public class EmailTemplates {

    /** How many unread rows to show before collapsing into a "+N more" line. */
    private static final int MAX_UNREAD_ROWS = 5;

    private final MailTheme theme;

    @Value("${app.mail.brand.name:NeoChatHub}")
    private String brandName;

    @Value("${app.mail.brand.tagline:Meet new people. Chat. Connect.}")
    private String tagline;

    @Value("${app.mail.brand.support-email:support@neochathub.com}")
    private String supportEmail;

    @Value("${app.frontend-base-url:http://localhost:3000}")
    private String baseUrl;

    public EmailTemplates(MailTheme theme) {
        this.theme = theme;
    }

    // ── Public templates ───────────────────────────────────────────────────────

    /** Sent once, after the email is verified. */
    public String welcome(String name, String openLink) {
        String content = h1("Welcome to " + esc(brandName) + ", " + greetName(name) + "! 🎉")
                + p("Your account is verified and ready. " + esc(brandName) + " is where you meet new "
                    + "people, spark real conversations, and stay connected — one chat at a time.")
                + p("Here's how to get the most out of it:")
                + featureList(List.of(
                        "Complete your profile so people know who they're talking to",
                        "Jump into a random match or browse and message someone new",
                        "Turn on notifications so you never miss a reply"))
                + button("Open " + esc(brandName), openLink)
                + signoff("Glad to have you here.");
        return layout("Welcome to " + brandName + " — let's get you started.", content);
    }

    /** Email-address verification with a confirm link. Sent first, at signup. */
    public String verifyEmail(String name, String verifyLink, long expiryMinutes) {
        String content = h1("Verify your email")
                + p("Hi " + greetName(name) + ",")
                + p("Welcome to " + esc(brandName) + "! Confirm this is your email address to activate "
                    + "your account. This link expires in " + expiryMinutes + " minutes.")
                + button("Verify email", verifyLink)
                + fallbackLink(verifyLink)
                + p(mutedText("If you didn't create a " + esc(brandName)
                    + " account, you can safely ignore this email."));
        return layout("Confirm your email to activate your " + brandName + " account.", content);
    }

    /** LinkedIn-style unread-messages digest. */
    public String unreadMessages(String name, List<EmailUnreadPreview> previews,
                                 int totalUnread, String openLink) {
        String heading = totalUnread == 1
                ? "You have a new message"
                : "You have " + totalUnread + " new messages";
        int visible = Math.min(previews.size(), MAX_UNREAD_ROWS);
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < visible; i++) {
            rows.append(unreadRow(previews.get(i), i == visible - 1));
        }
        int remaining = totalUnread - visible;

        String content = h1(heading)
                + p("Here's what you missed on " + esc(brandName) + ":")
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                    + "style=\"margin:10px 0 6px;border:1px solid " + theme.cardBorder()
                    + ";border-radius:14px;background:#fbfcfe;\">"
                    + "<tr><td style=\"padding:2px 18px;\">"
                    + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\">" + rows + "</table>"
                    + "</td></tr></table>"
                + (remaining > 0
                        ? p(mutedText("+ " + remaining + " more "
                            + (remaining == 1 ? "conversation" : "conversations") + " waiting for you."))
                        : "")
                + button("Open " + esc(brandName), openLink)
                + p(mutedText("You're receiving this because you have unread messages. "
                    + "You can turn these off in Settings → Notifications."));
        String preheader = totalUnread == 1
                ? "You have a new message on " + brandName
                : "You have " + totalUnread + " new messages on " + brandName;
        return layout(preheader, content);
    }

    /** Password-reset link. */
    public String passwordReset(String name, String resetLink, long expiryMinutes) {
        String content = h1("Reset your password")
                + p("Hi " + greetName(name) + ",")
                + p("We received a request to reset your " + esc(brandName)
                    + " password. Choose a new one using the button below. "
                    + "This link expires in " + expiryMinutes + " minutes.")
                + button("Reset password", resetLink)
                + fallbackLink(resetLink)
                + p(mutedText("If you didn't request this, you can safely ignore this email — "
                    + "your password won't change."));
        return layout("Reset your " + brandName + " password.", content);
    }

    /** Security notice after a successful password change. */
    public String passwordChanged(String name) {
        String content = h1("Your password was changed")
                + p("Hi " + greetName(name) + ",")
                + p("This is a confirmation that your " + esc(brandName)
                    + " password was just changed. If this was you, no action is needed.")
                + p("If you <b>didn't</b> make this change, your account may be compromised — "
                    + "please contact us immediately.")
                + button("Contact support", "mailto:" + supportEmail)
                + signoff("Keeping your account safe,");
        return layout("Your " + brandName + " password was changed.", content);
    }

    /**
     * New-sign-in security alert (Google-style). {@code device}, {@code location} and
     * {@code when} may each be null/blank and are shown only when present.
     */
    public String loginAlert(String name, String device, String location, String when, String secureLink) {
        String rows = detailRow("When", when)
                + detailRow("Device", device)
                + detailRow("Location", location);
        String detailBlock = rows.isBlank() ? "" :
                "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                        + "style=\"margin:8px 0 4px;border:1px solid " + theme.cardBorder()
                        + ";border-radius:14px;background:#fbfcfe;\"><tr><td style=\"padding:8px 18px;\">"
                        + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\">"
                        + rows + "</table></td></tr></table>";
        String content = h1("New sign-in to your account")
                + p("Hi " + greetName(name) + ",")
                + p("Your " + esc(brandName) + " account was just signed in to. If this was you, "
                    + "you're all set — no action needed.")
                + detailBlock
                + p("If you don't recognise this, secure your account now by changing your password.")
                + button("Secure my account", secureLink)
                + p(mutedText("You can turn these sign-in alerts off in Settings → Notifications."));
        return layout("New sign-in to your " + brandName + " account.", content);
    }

    /** Acknowledgement that a support request was received. */
    public String supportReceived(String name, String ticketId, String subjectLine) {
        String ref = ticketId == null || ticketId.isBlank() ? "" :
                p(mutedText("Reference: <b style=\"color:" + theme.ink() + ";\">#" + esc(ticketId) + "</b>"));
        String subj = subjectLine == null || subjectLine.isBlank() ? "" :
                p("<b>Subject:</b> " + esc(subjectLine));
        String content = h1("We've got your message")
                + p("Hi " + greetName(name) + ",")
                + p("Thanks for reaching out to " + esc(brandName)
                    + " support. Our team has received your request and will get back to you as soon "
                    + "as possible — usually within 24–48 hours.")
                + subj
                + ref
                + p("There's no need to reply to this email; we'll follow up at this address.")
                + signoff("The " + esc(brandName) + " Support Team");
        return layout("We've received your support request.", content);
    }

    /**
     * Generic informational / announcement email. {@code bodyHtml} is trusted content built by
     * the caller (use {@link #p(String)} to format paragraphs); {@code ctaLabel}/{@code ctaLink}
     * are optional — pass null/blank to omit the button.
     */
    public String announcement(String name, String heading, String bodyHtml,
                               String ctaLabel, String ctaLink) {
        boolean hasCta = ctaLabel != null && !ctaLabel.isBlank()
                && ctaLink != null && !ctaLink.isBlank();
        String content = h1(esc(heading))
                + p("Hi " + greetName(name) + ",")
                + (bodyHtml == null ? "" : bodyHtml)
                + (hasCta ? button(esc(ctaLabel), ctaLink) : "")
                + signoff("— The " + esc(brandName) + " Team");
        return layout(heading, content);
    }

    // ── Shared layout ────────────────────────────────────────────────────────

    private String layout(String preheader, String contentHtml) {
        String badge = brandName == null || brandName.isBlank()
                ? "N" : esc(brandName.trim().substring(0, 1).toUpperCase());
        return LAYOUT
                .replace("__PREHEADER__", esc(preheader))
                .replace("__CONTENT__", contentHtml)
                .replace("__BADGE__", badge)
                .replace("__BRAND__", esc(brandName))
                .replace("__TAGLINE__", esc(tagline))
                .replace("__SUPPORT__", esc(supportEmail))
                .replace("__BASEURL__", attr(baseUrl))
                .replace("__HEADER_A__", theme.headerFrom())
                .replace("__HEADER_B__", theme.headerTo())
                .replace("__ACCENT__", theme.accent())
                .replace("__INK__", theme.ink())
                .replace("__PAGE_BG__", theme.pageBg())
                .replace("__CARD_BORDER__", theme.cardBorder())
                .replace("__FOOTER_BG__", theme.footerBg())
                .replace("__FOOTER_TEXT__", theme.footerText())
                .replace("__FONT__", theme.font())
                .replace("__YEAR__", String.valueOf(Year.now().getValue()));
    }

    private static final String LAYOUT = """
            <!DOCTYPE html>
            <html lang="en"><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <meta name="x-apple-disable-message-reformatting">
            <title>__BRAND__</title>
            </head>
            <body style="margin:0;padding:0;background:__PAGE_BG__;-webkit-text-size-adjust:100%;">
            <span style="display:none!important;visibility:hidden;opacity:0;color:transparent;height:0;width:0;overflow:hidden;mso-hide:all;">__PREHEADER__</span>
            <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:__PAGE_BG__;padding:28px 12px;">
              <tr><td align="center">
                <table role="presentation" width="600" cellpadding="0" cellspacing="0" style="max-width:600px;width:100%;background:#ffffff;border:1px solid __CARD_BORDER__;border-radius:18px;overflow:hidden;box-shadow:0 10px 30px -18px rgba(15,23,42,0.28);">
                  <tr><td style="background:__HEADER_A__;background:linear-gradient(120deg,__HEADER_A__ 0%,__HEADER_B__ 100%);padding:28px 32px;">
                    <table role="presentation" cellpadding="0" cellspacing="0"><tr>
                      <td valign="middle" style="padding-right:14px;">
                        <table role="presentation" width="46" height="46" cellpadding="0" cellspacing="0" style="width:46px;height:46px;border-radius:13px;background:rgba(255,255,255,0.16);">
                          <tr><td align="center" valign="middle" style="height:46px;color:#ffffff;font-family:__FONT__;font-size:21px;font-weight:700;">__BADGE__</td></tr>
                        </table>
                      </td>
                      <td valign="middle">
                        <div style="font-family:__FONT__;font-size:21px;font-weight:700;color:#ffffff;letter-spacing:-0.3px;">__BRAND__</div>
                        <div style="font-family:__FONT__;font-size:13px;color:rgba(255,255,255,0.82);margin-top:2px;">__TAGLINE__</div>
                      </td>
                    </tr></table>
                  </td></tr>
                  <tr><td style="padding:34px 32px;font-family:__FONT__;color:__INK__;font-size:15px;line-height:1.6;">
                    __CONTENT__
                  </td></tr>
                  <tr><td style="background:__FOOTER_BG__;border-top:1px solid __CARD_BORDER__;padding:22px 32px;font-family:__FONT__;font-size:12px;line-height:1.7;color:__FOOTER_TEXT__;">
                    Need help? Email us at <a href="mailto:__SUPPORT__" style="color:__ACCENT__;text-decoration:none;">__SUPPORT__</a>.<br>
                    <a href="__BASEURL__" style="color:__FOOTER_TEXT__;text-decoration:underline;">__BRAND__</a> &middot; Meet new people, chat, and connect.<br>
                    &copy; __YEAR__ __BRAND__. All rights reserved.
                  </td></tr>
                </table>
              </td></tr>
            </table>
            </body></html>
            """;

    // ── Content helpers ────────────────────────────────────────────────────────

    private String h1(String htmlText) {
        return "<h1 style=\"margin:0 0 16px;font-size:22px;line-height:1.3;font-weight:700;color:"
                + theme.ink() + ";\">" + htmlText + "</h1>";
    }

    private String p(String html) {
        return "<p style=\"margin:0 0 14px;color:" + theme.body() + ";\">" + html + "</p>";
    }

    private String mutedText(String html) {
        return "<span style=\"color:" + theme.muted() + ";font-size:13px;\">" + html + "</span>";
    }

    private String signoff(String line) {
        return "<p style=\"margin:22px 0 0;color:" + theme.muted() + ";\">" + esc(line)
                + "<br>— The " + esc(brandName) + " Team</p>";
    }

    /** A label/value row for the login-alert detail card; blank value ⇒ row omitted. */
    private String detailRow(String label, String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return "<tr>"
                + "<td valign=\"top\" style=\"width:78px;padding:6px 0;color:" + theme.muted()
                + ";font-size:13px;\">" + esc(label) + "</td>"
                + "<td valign=\"top\" style=\"padding:6px 0;color:" + theme.ink()
                + ";font-size:14px;font-weight:600;\">" + esc(value) + "</td>"
                + "</tr>";
    }

    private String featureList(List<String> items) {
        StringBuilder sb = new StringBuilder(
                "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:4px 0 8px;\">");
        for (String item : items) {
            sb.append("<tr>")
              .append("<td valign=\"top\" style=\"width:30px;padding:2px 12px 12px 0;\">")
              .append("<table role=\"presentation\" width=\"20\" height=\"20\" cellpadding=\"0\" cellspacing=\"0\" "
                      + "style=\"width:20px;height:20px;border-radius:7px;background:rgba(15,118,110,0.10);\">"
                      + "<tr><td align=\"center\" valign=\"middle\" style=\"height:20px;color:" + theme.accent()
                      + ";font-size:12px;font-weight:700;\">&#10003;</td></tr></table>")
              .append("</td>")
              .append("<td valign=\"middle\" style=\"color:").append(theme.body())
              .append(";font-size:15px;line-height:1.5;padding-bottom:12px;\">")
              .append(esc(item)).append("</td>")
              .append("</tr>");
        }
        return sb.append("</table>").toString();
    }

    private String button(String label, String url) {
        return """
                <table role="presentation" cellpadding="0" cellspacing="0" align="center" style="margin:28px auto 24px;">
                  <tr><td align="center" bgcolor="__PRIMARY__" style="border-radius:12px;box-shadow:0 8px 18px -6px rgba(4,120,87,0.45);">
                    <a href="__URL__" style="display:inline-block;padding:14px 34px;font-family:__FONT__;font-size:15px;font-weight:600;color:#ffffff;text-decoration:none;border-radius:12px;letter-spacing:0.01em;">__LABEL__</a>
                  </td></tr>
                </table>
                """
                .replace("__PRIMARY__", theme.primary())
                .replace("__FONT__", theme.font())
                .replace("__URL__", attr(url))
                .replace("__LABEL__", label);
    }

    private String fallbackLink(String url) {
        return "<p style=\"margin:0 0 14px;font-size:13px;color:" + theme.muted() + ";\">"
                + "If the button doesn't work, paste this link into your browser:<br>"
                + "<a href=\"" + attr(url) + "\" style=\"color:" + theme.accent() + ";word-break:break-all;\">"
                + esc(url) + "</a></p>";
    }

    private String unreadRow(EmailUnreadPreview pv, boolean last) {
        String name = pv.senderName() == null || pv.senderName().isBlank() ? "Someone" : pv.senderName();
        String snippet = pv.snippet() == null ? "" : pv.snippet();
        String time = pv.timeAgo() == null ? "" : pv.timeAgo();
        String divider = last ? "" : "border-bottom:1px solid #eef2f6;";
        return "<tr><td style=\"padding:14px 0;" + divider + "\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr>"
                + "<td valign=\"top\" style=\"width:48px;\">" + avatar(pv.avatarUrl(), name) + "</td>"
                + "<td valign=\"top\" style=\"padding-left:12px;\">"
                + "<div style=\"font-size:15px;color:" + theme.ink() + ";\">"
                + "<b>" + esc(name) + "</b>"
                + (time.isBlank() ? "" : " <span style=\"color:" + theme.footerText() + ";font-size:12px;\">&middot; " + esc(time) + "</span>")
                + "</div>"
                + "<div style=\"font-size:14px;color:" + theme.muted() + ";margin-top:2px;\">" + esc(truncate(snippet, 90)) + "</div>"
                + "</td></tr></table>"
                + "</td></tr>";
    }

    private String avatar(String url, String name) {
        if (url != null && !url.isBlank()) {
            return "<img src=\"" + attr(url) + "\" width=\"44\" height=\"44\" alt=\"\" "
                    + "style=\"width:44px;height:44px;border-radius:50%;display:block;object-fit:cover;\">";
        }
        String color = theme.avatarColor(name);
        String initial = esc(name.trim().substring(0, 1).toUpperCase());
        return "<table role=\"presentation\" width=\"44\" height=\"44\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"width:44px;height:44px;border-radius:50%;background:" + color + ";\">"
                + "<tr><td align=\"center\" valign=\"middle\" style=\"height:44px;color:#ffffff;font-family:" + theme.font()
                + ";font-size:18px;font-weight:600;\">" + initial + "</td></tr></table>";
    }

    // ── String helpers ───────────────────────────────────────────────────────

    private String greetName(String name) {
        return esc(name == null || name.isBlank() ? "there" : name.trim());
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        s = s.strip();
        return s.length() <= max ? s : s.substring(0, max - 1).stripTrailing() + "…";
    }

    /** HTML-escape text content. */
    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /** Escape a URL for safe use in an href attribute. */
    private static String attr(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }
}
