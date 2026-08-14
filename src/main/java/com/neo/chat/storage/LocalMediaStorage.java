package com.neo.chat.storage;

import com.neo.chat.exception.FileStorageException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Filesystem-backed media store — the default, used in local/dev (and prod only if
 * {@code storage.provider} is left {@code local}). Objects live under
 * {@code storage.media-root} and the stored reference is the absolute path
 * {@code <media-root>/<key>}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "storage.provider", havingValue = "local", matchIfMissing = true)
public class LocalMediaStorage implements MediaStorage {

    private final Path root;

    public LocalMediaStorage(StorageProperties props) {
        this.root = Paths.get(props.getMediaRoot()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new FileStorageException("Could not create media root: " + root + " " + e);
        }
        log.info("LocalMediaStorage active — media root {}", root);
    }

    /**
     * Copy {@code source} to {@code <media-root>/<key>}, creating parent folders and
     * overwriting any existing file, and return the absolute target path as the stored
     * reference. Rejects keys that are unsafe or that resolve outside the media root.
     *
     * @param source      the prepared local file to persist ({@code java.nio.file.Path})
     * @param key         the object key / relative sub-path under the media root
     *                    ({@code java.lang.String})
     * @param contentType the MIME type ({@code java.lang.String}); unused by this backend
     * @return the absolute path reference {@code <media-root>/<key>} ({@code java.lang.String})
     * @throws com.neo.chat.exception.FileStorageException if the key is unsafe, escapes
     *                                                        the root, or the copy fails
     */
    @Override
    public String store(Path source, String key, String contentType) {
        if (!MediaKeys.isSafeKey(key)) {
            throw new FileStorageException("Invalid media key: " + key);
        }
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new FileStorageException("Media key escapes root: " + key);
        }
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new FileStorageException("Could not store media " + key + " " + e);
        }
        return target.toString();
    }

    /**
     * Resolve {@code reference} to a readable file under the media root and wrap it as a
     * streamable {@link MediaContent} (with probed content type and byte length).
     *
     * @param reference the stored reference ({@code java.lang.String})
     * @return an {@code java.util.Optional} of {@link MediaContent}; empty if the reference
     * is unresolvable, escapes the root, is unreadable, or an
     * {@code java.io.IOException} occurs
     */
    @Override
    public Optional<MediaContent> open(String reference) {
        Path p = resolve(reference);
        if (p == null || !Files.isReadable(p)) return Optional.empty();
        try {
            Resource resource = new UrlResource(p.toUri());
            String contentType = Files.probeContentType(p);
            return Optional.of(new MediaContent(resource, contentType, Files.size(p)));
        } catch (IOException e) {
            log.warn("Failed to open media file {}", p, e);
            return Optional.empty();
        }
    }

    /**
     * Expose the on-disk file for a reference as an in-place {@link LocalFile} (no copy;
     * its {@link LocalFile#close()} is a no-op).
     *
     * @param reference the stored reference ({@code java.lang.String})
     * @return an {@code java.util.Optional} of {@link LocalFile}; empty if the reference is
     * unresolvable or the file is not readable
     */
    @Override
    public Optional<LocalFile> localCopy(String reference) {
        Path p = resolve(reference);
        return (p != null && Files.isReadable(p)) ? Optional.of(new InPlaceLocalFile(p)) : Optional.empty();
    }

    /**
     * Best-effort delete of the file for {@code reference}; unresolvable references and
     * {@code java.io.IOException} are swallowed (logged), never thrown.
     *
     * @param reference the stored reference ({@code java.lang.String})
     */
    @Override
    public void delete(String reference) {
        Path p = resolve(reference);
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("Failed to delete media file {}", p, e);
        }
    }

    /**
     * Walk the media root (or the {@code prefix} subtree) and return one
     * {@link StoredObject} per regular file with a safe key, carrying the
     * {@code <media-root>/<key>} reference, size, last-modified time and guessed content
     * type. Best-effort — unreadable files are skipped and failures yield an empty list.
     *
     * @param prefix a sub-path under the root to restrict the walk, or null/blank for the
     *               whole store ({@code java.lang.String})
     * @return a {@code java.util.List} of {@link StoredObject} (never null)
     */
    @Override
    public List<StoredObject> list(String prefix) {
        Path base = root;
        if (prefix != null && !prefix.isBlank()) {
            Path p = root.resolve(prefix).normalize();
            if (p.startsWith(root)) base = p;
        }
        if (!Files.isDirectory(base)) return List.of();
        List<StoredObject> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(base)) {
            stream.filter(Files::isRegularFile).forEach(f -> {
                try {
                    String key = root.relativize(f).toString().replace('\\', '/');
                    if (!MediaKeys.isSafeKey(key)) return;
                    out.add(new StoredObject(
                            f.toString(),
                            key,
                            Files.size(f),
                            Files.getLastModifiedTime(f).toInstant(),
                            MediaKeys.contentTypeGuess(key)));
                } catch (IOException ignored) { /* skip unreadable file */ }
            });
        } catch (IOException e) {
            log.warn("Failed to list media under {}", base, e);
        }
        return out;
    }

    /**
     * Resolve a reference to a path under the media root, guarding against traversal.
     */
    private Path resolve(String reference) {
        String abs = MediaKeys.absolutePath(reference);
        if (abs == null) return null;
        Path p = Paths.get(abs).normalize();
        return p.startsWith(root) ? p : null;
    }

    /**
     * A file that already lives on disk — {@link #close()} must NOT delete it.
     */
    private record InPlaceLocalFile(Path path) implements LocalFile {
        @Override
        public void close() { /* in-place file — nothing to clean up */ }
    }
}
