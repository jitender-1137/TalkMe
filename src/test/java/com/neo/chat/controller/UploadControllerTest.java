package com.neo.chat.controller;

import com.neo.chat.domain.Role;
import com.neo.chat.domain.User;
import com.neo.chat.repository.RoleRepository;
import com.neo.chat.repository.UserRepository;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.StorageService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-context integration test for {@code UploadController}.
 *
 * <p>Unlike the sibling {@code *ControllerUnitTest} classes (standalone MockMvc + mocked service),
 * this boots the whole {@link org.springframework.boot.test.context.SpringBootTest} application
 * context under the {@code test} profile and wires MockMvc through the REAL Spring Security filter
 * chain via {@link SecurityMockMvcConfigurers#springSecurity()}. Only {@link StorageService} is
 * replaced with a {@link MockitoBean}; {@link UserRepository}/{@link RoleRepository} are the real
 * beans, so the test seeds and tears down a persisted {@link User}.
 *
 * <p>Because the full chain is active, authentication (missing principal → 401) and CSRF
 * (cookie/header mismatch → 403 {@code CSRF_TOKEN_INVALID}) are genuinely exercised here rather
 * than deferred. Multipart uploads carry a matching {@code csrf_token} cookie + {@code X-CSRF-Token}
 * header. Media retrieval is confined to the configured {@code storage.media-root}
 * ({@code ./build/test-media}) by the controller's path-traversal guard.
 */
@SpringBootTest
@ActiveProfiles("test")
public class UploadControllerTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @MockitoBean
    private StorageService storageService;

    private MockMvc mockMvc;
    private User testUser;
    private Path tempTestFilePath;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(Role.builder().name("ROLE_USER").build()));

        testUser = User.builder()
                .username("testuser")
                .email("testuser@example.com")
                .name("Test User")
                .isGuest(false)
                .isVerified(true)
                .roles(Set.of(userRole))
                .build();
        testUser = userRepository.save(testUser);
    }

    @AfterEach
    void tearDown() throws IOException {
        userRepository.deleteAll();
        if (tempTestFilePath != null) {
            Files.deleteIfExists(tempTestFilePath);
        }
    }

    private CustomUserDetails testUserDetails() {
        return new CustomUserDetails(testUser);
    }

    @Test
    void testUploadFileSuccess() throws Exception {
        // UploadValidator verifies the REAL content type from magic bytes, so the file must
        // actually be what its declared type says — use a real 8-byte PNG signature as an image.
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test-image.png",
                MediaType.IMAGE_PNG_VALUE,
                png
        );

        Mockito.when(storageService.storeFile(any(), anyString(), anyString()))
                .thenReturn("http://example.com/uploads/test-image.png");

        Cookie csrfCookie = new Cookie("csrf_token", "test-token-value");

        mockMvc.perform(multipart("/api/v1/uploads")
                        .file(file)
                        .param("type", "image")
                        .with(user(testUserDetails()))
                        .cookie(csrfCookie)
                        .header("X-CSRF-Token", "test-token-value"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("File uploaded successfully"))
                .andExpect(jsonPath("$.messageCode").value("TM_167"))
                .andExpect(jsonPath("$.data.url").value("http://example.com/uploads/test-image.png"))
                .andExpect(jsonPath("$.data.fileName").value("test-image.png"))
                .andExpect(jsonPath("$.data.fileSize").value(png.length))
                .andExpect(jsonPath("$.data.mimeType").value(MediaType.IMAGE_PNG_VALUE));
    }

    /**
     * No authenticated principal (no {@code .with(user(...))}) → the security filter chain rejects
     * the multipart upload with 401 before it reaches the controller.
     */
    @Test
    void testUploadFileUnauthorized() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test-document.txt",
                MediaType.TEXT_PLAIN_VALUE,
                "Hello World content".getBytes()
        );

        Cookie csrfCookie = new Cookie("csrf_token", "test-token-value");

        mockMvc.perform(multipart("/api/v1/uploads")
                        .file(file)
                        .param("type", "document")
                        .cookie(csrfCookie)
                        .header("X-CSRF-Token", "test-token-value"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Authenticated but the {@code X-CSRF-Token} header does not match the {@code csrf_token}
     * cookie → CSRF filter returns 403 with messageCode {@code CSRF_TOKEN_INVALID}.
     */
    @Test
    void testUploadFileCsrfTokenMismatch() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test-document.txt",
                MediaType.TEXT_PLAIN_VALUE,
                "Hello World content".getBytes()
        );

        Cookie csrfCookie = new Cookie("csrf_token", "test-token-value");

        mockMvc.perform(multipart("/api/v1/uploads")
                        .file(file)
                        .param("type", "document")
                        .with(user(testUserDetails()))
                        .cookie(csrfCookie)
                        .header("X-CSRF-Token", "wrong-token-value"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.messageCode").value("CSRF_TOKEN_INVALID"));
    }

    /**
     * Authenticated + valid CSRF but the required {@code file} part is absent → surfaces via the
     * catch-all as 500 / {@code TM_002} (pins current behaviour; there is no dedicated handler).
     */
    @Test
    void testUploadFileMissingFile() throws Exception {
        Cookie csrfCookie = new Cookie("csrf_token", "test-token-value");

        mockMvc.perform(multipart("/api/v1/uploads")
                        .param("type", "document")
                        .with(user(testUserDetails()))
                        .cookie(csrfCookie)
                        .header("X-CSRF-Token", "test-token-value"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.messageCode").value("TM_002"));
    }

    /**
     * File present but the required {@code type} request param is absent → 500 / {@code TM_002}
     * via the catch-all (mirrors {@link #testUploadFileMissingFile()}).
     */
    @Test
    void testUploadFileMissingType() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test-document.txt",
                MediaType.TEXT_PLAIN_VALUE,
                "Hello World content".getBytes()
        );

        Cookie csrfCookie = new Cookie("csrf_token", "test-token-value");

        mockMvc.perform(multipart("/api/v1/uploads")
                        .file(file)
                        .with(user(testUserDetails()))
                        .cookie(csrfCookie)
                        .header("X-CSRF-Token", "test-token-value"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.messageCode").value("TM_002"));
    }

    @Test
    void testGetMediaSuccess() throws Exception {
        // The media backend only serves files UNDER its configured media root
        // (storage.media-root = ./build/test-media in the test profile); the traversal guard
        // rejects anything outside it. Write the fixture there and request it by absolute path.
        tempTestFilePath = Paths.get("build/test-media/temp-test-file.txt").toAbsolutePath();
        Files.createDirectories(tempTestFilePath.getParent());
        Files.writeString(tempTestFilePath, "Media file test contents.");

        mockMvc.perform(get("/api/v1/uploads/media")
                        .param("path", tempTestFilePath.toString()))
                .andExpect(status().isOk())
                .andExpect(content().string("Media file test contents."));
    }

    /**
     * A path that resolves to no existing file (relative, outside the served fixture) → 404.
     */
    @Test
    void testGetMediaNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/uploads/media")
                        .param("path", "test-uploads/non-existent-file.txt"))
                .andExpect(status().isNotFound());
    }
}
