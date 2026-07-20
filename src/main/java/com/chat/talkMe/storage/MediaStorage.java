package com.chat.talkMe.storage;

import org.springframework.core.io.Resource;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Backend-agnostic media store. Two implementations are selected by
 * {@code storage.provider}: {@link LocalMediaStorage} (filesystem — local/dev, and
 * the default) and {@link OciMediaStorage} (a single shared OCI Object Storage
 * bucket — prod only).
 *
 * <p>This is a purely <b>transparent</b> swap. The store returns a
 * {@code <media-root>/<key>} reference (the root is {@code storage.media-root},
 * default {@code /media} — e.g. {@code /media/conversations/<uuid>/<rand>.mp4}), and
 * media is served through the same endpoint as before. The only difference is that in
 * prod the bytes live in an OCI bucket instead of on the VM disk — nothing about the
 * media-creation pipeline (video transcode, photo+music mux), the stored URLs, the
 * serve endpoint, or the frontend changes.
 */
public interface MediaStorage {

    /**
     * Persist {@code source} (a prepared local file — already transcoded/muxed as
     * needed) under {@code key}, and return the reference to store in the DB:
     * {@code <MEDIA_ROOT>/<key>}. The caller still owns {@code source}.
     */
    String store(Path source, String key, String contentType);

    /** Open a stored reference for streaming to a client. Empty if it cannot be resolved. */
    Optional<MediaContent> open(String reference);

    /**
     * Materialize a stored reference as a readable local file for server-side
     * processing (ffmpeg transcode/mux, NSFW frame extraction). Filesystem-backed
     * references resolve in place; OCI references download to a temp file. Callers
     * MUST use try-with-resources — {@link LocalFile#close()} deletes the temp copy
     * (a no-op for an in-place file). Empty if the reference cannot be resolved.
     */
    Optional<LocalFile> localCopy(String reference);

    /** Delete the object/file for a stored reference. Best-effort; never throws. */
    void delete(String reference);

    /**
     * List stored objects under {@code prefix} (null/blank = the whole store). Each
     * {@link StoredObject#reference()} carries the same {@code <media-root>/<key>}
     * shape the app persists, so it can be reconciled against DB references directly.
     * Used by the admin storage reconciler to surface objects that exist in the bucket
     * but have no DB row (orphans). Best-effort — returns an empty list on failure.
     */
    java.util.List<StoredObject> list(String prefix);

    /** A readable media object plus the metadata needed to serve it. */
    record MediaContent(Resource resource, String contentType, long contentLength) {}

    /** A stored object's metadata (no bytes) — one row in a {@link #list(String)}. */
    record StoredObject(String reference, String key, long size,
                        java.time.Instant lastModified, String contentType) {}

    /** A local file handle whose {@link #close()} deletes it only if it is a temp copy. */
    interface LocalFile extends Closeable {
        Path path();
        @Override
        void close();
    }
}
