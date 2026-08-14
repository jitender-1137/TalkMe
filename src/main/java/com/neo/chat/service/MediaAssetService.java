package com.neo.chat.service;

import com.neo.chat.domain.MediaAsset;
import com.neo.chat.domain.User;
import com.neo.chat.enums.MediaContext;
import com.neo.chat.repository.MediaAssetRepository;
import com.neo.chat.storage.MediaKeys;
import com.neo.chat.storage.StorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists an admin-only {@link MediaAsset} ownership record for every upload, so the
 * storage gallery can attribute a physical file to its uploader — including stranger and
 * lobby media that never becomes a chat {@code MessageAttachment}.
 *
 * <p>Recording is strictly fail-open: an upload must never fail because we couldn't write
 * its bookkeeping row. The record runs in its own transaction ({@code REQUIRES_NEW}) and
 * swallows every exception.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaAssetService {

    private final MediaAssetRepository repository;
    private final StorageProperties storageProperties;

    /**
     * Record ownership for a freshly-stored upload. The {@code context}/{@code contextId}
     * are derived from the actual stored key (not the raw client params), so the record
     * always agrees with the folder the file landed in.
     *
     * @param reference        the stored reference returned by the storage backend
     * @param owner            the authenticated uploader (maybe null for anonymous flows)
     * @param uploadType       the upload {@code type} param (image / video / …)
     * @param originalFileName client-reported file name
     * @param contentType      MIME type
     * @param fileSize         stored size in bytes
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String reference, User owner, String uploadType,
                       String originalFileName, String contentType, Long fileSize) {
        try {
            String key = MediaKeys.key(reference, storageProperties.getMediaRoot());
            if (key == null) {
                log.debug("[MediaAsset] skip record — unresolvable key for ref {}", reference);
                return;
            }
            MediaContext context = MediaContext.fromCategory(categoryOf(key));
            String contextId = context == MediaContext.CONVERSATION ? segment(key, 1) : null;

            // Upsert on the unique storage key: a re-store of the same key just refreshes
            // the record rather than throwing a constraint violation.
            MediaAsset asset = repository.findByStorageKey(key).orElseGet(MediaAsset::new);
            asset.setStorageKey(key);
            asset.setReference(reference);
            asset.setOwner(owner);
            asset.setContext(context);
            asset.setContextId(contextId);
            asset.setUploadType(uploadType);
            asset.setOriginalFileName(truncate(originalFileName, 512));
            asset.setContentType(truncate(contentType, 100));
            asset.setFileSize(fileSize);
            // saveAndFlush so a constraint/DB error surfaces HERE (inside the try) rather
            // than at the deferred REQUIRES_NEW commit, where it would escape this catch.
            // The call site also guards, so either way an upload is never broken.
            repository.saveAndFlush(asset);
        } catch (RuntimeException e) {
            // Never break an upload over bookkeeping.
            log.warn("[MediaAsset] failed to record ownership for {}: {}", reference, e.getMessage());
        }
    }

    /**
     * Top-level folder of an object key (mirrors AdminServiceImpl.categoryOf).
     */
    private static String categoryOf(String key) {
        int slash = key.indexOf('/');
        String top = slash > 0 ? key.substring(0, slash) : key;
        return switch (top) {
            case "conversations", "lobby", "strangers", "profiles", "posts", "stories" -> top;
            default -> "other";
        };
    }

    /**
     * The nth {@code /}-separated segment of a key, or null.
     */
    private static String segment(String key, int index) {
        String[] parts = key.split("/");
        return index >= 0 && index < parts.length ? parts[index] : null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
