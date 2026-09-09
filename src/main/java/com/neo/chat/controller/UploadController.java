package com.neo.chat.controller;

import com.neo.chat.domain.User;
import com.neo.chat.security.JwtTokenProvider;
import com.neo.chat.storage.MediaKeys;
import com.neo.chat.storage.StorageProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.dto.response.UploadResponse;
import com.neo.chat.exception.ContentModerationException;
import com.neo.chat.exception.ServiceException;
import com.neo.chat.moderation.ContentModerationService;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.MediaAssetService;
import com.neo.chat.service.StorageService;
import com.neo.chat.service.lookup.ChatMembershipLookupService;
import com.neo.chat.service.lookup.UserLookupSupport;
import com.neo.chat.storage.MediaStorage;
import com.neo.chat.util.UploadValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.UUID;

/**
 * Media upload + serve endpoints. Validates size and real content type, moderates publicly-visible
 * uploads, stores files via the active {@link StorageService}/{@link MediaStorage} backend, and
 * records admin-only ownership rows. No class-level auth gate; the upload derives owner ids from the
 * authenticated principal.
 */
@Slf4j
@RestController
@RequestMapping("/uploads")
@RequiredArgsConstructor
@Tag(name = "Uploads", description = "Media upload and serve endpoints")
public class UploadController {

    /**
     * Per-type upload caps. The global multipart limit is the larger of these (30MB).
     */
    private static final long MAX_IMAGE_BYTES = 2L * 1024 * 1024;   // 2 MB
    private static final long MAX_VIDEO_BYTES = 30L * 1024 * 1024;  // 30 MB

    private final StorageService storageService;
    private final MediaStorage mediaStorage;
    private final ChatMembershipLookupService chatMembershipLookup;
    private final ContentModerationService moderationService;
    private final MediaAssetService mediaAssetService;
    private final StorageProperties storageProperties;
    private final JwtTokenProvider tokenProvider;
    private final UserLookupSupport userLookup;

    /**
     * Upload categories whose images/videos must be CLEAN (publicly visible content).
     */
    private static final Set<String> MODERATED_CONTEXTS = Set.of("profile", "post", "story");

    /**
     * Upload a single media file: enforce per-type size caps, verify the real content type from
     * magic bytes, hard-block NSFW uploads for publicly-visible contexts (profile/post/story),
     * store the file under a server-derived subfolder, and record a best-effort ownership row.
     *
     * @param file        the multipart file to store
     * @param type        media type hint ("image" | "video"); drives size caps and validation
     * @param context     destination category (conversation|post|story|profile|stranger|lobby); optional
     * @param contextId   client-supplied id used only for the conversation context (validated as a UUID)
     * @param userDetails the authenticated principal; owner ids are derived from it, never the client
     * @return 200 with the stored file's url, name, actual stored size, and mime type
     * @throws com.neo.chat.exception.ServiceException           if the file exceeds its per-type size cap (413)
     * @throws com.neo.chat.exception.ContentModerationException if a moderated-context image is explicit
     * @throws com.neo.chat.exception.FileStorageException       if the file cannot be stored
     */
    @Operation(summary = "Upload a single media file (size-capped, type-verified, moderated for public contexts) and record its ownership")
    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<ResponseDto<UploadResponse>> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("type") String type,
            // Destination category (conversation | post | story | profile | stranger | lobby).
            // Owner ids are derived SERVER-SIDE from the principal — never trusted from
            // the client — except the conversation id, which is validated as a UUID.
            @RequestParam(value = "context", required = false) String context,
            @RequestParam(value = "contextId", required = false) String contextId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        validateSize(file, type);

        // Verify the REAL content type from magic bytes (never trust the client's
        // Content-Type) — rejects polyglots / disguised files and scriptable SVGs.
        UploadValidator.validate(file, type);

        // Publicly-visible images/videos (profile photos, feed posts, stories) must be
        // clean — reject NSFW uploads up-front, before the file is ever stored. 1:1 /
        // group conversation media is intentionally NOT hard-blocked here (it's handled
        // at send time with the consent flow), and stranger/lobby are excluded.
        if (context != null && MODERATED_CONTEXTS.contains(context.toLowerCase())
                && moderationService.moderateUpload(file).explicit()) {
            throw new ContentModerationException(
                    "This image violates our community guidelines and can't be uploaded.");
        }

        String subdivide = resolveSubdivide(context, contextId, userDetails);
        String url = storageService.storeFile(file, type, subdivide);

        // Report the ACTUAL stored size — videos are transcode server-side and
        // are typically much smaller than the uploaded multipart file.
        long storedSize = file.getSize();
        try {
            Path stored = Paths.get(url);
            if (Files.exists(stored)) {
                storedSize = Files.size(stored);
            }
        } catch (Exception ignored) {
            // fall back to the original multipart size
        }

        // Record an admin-only ownership row for this object (fail-open — never blocks the
        // upload). This is the ONLY durable owner link for stranger media, whose storage
        // path is deliberately anonymous, and it lets the admin gallery attribute lobby /
        // stranger files that never become a persisted MessageAttachment. The call site is
        // guarded too: record() runs in its own (REQUIRES_NEW) transaction whose commit
        // happens as it returns, so a flush/commit-time failure would otherwise escape here.
        try {
            mediaAssetService.record(
                    url,
                    userDetails != null ? userDetails.getUser() : null,
                    type,
                    file.getOriginalFilename(),
                    file.getContentType(),
                    storedSize);
        } catch (RuntimeException e) {
            // Bookkeeping must never fail an already-stored upload.
            log.warn("[Upload] media-ownership record failed for {}: {}", url, e.getMessage());
        }

        UploadResponse response = UploadResponse.builder()
                .url(url)
                .fileName(file.getOriginalFilename())
                .fileSize(storedSize)
                .mimeType(file.getContentType())
                .build();

        return ResponseEntity.ok(SuccessResponseDto.success(response, "File uploaded successfully", "TM_167"));
    }

    /**
     * Serve a stored media file by path via the active storage backend (traversal-guarded), forcing
     * download + a locked-down CSP for scriptable types (SVG/HTML/XML).
     *
     * <p>AUTHORIZATION (this endpoint is {@code permitAll} at the security-rule level because
     * browsers load {@code <img>/<video>} without an Authorization header):
     * <ul>
     *   <li>The viewer is identified from the Bearer token (SecurityContext) or, failing that, from
     *       the HttpOnly {@code media_token} cookie issued at login (path-scoped to this endpoint).</li>
     *   <li>{@code profiles/**} (avatars) are public — they appear on the public {@code /@username}
     *       page and in emails.</li>
     *   <li>{@code conversations/{chatUuid}/**} requires the viewer to be a member of that chat.</li>
     *   <li>Everything else (posts, stories, lobby, strangers, others, legacy flat keys) requires an
     *       authenticated, non-disabled viewer.</li>
     * </ul>
     * Returns 401 for an anonymous viewer of protected media, 403 for a non-member, 404 if the
     * reference does not resolve, and 500 on backend error.
     *
     * @param path    the media reference (absolute stored path or object key)
     * @param request the servlet request (Bearer principal / media cookie)
     * @return 200 with the file resource, or the status codes above
     */
    @Operation(summary = "Serve a stored media file by path via the active storage backend (traversal-guarded; download-forced for scriptable types)")
    @GetMapping("/media")
    public ResponseEntity<Resource> getMedia(@RequestParam("path") String path, HttpServletRequest request) {
        String[] catAndId = categoryAndChat(path);
        String category = catAndId[0];

        if (!"profiles".equals(category)) {
            String viewer = resolveViewer(request);
            if (viewer == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            if ("conversations".equals(category)) {
                String chatUuid = catAndId[1];
                if (chatUuid == null || !isChatMember(chatUuid, viewer)) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
                }
            }
        }

        try {
            // Delegate to the active storage backend (disk in local/dev, OCI bucket in
            // prod). The backend enforces the "under the media root" traversal guard, so
            // ?path=/etc/passwd still resolves to nothing.
            return mediaStorage.open(path)
                    .map(mc -> {
                        String contentType = mc.contentType() != null ? mc.contentType() : "application/octet-stream";
                        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                                .contentType(MediaType.parseMediaType(contentType))
                                .header("X-Content-Type-Options", "nosniff")
                                // Per-viewer authorization → never let a shared cache serve it to someone else.
                                .header("Cache-Control", "private, max-age=3600");
                        // Neutralize scriptable types (SVG/HTML/XML): a stored file must
                        // not execute as a document on our own origin if opened directly.
                        // <img>/<video> ignore Content-Disposition, so display is unaffected;
                        // only top-level navigation is forced to download + sandboxed.
                        String lower = contentType.toLowerCase();
                        if (lower.contains("svg") || lower.contains("html") || lower.contains("xml")) {
                            builder.header("Content-Disposition", "attachment");
                            builder.header("Content-Security-Policy", "default-src 'none'; sandbox");
                        }
                        if (mc.contentLength() >= 0) {
                            builder.contentLength(mc.contentLength());
                        }
                        return builder.body(mc.resource());
                    })
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Best-effort extraction of the object's category (and, for conversation media, the chat uuid)
     * from any reference form the frontend may send: an absolute stored path
     * ({@code /media/conversations/<uuid>/f.jpg}, {@code /opt/media/...}), a bare object key
     * ({@code conversations/<uuid>/f.jpg}), or a {@code ?path=} URL. Returns {@code [category, chatUuid]}
     * where {@code chatUuid} is null unless the category is {@code conversations}. An unrecognised
     * reference yields {@code ["", null]} → treated as protected (authenticated viewer required).
     */
    private static final Set<String> MEDIA_CATEGORIES = Set.of(
            "conversations", "profiles", "posts", "stories", "lobby", "strangers", "others");

    private String[] categoryAndChat(String reference) {
        String abs = MediaKeys.absolutePath(reference);
        String scan = abs != null ? abs : reference;
        if (scan == null) return new String[]{"", null};
        int q = scan.indexOf('?');
        if (q >= 0) scan = scan.substring(0, q);
        String[] parts = scan.split("/");
        for (int i = 0; i < parts.length; i++) {
            if (MEDIA_CATEGORIES.contains(parts[i])) {
                String chat = ("conversations".equals(parts[i]) && i + 1 < parts.length && !parts[i + 1].isBlank())
                        ? parts[i + 1] : null;
                return new String[]{parts[i], chat};
            }
        }
        return new String[]{"", null};
    }

    /**
     * Identify the viewer: the authenticated Bearer principal if present, else the subject of a
     * valid {@code media_token} cookie whose account is still enabled. Null when anonymous.
     */
    private String resolveViewer(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof CustomUserDetails cud) {
            return cud.getUsername();
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (AuthController.MEDIA_COOKIE.equals(c.getName())) {
                String username = tokenProvider.parseMediaToken(c.getValue());
                if (username == null) return null;
                // A media cookie outlives a ban / deletion — re-check the account state.
                return userLookup.findByUsername(username)
                        .filter(u -> !u.isDeleted() && !u.isBanned())
                        .map(User::getUsername)
                        .orElse(null);
            }
        }
        return null;
    }

    /**
     * Whether {@code viewer} is a member of chat {@code chatUuid}. Fails closed on a bad uuid,
     * a missing chat, or a lookup error.
     */
    private boolean isChatMember(String chatUuid, String viewer) {
        try {
            return chatMembershipLookup.findByUuidWithMembers(UUID.fromString(chatUuid))
                    .map(chat -> chat.getMembers().stream()
                            .anyMatch(m -> m.getUser() != null && viewer.equals(m.getUser().getUsername())))
                    .orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Enforce per-type upload caps: images ≤ 2 MB, videos ≤ 30 MB. (The global
     * multipart limit already blocks anything over 30 MB with a 413; this adds the
     * stricter image cap and a clear, type-specific message.) Reject with HTTP 413.
     */
    private void validateSize(MultipartFile file, String type) {
        long size = file.getSize();
        String ct = file.getContentType();
        boolean isImage = "image".equalsIgnoreCase(type) || (ct != null && ct.startsWith("image/"));
        boolean isVideo = "video".equalsIgnoreCase(type) || (ct != null && ct.startsWith("video/"));
        if (isImage && size > MAX_IMAGE_BYTES) {
            throw new ServiceException(413, "Image is too large. Maximum size is 2 MB.", "TM_491");
        }
        if (isVideo && size > MAX_VIDEO_BYTES) {
            throw new ServiceException(413, "Video is too large. Maximum size is 30 MB.", "TM_492");
        }
    }

    /**
     * Map an upload category to a media subfolder. Owner ids come from the
     * authenticated principal (traversal-safe); the only client-supplied id
     * (conversation) must be a UUID that EXISTS and that the uploader is a member of.
     * Stranger media stays anonymous (no id in the peer-visible path). Anything
     * unknown/unverified falls back to {@code others/}.
     */
    private String resolveSubdivide(String context, String contextId, CustomUserDetails userDetails) {
        if (context == null) {
            return "others";
        }
        String uid = (userDetails != null && userDetails.getUser() != null)
                ? userDetails.getUser().getUuid().toString()
                : null;
        switch (context) {
            case "stranger":
                // The URL is shared with the anonymous peer, so the path must NOT carry
                // a user/session id — a flat folder + random filename keeps it unlinkable.
                return "strangers";
            case "profile":
                if (uid != null) return "profiles/" + uid;
                break;
            case "post":
                if (uid != null) return "posts/" + uid;
                break;
            case "story":
                if (uid != null) return "stories/" + uid;
                break;
            case "lobby":
                if (uid != null) return "lobby/" + uid;
                break;
            case "conversation": {
                // Only file into conversations/{id} for a chat that EXISTS and that the
                // uploader is a member of — never trust a client-supplied conversation id.
                String cid = safeUuid(contextId);
                if (cid != null && userDetails != null && userDetails.getUser() != null
                        && chatMembershipLookup.findByUuid(UUID.fromString(cid))
                        .filter(chat -> chatMembershipLookup.isMember(chat, userDetails.getUser()))
                        .isPresent()) {
                    return "conversations/" + cid;
                }
                break;
            }
            default:
                break;
        }
        return "others";
    }

    /**
     * Normalized UUID string, or null if {@code value} is not a valid UUID.
     */
    private String safeUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim()).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
