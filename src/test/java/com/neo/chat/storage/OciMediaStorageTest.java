package com.neo.chat.storage;

import com.neo.chat.exception.FileStorageException;
import com.neo.chat.storage.MediaStorage.LocalFile;
import com.neo.chat.storage.MediaStorage.MediaContent;
import com.neo.chat.storage.MediaStorage.StoredObject;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.oracle.bmc.objectstorage.model.ListObjects;
import com.oracle.bmc.objectstorage.model.ObjectSummary;
import com.oracle.bmc.objectstorage.requests.DeleteObjectRequest;
import com.oracle.bmc.objectstorage.requests.GetObjectRequest;
import com.oracle.bmc.objectstorage.requests.ListObjectsRequest;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import com.oracle.bmc.objectstorage.responses.GetObjectResponse;
import com.oracle.bmc.objectstorage.responses.ListObjectsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link OciMediaStorage}. The OCI {@link ObjectStorageClient}
 * is mocked; SDK response models are built with their real builders. Covers put/get/delete/
 * list happy paths (request-field capture, pagination), reference→key resolution and skips,
 * downstream RuntimeException handling (fail-soft open/delete/list, fail-fast store→TM_170),
 * and temp-file cleanup on {@code localCopy}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OciMediaStorage (unit)")
class OciMediaStorageTest {

    private static final String NS = "tenancyns";
    private static final String BUCKET = "neochathub-media";
    private static final String ROOT = "/media";

    @Mock
    private ObjectStorageClient client;

    private OciMediaStorage storage;

    @TempDir
    Path tmp;

    @BeforeEach
    void setUp() {
        StorageProperties.Oci oci = new StorageProperties.Oci();
        oci.setNamespace(NS);
        oci.setBucket(BUCKET);
        StorageProperties props = new StorageProperties(ROOT, List.of(), oci);
        storage = new OciMediaStorage(client, props);
    }

    private Path sourceFile(String name, String content) throws IOException {
        Path p = tmp.resolve(name);
        Files.createDirectories(p.getParent() == null ? tmp : p.getParent());
        Files.writeString(p, content);
        return p;
    }

    private GetObjectResponse getResp(Long len, String contentType, byte[] body) {
        return GetObjectResponse.builder()
                .contentLength(len)
                .contentType(contentType)
                .inputStream(new ByteArrayInputStream(body))
                .build();
    }

    @Nested
    @DisplayName("store")
    class Store {

        @Test
        @DisplayName("uploads the object and returns the <root>/<key> reference")
        void uploadsAndReturnsReference() throws IOException {
            Path src = sourceFile("a.jpg", "hello");

            String ref = storage.store(src, "posts/a.jpg", "image/jpeg");

            assertThat(ref).isEqualTo("/media/posts/a.jpg");
            ArgumentCaptor<PutObjectRequest> cap = ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(client).putObject(cap.capture());
            PutObjectRequest req = cap.getValue();
            assertThat(req.getNamespaceName()).isEqualTo(NS);
            assertThat(req.getBucketName()).isEqualTo(BUCKET);
            assertThat(req.getObjectName()).isEqualTo("posts/a.jpg");
            assertThat(req.getContentType()).isEqualTo("image/jpeg");
            assertThat(req.getContentLength()).isEqualTo(5L);
        }

        @Test
        @DisplayName("rejects an unsafe key with TM_170 and never calls OCI")
        void rejectsUnsafeKey() throws IOException {
            Path src = sourceFile("a.jpg", "x");
            assertThatThrownBy(() -> storage.store(src, "../evil.jpg", "image/jpeg"))
                    .isInstanceOfSatisfying(FileStorageException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_170"));
            verify(client, never()).putObject(any());
        }

        @Test
        @DisplayName("wraps a client RuntimeException as TM_170")
        void wrapsClientFailure() throws IOException {
            Path src = sourceFile("a.jpg", "x");
            when(client.putObject(any())).thenThrow(new RuntimeException("boom"));

            assertThatThrownBy(() -> storage.store(src, "posts/a.jpg", "image/jpeg"))
                    .isInstanceOfSatisfying(FileStorageException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_170"));
        }

        @Test
        @DisplayName("wraps a source-open I/O failure as TM_170")
        void wrapsIoFailure() {
            Path missing = tmp.resolve("nope.bin");
            assertThatThrownBy(() -> storage.store(missing, "posts/a.jpg", "image/jpeg"))
                    .isInstanceOfSatisfying(FileStorageException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_170"));
            verify(client, never()).putObject(any());
        }
    }

    @Nested
    @DisplayName("open")
    class Open {

        @Test
        @DisplayName("returns content with the object's length and type")
        void opensObject() {
            when(client.getObject(any(GetObjectRequest.class)))
                    .thenReturn(getResp(4L, "video/mp4", "data".getBytes(StandardCharsets.UTF_8)));

            Optional<MediaContent> out = storage.open("/media/posts/a.mp4");

            assertThat(out).isPresent();
            assertThat(out.get().contentLength()).isEqualTo(4L);
            assertThat(out.get().contentType()).isEqualTo("video/mp4");
            ArgumentCaptor<GetObjectRequest> cap = ArgumentCaptor.forClass(GetObjectRequest.class);
            verify(client).getObject(cap.capture());
            assertThat(cap.getValue().getObjectName()).isEqualTo("posts/a.mp4");
        }

        @Test
        @DisplayName("null content-length maps to a -1 length but still resolves")
        void nullLengthMapsToMinusOne() {
            when(client.getObject(any(GetObjectRequest.class)))
                    .thenReturn(getResp(null, "image/png", "x".getBytes()));

            Optional<MediaContent> out = storage.open("/media/posts/a.png");

            assertThat(out).isPresent();
            assertThat(out.get().contentLength()).isEqualTo(-1L);
        }

        @Test
        @DisplayName("empty (no client call) for a reference that resolves to no key")
        void emptyForUnresolvableKey() {
            assertThat(storage.open("relative-no-slash.png")).isEmpty();
            verify(client, never()).getObject(any(GetObjectRequest.class));
        }

        @Test
        @DisplayName("empty when the client throws")
        void emptyOnClientFailure() {
            when(client.getObject(any(GetObjectRequest.class)))
                    .thenThrow(new RuntimeException("404"));

            assertThat(storage.open("/media/posts/a.jpg")).isEmpty();
        }
    }

    @Nested
    @DisplayName("localCopy")
    class LocalCopy {

        @Test
        @DisplayName("downloads to a temp file and close() deletes it")
        void downloadsToTemp() throws IOException {
            when(client.getObject(any(GetObjectRequest.class)))
                    .thenReturn(getResp(5L, "image/jpeg", "hello".getBytes(StandardCharsets.UTF_8)));

            Optional<LocalFile> lf = storage.localCopy("/media/posts/a.jpg");

            assertThat(lf).isPresent();
            Path p = lf.get().path();
            assertThat(Files.readString(p)).isEqualTo("hello");
            lf.get().close();
            assertThat(Files.exists(p)).as("temp download deleted on close()").isFalse();
        }

        @Test
        @DisplayName("empty (no client call) for an unresolvable reference")
        void emptyForUnresolvable() {
            assertThat(storage.localCopy("relative.jpg")).isEmpty();
            verify(client, never()).getObject(any(GetObjectRequest.class));
        }

        @Test
        @DisplayName("empty when the download fails")
        void emptyOnFailure() {
            when(client.getObject(any(GetObjectRequest.class)))
                    .thenThrow(new RuntimeException("boom"));

            assertThat(storage.localCopy("/media/posts/a.jpg")).isEmpty();
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("deletes the resolved object key")
        void deletes() {
            storage.delete("/media/posts/a.jpg");

            ArgumentCaptor<DeleteObjectRequest> cap = ArgumentCaptor.forClass(DeleteObjectRequest.class);
            verify(client).deleteObject(cap.capture());
            DeleteObjectRequest req = cap.getValue();
            assertThat(req.getNamespaceName()).isEqualTo(NS);
            assertThat(req.getBucketName()).isEqualTo(BUCKET);
            assertThat(req.getObjectName()).isEqualTo("posts/a.jpg");
        }

        @Test
        @DisplayName("no-op (no client call) for an unresolvable reference")
        void noopForUnresolvable() {
            storage.delete("relative.jpg");
            verify(client, never()).deleteObject(any());
        }

        @Test
        @DisplayName("swallows a client failure (never throws)")
        void swallowsFailure() {
            when(client.deleteObject(any())).thenThrow(new RuntimeException("boom"));
            storage.delete("/media/posts/a.jpg"); // must not throw
            verify(client).deleteObject(any());
        }
    }

    @Nested
    @DisplayName("list")
    class ListObjectsTest {

        private ListObjectsResponse page(List<ObjectSummary> objs, String next) {
            ListObjects lo = ListObjects.builder().objects(objs).nextStartWith(next).build();
            return ListObjectsResponse.builder().listObjects(lo).build();
        }

        private ObjectSummary summary(String name, Long size, Date modified, Date created) {
            return ObjectSummary.builder().name(name).size(size)
                    .timeModified(modified).timeCreated(created).build();
        }

        @Test
        @DisplayName("maps summaries to StoredObjects and passes the prefix")
        void mapsSummaries() {
            Date when = new Date(1_700_000_000_000L);
            when(client.listObjects(any(ListObjectsRequest.class)))
                    .thenReturn(page(List.of(summary("posts/a.jpg", 12L, when, null)), null));

            List<StoredObject> out = storage.list("posts");

            assertThat(out).hasSize(1);
            StoredObject so = out.get(0);
            assertThat(so.key()).isEqualTo("posts/a.jpg");
            assertThat(so.reference()).isEqualTo("/media/posts/a.jpg");
            assertThat(so.size()).isEqualTo(12L);
            assertThat(so.lastModified()).isEqualTo(when.toInstant());
            assertThat(so.contentType()).isEqualTo("image/jpeg");

            ArgumentCaptor<ListObjectsRequest> cap = ArgumentCaptor.forClass(ListObjectsRequest.class);
            verify(client).listObjects(cap.capture());
            assertThat(cap.getValue().getPrefix()).isEqualTo("posts");
        }

        @Test
        @DisplayName("follows pagination via nextStartWith across pages")
        void followsPagination() {
            when(client.listObjects(any(ListObjectsRequest.class)))
                    .thenReturn(page(List.of(summary("a.jpg", 1L, new Date(1L), null)), "cursor"))
                    .thenReturn(page(List.of(summary("b.jpg", 2L, new Date(2L), null)), null));

            List<StoredObject> out = storage.list(null);

            assertThat(out).extracting(StoredObject::key).containsExactly("a.jpg", "b.jpg");
            verify(client, Mockito.times(2)).listObjects(any());
        }

        @Test
        @DisplayName("skips unsafe keys and defaults null size to 0")
        void skipsUnsafeAndDefaultsSize() {
            when(client.listObjects(any(ListObjectsRequest.class)))
                    .thenReturn(page(List.of(
                            summary("../escape.jpg", 5L, new Date(1L), null),
                            summary("ok.jpg", null, null, new Date(3L))), null));

            List<StoredObject> out = storage.list(null);

            assertThat(out).extracting(StoredObject::key).containsExactly("ok.jpg");
            assertThat(out.get(0).size()).isEqualTo(0L);
            assertThat(out.get(0).lastModified()).isEqualTo(new Date(3L).toInstant());
        }

        @Test
        @DisplayName("empty when getListObjects() is null")
        void emptyWhenNullListObjects() {
            when(client.listObjects(any(ListObjectsRequest.class)))
                    .thenReturn(ListObjectsResponse.builder().listObjects(null).build());

            assertThat(storage.list(null)).isEmpty();
        }

        @Test
        @DisplayName("empty when the client throws")
        void emptyOnClientFailure() {
            when(client.listObjects(any(ListObjectsRequest.class)))
                    .thenThrow(new RuntimeException("boom"));

            assertThat(storage.list(null)).isEmpty();
        }
    }

    @Test
    @DisplayName("unsafe store key does not touch the OCI client at all")
    void unsafeKeyNoClientInteraction() throws IOException {
        Path src = sourceFile("a.jpg", "x");
        assertThatThrownBy(() -> storage.store(src, "/abs/key.jpg", "image/jpeg"))
                .isInstanceOf(FileStorageException.class);
        verifyNoInteractions(client);
    }
}
