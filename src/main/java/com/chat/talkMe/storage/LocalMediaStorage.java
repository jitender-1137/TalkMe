package com.chat.talkMe.storage;

import com.chat.talkMe.exception.FileStorageException;
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
import java.util.Optional;

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

    @Override
    public Optional<LocalFile> localCopy(String reference) {
        Path p = resolve(reference);
        return (p != null && Files.isReadable(p)) ? Optional.of(new InPlaceLocalFile(p)) : Optional.empty();
    }

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

    @Override
    public java.util.List<StoredObject> list(String prefix) {
        Path base = root;
        if (prefix != null && !prefix.isBlank()) {
            Path p = root.resolve(prefix).normalize();
            if (p.startsWith(root)) base = p;
        }
        if (!Files.isDirectory(base)) return java.util.List.of();
        java.util.List<StoredObject> out = new java.util.ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(base)) {
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

    /** Resolve a reference to a path under the media root, guarding against traversal. */
    private Path resolve(String reference) {
        String abs = MediaKeys.absolutePath(reference);
        if (abs == null) return null;
        Path p = Paths.get(abs).normalize();
        return p.startsWith(root) ? p : null;
    }

    /** A file that already lives on disk — {@link #close()} must NOT delete it. */
    private record InPlaceLocalFile(Path path) implements LocalFile {
        @Override
        public void close() { /* in-place file — nothing to clean up */ }
    }
}
