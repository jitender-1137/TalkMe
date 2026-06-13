package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.repository.RoleRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.StorageService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test-document.txt",
                MediaType.TEXT_PLAIN_VALUE,
                "Hello World content".getBytes()
        );

        Mockito.when(storageService.storeFile(any(), anyString()))
                .thenReturn("http://example.com/uploads/test-document.txt");

        Cookie csrfCookie = new Cookie("csrf_token", "test-token-value");

        mockMvc.perform(multipart("/uploads")
                .file(file)
                .param("type", "document")
                .with(user(testUserDetails()))
                .cookie(csrfCookie)
                .header("X-CSRF-Token", "test-token-value"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("File uploaded successfully"))
                .andExpect(jsonPath("$.messageCode").value("TM_167"))
                .andExpect(jsonPath("$.data.url").value("http://example.com/uploads/test-document.txt"))
                .andExpect(jsonPath("$.data.fileName").value("test-document.txt"))
                .andExpect(jsonPath("$.data.fileSize").value(19))
                .andExpect(jsonPath("$.data.mimeType").value(MediaType.TEXT_PLAIN_VALUE));
    }

    @Test
    void testUploadFileUnauthorized() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test-document.txt",
                MediaType.TEXT_PLAIN_VALUE,
                "Hello World content".getBytes()
        );

        Cookie csrfCookie = new Cookie("csrf_token", "test-token-value");

        mockMvc.perform(multipart("/uploads")
                .file(file)
                .param("type", "document")
                .cookie(csrfCookie)
                .header("X-CSRF-Token", "test-token-value"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testUploadFileCsrfTokenMismatch() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test-document.txt",
                MediaType.TEXT_PLAIN_VALUE,
                "Hello World content".getBytes()
        );

        Cookie csrfCookie = new Cookie("csrf_token", "test-token-value");

        mockMvc.perform(multipart("/uploads")
                .file(file)
                .param("type", "document")
                .with(user(testUserDetails()))
                .cookie(csrfCookie)
                .header("X-CSRF-Token", "wrong-token-value"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.messageCode").value("CSRF_TOKEN_INVALID"));
    }

    @Test
    void testUploadFileMissingFile() throws Exception {
        Cookie csrfCookie = new Cookie("csrf_token", "test-token-value");

        mockMvc.perform(multipart("/uploads")
                .param("type", "document")
                .with(user(testUserDetails()))
                .cookie(csrfCookie)
                .header("X-CSRF-Token", "test-token-value"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.messageCode").value("TM_002"));
    }

    @Test
    void testUploadFileMissingType() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test-document.txt",
                MediaType.TEXT_PLAIN_VALUE,
                "Hello World content".getBytes()
        );

        Cookie csrfCookie = new Cookie("csrf_token", "test-token-value");

        mockMvc.perform(multipart("/uploads")
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
        tempTestFilePath = Paths.get("test-uploads/temp-test-file.txt");
        Files.createDirectories(tempTestFilePath.getParent());
        Files.writeString(tempTestFilePath, "Media file test contents.");

        mockMvc.perform(get("/uploads/media")
                .param("path", tempTestFilePath.toAbsolutePath().toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("Media file test contents."));
    }

    @Test
    void testGetMediaNotFound() throws Exception {
        mockMvc.perform(get("/uploads/media")
                .param("path", "test-uploads/non-existent-file.txt"))
                .andExpect(status().isNotFound());
    }
}
