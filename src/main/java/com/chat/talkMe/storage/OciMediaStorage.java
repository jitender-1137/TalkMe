package com.chat.talkMe.storage;

import com.chat.talkMe.exception.FileStorageException;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.oracle.bmc.objectstorage.model.ObjectSummary;
import com.oracle.bmc.objectstorage.requests.DeleteObjectRequest;
import com.oracle.bmc.objectstorage.requests.GetObjectRequest;
import com.oracle.bmc.objectstorage.requests.ListObjectsRequest;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import com.oracle.bmc.objectstorage.responses.GetObjectResponse;
import com.oracle.bmc.objectstorage.responses.ListObjectsResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * OCI Object Storage media store — prod only ({@code storage.provider=oci}).
 *
 * <p>A transparent replacement for the filesystem: bytes live in a single OCI bucket
 * shared by every backend instance (so media survives instance replacement and is
 * consistent across a scaled-out deployment), accessed by the backend via an OCI API
 * key. The stored reference keeps the same {@code <MEDIA_ROOT>/<key>} shape the app
 * has always used, and media is streamed back through the existing serve endpoint —
 * the media pipeline, URLs, and frontend are untouched.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "storage.provider", havingValue = "oci")
public class OciMediaStorage implements MediaStorage {

    private final ObjectStorageClient client;
    private final StorageProperties props;
    private final StorageProperties.Oci oci;

    public OciMediaStorage(ObjectStorageClient client, StorageProperties props) {
        this.client = client;
        this.props = props;
        this.oci = props.getOci();
    }

    /**
     * Upload {@code source} to the shared bucket under object name {@code key} (with its
     * byte length and content type) and return the {@code <media-root>/<key>} reference
     * the app persists. Rejects unsafe keys.
     *
     * @param source      the prepared local file to upload ({@code java.nio.file.Path})
     * @param key         the object name / key within the bucket ({@code java.lang.String})
     * @param contentType the MIME type stored on the object ({@code java.lang.String})
     * @return the reference {@code <media-root>/<key>} ({@code java.lang.String})
     * @throws com.chat.talkMe.exception.FileStorageException if the key is unsafe or the
     *                    upload fails
     */
    @Override
    public String store(Path source, String key, String contentType) {
        if (!MediaKeys.isSafeKey(key)) {
            throw new FileStorageException("Invalid media key: " + key);
        }
        try (InputStream body = Files.newInputStream(source)) {
            client.putObject(PutObjectRequest.builder()
                    .namespaceName(oci.getNamespace())
                    .bucketName(oci.getBucket())
                    .objectName(key)
                    .contentLength(Files.size(source))
                    .contentType(contentType)
                    .putObjectBody(body)
                    .build());
        } catch (IOException | RuntimeException e) {
            throw new FileStorageException("OCI upload failed for " + key + ": " + e.getMessage());
        }
        // The reference shape the app persists: <media-root>/<key>.
        return props.getMediaRoot() + "/" + key;
    }

    /**
     * Fetch the bucket object for {@code reference} and wrap its stream as a
     * {@link MediaContent} whose content length is known up front (so Spring does not
     * drain the stream to measure it) and whose filename is derived from the key.
     *
     * @param reference the stored reference ({@code java.lang.String})
     * @return an {@code java.util.Optional} of {@link MediaContent}; empty if the key is
     *                  invalid or the fetch fails
     */
    @Override
    public Optional<MediaContent> open(String reference) {
        String key = MediaKeys.key(reference, props.getMediaRoot());
        if (key == null) return Optional.empty();
        try {
            GetObjectResponse resp = getObject(key);
            long len = resp.getContentLength() != null ? resp.getContentLength() : -1;
            InputStream in = resp.getInputStream();
            Resource resource = new InputStreamResource(in) {
                @Override
                public long contentLength() {
                    return len; // known up front — avoids Spring draining the stream
                }

                @Override
                public String getFilename() {
                    int slash = key.lastIndexOf('/');
                    return slash >= 0 ? key.substring(slash + 1) : key;
                }
            };
            return Optional.of(new MediaContent(resource, resp.getContentType(), len));
        } catch (RuntimeException e) {
            log.warn("OCI open failed for {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Download the bucket object for {@code reference} to a temp file (extension derived
     * from the key) and return it as a {@link TempLocalFile} whose {@link LocalFile#close()}
     * deletes the copy. On failure the temp file is best-effort removed.
     *
     * @param reference the stored reference ({@code java.lang.String})
     * @return an {@code java.util.Optional} of {@link LocalFile}; empty if the key is invalid
     *                  or the download fails
     */
    @Override
    public Optional<LocalFile> localCopy(String reference) {
        String key = MediaKeys.key(reference, props.getMediaRoot());
        if (key == null) return Optional.empty();
        Path tmp = null;
        try {
            tmp = Files.createTempFile("nch-oci-", extensionOf(key));
            GetObjectResponse resp = getObject(key);
            try (InputStream in = resp.getInputStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            return Optional.of(new TempLocalFile(tmp));
        } catch (IOException | RuntimeException e) {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) { /* best-effort */ }
            }
            log.warn("OCI download failed for {}: {}", reference, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Best-effort delete of the bucket object for {@code reference}; invalid keys and any
     * {@code java.lang.RuntimeException} are swallowed (logged), never thrown.
     *
     * @param reference the stored reference ({@code java.lang.String})
     */
    @Override
    public void delete(String reference) {
        String key = MediaKeys.key(reference, props.getMediaRoot());
        if (key == null) return;
        try {
            client.deleteObject(DeleteObjectRequest.builder()
                    .namespaceName(oci.getNamespace())
                    .bucketName(oci.getBucket())
                    .objectName(key)
                    .build());
        } catch (RuntimeException e) {
            log.warn("OCI delete failed for {}: {}", key, e.getMessage());
        }
    }

    /**
     * Page through the bucket (optionally restricted to {@code prefix}, 1000 objects per
     * page) and return one {@link StoredObject} per safe-keyed object, carrying the
     * {@code <media-root>/<key>} reference, size, last-modified/created time and guessed
     * content type. Best-effort — a {@code java.lang.RuntimeException} yields the partial
     * list gathered so far.
     *
     * @param prefix an object-name prefix to filter by, or null/blank for the whole bucket
     *               ({@code java.lang.String})
     * @return a {@code java.util.List} of {@link StoredObject} (never null)
     */
    @Override
    public List<StoredObject> list(String prefix) {
        List<StoredObject> out = new ArrayList<>();
        String start = null;
        try {
            do {
                ListObjectsRequest.Builder req = ListObjectsRequest.builder()
                        .namespaceName(oci.getNamespace())
                        .bucketName(oci.getBucket())
                        .fields("name,size,timeModified,timeCreated")
                        .limit(1000);
                if (prefix != null && !prefix.isBlank()) req.prefix(prefix);
                if (start != null) req.start(start);
                ListObjectsResponse resp = client.listObjects(req.build());
                var lo = resp.getListObjects();
                if (lo == null) break;
                for (ObjectSummary os : lo.getObjects()) {
                    String key = os.getName();
                    if (!MediaKeys.isSafeKey(key)) continue;
                    long size = os.getSize() != null ? os.getSize() : 0L;
                    Date when = os.getTimeModified() != null ? os.getTimeModified() : os.getTimeCreated();
                    out.add(new StoredObject(
                            props.getMediaRoot() + "/" + key,
                            key,
                            size,
                            when != null ? when.toInstant() : null,
                            MediaKeys.contentTypeGuess(key)));
                }
                start = lo.getNextStartWith();
            } while (start != null && !start.isBlank());
        } catch (RuntimeException e) {
            log.warn("OCI list failed for prefix {}: {}", prefix, e.getMessage());
        }
        return out;
    }

    /**
     * Issue a GetObject against the configured namespace/bucket for {@code key}.
     *
     * @param key the object name within the bucket ({@code java.lang.String})
     * @return the {@code com.oracle.bmc.objectstorage.responses.GetObjectResponse}
     */
    private GetObjectResponse getObject(String key) {
        return client.getObject(GetObjectRequest.builder()
                .namespaceName(oci.getNamespace())
                .bucketName(oci.getBucket())
                .objectName(key)
                .build());
    }

    /**
     * The file extension (including the dot) of {@code key}, or {@code ".tmp"} if the key
     * has no extension in its final path segment.
     *
     * @param key the object key ({@code java.lang.String})
     * @return the extension including the leading dot, or {@code ".tmp"}
     *                  ({@code java.lang.String})
     */
    private static String extensionOf(String key) {
        int dot = key.lastIndexOf('.');
        int slash = key.lastIndexOf('/');
        return (dot > slash && dot >= 0) ? key.substring(dot) : ".tmp";
    }

    /**
     * A temp download — {@link #close()} deletes it.
     */
    private record TempLocalFile(Path path) implements LocalFile {
        @Override
        public void close() {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) { /* best-effort */ }
        }
    }
}
