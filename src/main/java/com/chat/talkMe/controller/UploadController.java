package com.chat.talkMe.controller;

import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.dto.response.UploadResponse;
import com.chat.talkMe.exception.ContentModerationException;
import com.chat.talkMe.exception.ServiceException;
import com.chat.talkMe.moderation.ContentModerationService;
import com.chat.talkMe.repository.ChatMemberRepository;
import com.chat.talkMe.repository.ChatRepository;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.MediaAssetService;
import com.chat.talkMe.service.StorageService;
import com.chat.talkMe.storage.MediaStorage;
import com.chat.talkMe.util.UploadValidator;
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
public class UploadController {

    /**
     * Per-type upload caps. The global multipart limit is the larger of these (30MB).
     */
    private static final long MAX_IMAGE_BYTES = 2L * 1024 * 1024;   // 2 MB
    private static final long MAX_VIDEO_BYTES = 30L * 1024 * 1024;  // 30 MB

    private final StorageService storageService;
    private final MediaStorage mediaStorage;
    private final ChatRepository chatRepository;
    private final ChatMemberRepository chatMemberRepository;
    private final ContentModerationService moderationService;
    private final MediaAssetService mediaAssetService;

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
     * @throws com.chat.talkMe.exception.ServiceException            if the file exceeds its per-type size cap (413)
     * @throws com.chat.talkMe.exception.ContentModerationException  if a moderated-context image is explicit
     * @throws com.chat.talkMe.exception.FileStorageException        if the file cannot be stored
     */
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
                && moderationService.moderateUpload(file).isExplicit()) {
            throw new ContentModerationException(
                    "This image violates our community guidelines and can't be uploaded.");
        }

        String subdir = resolveSubdir(context, contextId, userDetails);
        String url = storageService.storeFile(file, type, subdir);

        // Report the ACTUAL stored size — videos are transcoded server-side and
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
     * download + a locked-down CSP for scriptable types (SVG/HTML/XML). Returns 404 if not found and
     * 500 on backend error.
     *
     * @param path the media path relative to the storage root
     * @return 200 with the file resource, 404 if missing, or 500 on error
     */
    @GetMapping("/media")
    public ResponseEntity<Resource> getMedia(@RequestParam("path") String path) {
        try {
            // Delegate to the active storage backend (disk in local/dev, OCI bucket in
            // prod). The backend enforces the "under the media root" traversal guard, so
            // ?path=/etc/passwd still resolves to nothing.
            return mediaStorage.open(path)
                    .map(mc -> {
                        String contentType = mc.contentType() != null ? mc.contentType() : "application/octet-stream";
                        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                                .contentType(MediaType.parseMediaType(contentType))
                                .header("X-Content-Type-Options", "nosniff");
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
    private String resolveSubdir(String context, String contextId, CustomUserDetails userDetails) {
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
                        && chatRepository.findByUuid(UUID.fromString(cid))
                        .filter(chat -> chatMemberRepository
                                .findByChatAndUser(chat, userDetails.getUser()).isPresent())
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
