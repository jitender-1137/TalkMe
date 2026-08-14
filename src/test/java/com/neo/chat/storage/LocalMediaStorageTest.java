package com.neo.chat.storage;

import com.neo.chat.exception.FileStorageException;
import com.neo.chat.storage.MediaStorage.LocalFile;
import com.neo.chat.storage.MediaStorage.MediaContent;
import com.neo.chat.storage.MediaStorage.StoredObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-file-I/O unit test for {@link LocalMediaStorage} using a JUnit5 {@code @TempDir}
 * media root. Covers store (safe/unsafe keys, nested dirs, source-missing failure),
 * open/localCopy/delete resolution, {@code path=} rewrite handling, traversal rejection,
 * and prefix listing.
 */
@DisplayName("LocalMediaStorage (unit, real file I/O)")
class LocalMediaStorageTest {

    @TempDir
    Path rootDir;

    private LocalMediaStorage storage;

    @BeforeEach
    void setUp() {
        StorageProperties props = new StorageProperties();
        props.setMediaRoot(rootDir.toString());
        storage = new LocalMediaStorage(props);
    }

    /**
     * Creates a source file in the temp area (outside the store) with the given bytes.
     */
    private Path sourceFile(String name, String content) throws IOException {
        Path src = Files.createTempFile("src-", "-" + name);
        Files.writeString(src, content);
        return src;
    }

    @Nested
    @DisplayName("constructor")
    class Constructor {

        @Test
        @DisplayName("creates the media root directory")
        void createsRoot() {
            assertThat(Files.isDirectory(rootDir)).isTrue();
        }
    }

    @Nested
    @DisplayName("store")
    class Store {

        @Test
        @DisplayName("copies the source under root/key and returns the absolute target path")
        void storesFile() throws IOException {
            Path src = sourceFile("a.txt", "hello");

            String ref = storage.store(src, "posts/a.txt", "text/plain");

            Path target = rootDir.resolve("posts/a.txt");
            assertThat(ref).isEqualTo(target.toString());
            assertThat(Files.readString(target)).isEqualTo("hello");
        }

        @Test
        @DisplayName("creates intermediate directories for a nested key")
        void createsParentDirs() throws IOException {
            Path src = sourceFile("v.mp4", "bytes");

            storage.store(src, "conversations/uuid/deep/v.mp4", "video/mp4");

            assertThat(Files.exists(rootDir.resolve("conversations/uuid/deep/v.mp4"))).isTrue();
        }

        @Test
        @DisplayName("overwrites an existing object at the same key")
        void overwrites() throws IOException {
            storage.store(sourceFile("a.txt", "first"), "k/a.txt", "text/plain");
            storage.store(sourceFile("a.txt", "second"), "k/a.txt", "text/plain");

            assertThat(Files.readString(rootDir.resolve("k/a.txt"))).isEqualTo("second");
        }

        @Test
        @DisplayName("rejects a key containing '..' with TM_170")
        void rejectsTraversalKey() throws IOException {
            Path src = sourceFile("a.txt", "x");
            assertThatThrownBy(() -> storage.store(src, "../escape.txt", "text/plain"))
                    .isInstanceOfSatisfying(FileStorageException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_170"));
        }

        @Test
        @DisplayName("rejects an absolute key with TM_170")
        void rejectsAbsoluteKey() throws IOException {
            Path src = sourceFile("a.txt", "x");
            assertThatThrownBy(() -> storage.store(src, "/etc/passwd", "text/plain"))
                    .isInstanceOfSatisfying(FileStorageException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_170"));
        }

        @Test
        @DisplayName("rejects a blank key with TM_170")
        void rejectsBlankKey() throws IOException {
            Path src = sourceFile("a.txt", "x");
            assertThatThrownBy(() -> storage.store(src, "   ", "text/plain"))
                    .isInstanceOfSatisfying(FileStorageException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_170"));
        }

        @Test
        @DisplayName("wraps an I/O failure (missing source) as TM_170")
        void wrapsIoFailure() {
            Path missing = rootDir.resolve("does-not-exist.src");
            assertThatThrownBy(() -> storage.store(missing, "k/a.txt", "text/plain"))
                    .isInstanceOfSatisfying(FileStorageException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_170"));
        }
    }

    @Nested
    @DisplayName("open")
    class Open {

        @Test
        @DisplayName("returns content with size for a stored reference")
        void opensStored() throws IOException {
            String ref = storage.store(sourceFile("a.txt", "hello"), "posts/a.txt", "text/plain");

            Optional<MediaContent> opened = storage.open(ref);

            assertThat(opened).isPresent();
            assertThat(opened.get().contentLength()).isEqualTo(5);
            assertThat(opened.get().resource().exists()).isTrue();
        }

        @Test
        @DisplayName("resolves the rewritten ?path= form")
        void opensPathParamForm() throws IOException {
            String ref = storage.store(sourceFile("a.txt", "hi"), "posts/a.txt", "text/plain");
            String encoded = "/serve?path=" + URLEncoder.encode(ref, StandardCharsets.UTF_8);

            assertThat(storage.open(encoded)).isPresent();
        }

        @Test
        @DisplayName("empty for a relative (unresolvable) reference")
        void emptyForRelative() {
            assertThat(storage.open("relative/a.txt")).isEmpty();
        }

        @Test
        @DisplayName("empty for an absolute path outside the media root")
        void emptyForOutsideRoot() {
            assertThat(storage.open("/etc/passwd")).isEmpty();
        }

        @Test
        @DisplayName("empty when the file does not exist")
        void emptyForMissing() {
            assertThat(storage.open(rootDir.resolve("nope.txt").toString())).isEmpty();
        }
    }

    @Nested
    @DisplayName("localCopy")
    class LocalCopy {

        @Test
        @DisplayName("resolves in place and close() does NOT delete the file")
        void inPlaceCopy() throws IOException {
            String ref = storage.store(sourceFile("a.txt", "hi"), "posts/a.txt", "text/plain");

            Optional<LocalFile> lf = storage.localCopy(ref);

            assertThat(lf).isPresent();
            Path p = lf.get().path();
            assertThat(Files.exists(p)).isTrue();
            lf.get().close();
            assertThat(Files.exists(p)).as("in-place file survives close()").isTrue();
        }

        @Test
        @DisplayName("empty for a missing reference")
        void emptyForMissing() {
            assertThat(storage.localCopy(rootDir.resolve("nope.txt").toString())).isEmpty();
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("removes an existing file")
        void deletesExisting() throws IOException {
            String ref = storage.store(sourceFile("a.txt", "hi"), "posts/a.txt", "text/plain");

            storage.delete(ref);

            assertThat(Files.exists(rootDir.resolve("posts/a.txt"))).isFalse();
        }

        @Test
        @DisplayName("no-op (no throw) for a missing file")
        void noopForMissing() {
            String ref = rootDir.resolve("nope.txt").toString();
            storage.delete(ref); // must not throw
            assertThat(Files.exists(rootDir.resolve("nope.txt"))).isFalse();
        }

        @Test
        @DisplayName("no-op for an unresolvable reference")
        void noopForUnresolvable() {
            storage.delete("relative.txt"); // resolve() → null → no-op, no throw
        }
    }

    @Nested
    @DisplayName("list")
    class ListObjects {

        @Test
        @DisplayName("lists every regular file under root with its key")
        void listsAll() throws IOException {
            storage.store(sourceFile("a.txt", "a"), "posts/a.txt", "text/plain");
            storage.store(sourceFile("b.txt", "bb"), "conversations/b.txt", "text/plain");

            List<StoredObject> all = storage.list(null);

            assertThat(all).extracting(StoredObject::key)
                    .containsExactlyInAnyOrder("posts/a.txt", "conversations/b.txt");
            assertThat(all).allSatisfy(o -> assertThat(o.size()).isGreaterThan(0));
        }

        @Test
        @DisplayName("scopes the listing to a prefix directory")
        void listsPrefix() throws IOException {
            storage.store(sourceFile("a.txt", "a"), "posts/a.txt", "text/plain");
            storage.store(sourceFile("b.txt", "b"), "conversations/b.txt", "text/plain");

            List<StoredObject> posts = storage.list("posts");

            assertThat(posts).extracting(StoredObject::key).containsExactly("posts/a.txt");
        }

        @Test
        @DisplayName("empty for a prefix that is not an existing directory")
        void emptyForMissingPrefix() {
            assertThat(storage.list("no-such-dir")).isEmpty();
        }
    }
}
