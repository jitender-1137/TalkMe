package com.neo.chat.controller;

import com.neo.chat.domain.Chat;
import com.neo.chat.domain.ChatMember;
import com.neo.chat.domain.Role;
import com.neo.chat.domain.User;
import com.neo.chat.exception.GlobalExceptionHandler;
import com.neo.chat.moderation.ContentModerationService;
import com.neo.chat.moderation.ModerationResult;
import com.neo.chat.repository.ChatMemberRepository;
import com.neo.chat.repository.ChatRepository;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.MediaAssetService;
import com.neo.chat.service.StorageService;
import com.neo.chat.storage.MediaStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link UploadController}.
 *
 * <p>Standalone {@link MockMvc} with mocked collaborators ({@link StorageService},
 * {@link MediaStorage}, {@link ChatRepository}, {@link ChatMemberRepository},
 * {@link ContentModerationService}) and the real {@link GlobalExceptionHandler}. The
 * {@link com.neo.chat.util.UploadValidator} magic-byte check is a STATIC util and runs
 * for real, so success-path fixtures carry genuine signature bytes.
 *
 * <p>No {@code Pageable} and no JSON request body are involved (the upload endpoint is
 * pure multipart form-data, the serve endpoint is a query param), so no Pageable resolver
 * and no tolerant Jackson converter are registered — only the
 * {@link AuthenticationPrincipalArgumentResolver} for {@code @AuthenticationPrincipal}.
 *
 * <p><b>Scope boundary:</b> filter-chain authentication/authorization (JWT, roles, CSRF,
 * the global multipart size cap that yields {@link org.springframework.web.multipart.MaxUploadSizeExceededException})
 * is enforced by the security/servlet layer and is out of scope here — those are covered by
 * the integration test. This test pins the controller's request/response wiring, its
 * per-type size guard, subdir resolution, moderation gate, and delegation to the services.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UploadController (unit)")
class UploadControllerUnitTest {

    private static final String BASE = "/uploads";
    private static final String MEDIA = "/uploads/media";
    private static final String SUCCESS_CODE = "TM_167";
    private static final String INTERNAL_ERROR_CODE = "TM_002";
    private static final String USER_UUID = "11111111-1111-1111-1111-111111111111";

    @Mock
    private StorageService storageService;
    @Mock
    private MediaStorage mediaStorage;
    @Mock
    private ChatRepository chatRepository;
    @Mock
    private ChatMemberRepository chatMemberRepository;
    @Mock
    private ContentModerationService moderationService;
    @Mock
    private MediaAssetService mediaAssetService;
    @Mock
    private com.neo.chat.storage.StorageProperties storageProperties;
    @Mock
    private com.neo.chat.security.JwtTokenProvider tokenProvider;
    @Mock
    private com.neo.chat.repository.UserRepository userRepository;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        UploadController controller = new UploadController(
                storageService, mediaStorage, chatRepository, chatMemberRepository, moderationService,
                mediaAssetService, storageProperties, tokenProvider, userRepository);
        org.mockito.Mockito.lenient().when(storageProperties.getMediaRoot()).thenReturn("/media");
        org.mockito.Mockito.lenient().when(storageProperties.getLegacyMediaRoots()).thenReturn(java.util.List.of());

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .setValidator(validator)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();

        Role role = Role.builder().name("ROLE_USER").build();
        testUser = User.builder()
                .username("testuser").email("t@e.com").name("Test User")
                .isGuest(false).roles(Set.of(role))
                .build();
        // resolveSubdir reads user.getUuid() for the profile/post/story/lobby/conversation branches.
        testUser.setUuid(UUID.fromString(USER_UUID));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void authenticate() {
        CustomUserDetails principal = new CustomUserDetails(testUser);
        Authentication auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /**
     * A 64-byte buffer beginning with the real 8-byte PNG signature — passes UploadValidator for type=image.
     */
    private static byte[] pngBytes() {
        byte[] full = new byte[64];
        byte[] sig = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(sig, 0, full, 0, sig.length);
        return full;
    }

    private static MockMultipartFile validPng() {
        return new MockMultipartFile("file", "pic.png", "image/png", pngBytes());
    }

    private static ModerationResult explicitResult() {
        return ModerationResult.explicit(ModerationResult.Category.NSFW_IMAGE, 0.99, List.of());
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /uploads — per-type size guard (runs FIRST, before magic-byte check)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /uploads — size guard")
    class SizeGuard {

        @Test
        void shouldReturn413AndSkipEverythingWhenImageExceeds2Mb() throws Exception {
            authenticate();
            // 2 MB + 1 byte, type=image. validateSize() throws BEFORE UploadValidator or any
            // service is touched, so the bytes need no valid PNG signature at all.
            byte[] tooBig = new byte[2 * 1024 * 1024 + 1];
            MockMultipartFile file = new MockMultipartFile("file", "big.png", "image/png", tooBig);

            mockMvc.perform(multipart(BASE).file(file).param("type", "image"))
                    .andExpect(status().isPayloadTooLarge())
                    .andExpect(jsonPath("$.messageCode").value("TM_491"));

            verifyNoInteractions(storageService, moderationService, chatRepository, chatMemberRepository);
        }

        @Test
        void shouldReturn413AndSkipEverythingWhenVideoExceeds30Mb() throws Exception {
            authenticate();
            byte[] tooBig = new byte[30 * 1024 * 1024 + 1];
            MockMultipartFile file = new MockMultipartFile("file", "big.mp4", "video/mp4", tooBig);

            mockMvc.perform(multipart(BASE).file(file).param("type", "video"))
                    .andExpect(status().isPayloadTooLarge())
                    .andExpect(jsonPath("$.messageCode").value("TM_492"));

            verifyNoInteractions(storageService, moderationService, chatRepository, chatMemberRepository);
        }

        @Test
        void shouldTreatImageContentTypeAsImageEvenWhenTypeDiffers() throws Exception {
            authenticate();
            // type="file" but Content-Type image/* → still capped at the 2 MB image limit.
            byte[] tooBig = new byte[2 * 1024 * 1024 + 1];
            MockMultipartFile file = new MockMultipartFile("file", "x.bin", "image/png", tooBig);

            mockMvc.perform(multipart(BASE).file(file).param("type", "file"))
                    .andExpect(status().isPayloadTooLarge())
                    .andExpect(jsonPath("$.messageCode").value("TM_491"));

            verifyNoInteractions(storageService);
        }

        @Test
        void fileTypeWithNullContentTypeIsNeitherImageNorVideoSoSizeGuardPasses() throws Exception {
            authenticate();
            // type="file" AND a null Content-Type → in validateSize both isImage and isVideo evaluate
            // their (ct != null) sub-condition to FALSE, so neither size cap fires. The upload then
            // fails the magic-byte DOCUMENT check (PNG bytes) with 415 — but the size guard's
            // ct==null branches (lines 138 & 139) have been exercised first.
            MockMultipartFile file = new MockMultipartFile("file", "x.bin", null, pngBytes());

            mockMvc.perform(multipart(BASE).file(file).param("type", "file"))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.messageCode").value("TM_495"));

            verifyNoInteractions(storageService);
        }

        @Test
        void videoByContentTypeUnderLimitPassesSizeGuard() throws Exception {
            authenticate();
            // type="file" but Content-Type video/* → isVideo becomes true via its (ct != null &&
            // startsWith("video/")) sub-condition; 64 bytes is far under the 30 MB cap so the video
            // size check (line 143) evaluates isVideo=true with size-not-exceeded and does not throw.
            // The DOCUMENT magic-byte check then rejects the PNG bytes → 415.
            MockMultipartFile file = new MockMultipartFile("file", "clip.bin", "video/mp4", pngBytes());

            mockMvc.perform(multipart(BASE).file(file).param("type", "file"))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.messageCode").value("TM_495"));

            verifyNoInteractions(storageService);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /uploads — success path & response wiring
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /uploads — success")
    class UploadSuccess {

        @Test
        void shouldReturn200WithUploadResponseAndForwardFileTypeSubdir() throws Exception {
            authenticate();
            when(storageService.storeFile(any(), any(), any())).thenReturn("conversations/x/rand.png");

            // context omitted → resolveSubdir returns "others"; "conversation"-family moderation
            // is NOT applied, so moderationService is never consulted here.
            mockMvc.perform(multipart(BASE).file(validPng()).param("type", "image"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("File uploaded successfully"))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.data.url").value("conversations/x/rand.png"))
                    .andExpect(jsonPath("$.data.fileName").value("pic.png"))
                    .andExpect(jsonPath("$.data.mimeType").value("image/png"))
                    // stored file does not exist on disk → falls back to the multipart size (64 bytes).
                    .andExpect(jsonPath("$.data.fileSize").value(64));

            ArgumentCaptor<MultipartFile> fileCap =
                    ArgumentCaptor.forClass(MultipartFile.class);
            ArgumentCaptor<String> typeCap = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> subdirCap = ArgumentCaptor.forClass(String.class);
            verify(storageService).storeFile(fileCap.capture(), typeCap.capture(), subdirCap.capture());
            assertThat(fileCap.getValue().getOriginalFilename()).isEqualTo("pic.png");
            assertThat(typeCap.getValue()).isEqualTo("image");
            assertThat(subdirCap.getValue()).isEqualTo("others");

            verifyNoInteractions(moderationService);
        }

        @Test
        void shouldReturn500WhenStorageServiceThrows() throws Exception {
            authenticate();
            when(storageService.storeFile(any(), any(), any())).thenThrow(new RuntimeException("disk full"));

            mockMvc.perform(multipart(BASE).file(validPng()).param("type", "image"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }

        @Test
        void shouldReportActualOnDiskSizeWhenStoredFileExists() throws Exception {
            authenticate();
            // storeFile returns a path to a REAL file → Files.exists(stored) is true (line 81) and
            // the reported size comes from Files.size(stored) (line 82), NOT the 64-byte multipart.
            Path stored = Files.createTempFile("upload-unit-", ".png");
            Files.write(stored, new byte[10]);
            try {
                when(storageService.storeFile(any(), any(), any())).thenReturn(stored.toString());

                mockMvc.perform(multipart(BASE).file(validPng()).param("type", "image"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.fileSize").value(10));
            } finally {
                Files.deleteIfExists(stored);
            }
        }

        @Test
        void shouldFallBackToMultipartSizeWhenStoredPathIsInvalid() throws Exception {
            authenticate();
            // A NUL byte makes Paths.get(url) throw InvalidPathException, which the try/catch swallows
            // (line 84) → the response falls back to the original multipart size (64 bytes).
            when(storageService.storeFile(any(), any(), any())).thenReturn("bad\0path.png");

            mockMvc.perform(multipart(BASE).file(validPng()).param("type", "image"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.fileSize").value(64));

        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /uploads — content moderation gate (profile/post/story only)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /uploads — moderation")
    class Moderation {

        @Test
        void shouldReturn422AndNotStoreWhenModeratedContextIsExplicit() throws Exception {
            authenticate();
            when(moderationService.moderateUpload(any())).thenReturn(explicitResult());

            mockMvc.perform(multipart(BASE).file(validPng()).param("type", "image").param("context", "profile"))
                    // ContentModerationException → GlobalExceptionHandler.handleServiceException → 422 / TM_490.
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.messageCode").value("TM_490"));

            verify(storageService, never()).storeFile(any(), any(), any());
        }

        @Test
        void shouldProceedAndStoreUnderProfileSubdirWhenModeratedContextIsClean() throws Exception {
            authenticate();
            when(moderationService.moderateUpload(any())).thenReturn(ModerationResult.clean());
            when(storageService.storeFile(any(), any(), any())).thenReturn("profiles/uid/rand.png");

            mockMvc.perform(multipart(BASE).file(validPng()).param("type", "image").param("context", "profile"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE));

            ArgumentCaptor<String> subdirCap = ArgumentCaptor.forClass(String.class);
            verify(storageService).storeFile(any(), eq("image"), subdirCap.capture());
            assertThat(subdirCap.getValue()).isEqualTo("profiles/" + USER_UUID);
        }

        @Test
        void shouldNotModerateConversationContext() throws Exception {
            authenticate();
            when(storageService.storeFile(any(), any(), any())).thenReturn("others/rand.png");

            mockMvc.perform(multipart(BASE).file(validPng()).param("type", "image").param("context", "stranger"))
                    .andExpect(status().isOk());

            // stranger/lobby/conversation are intentionally excluded from the up-front NSFW block.
            verifyNoInteractions(moderationService);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /uploads — resolveSubdir branches (captured subdir asserted)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /uploads — subdir resolution")
    class SubdirResolution {

        private String uploadAndCaptureSubdir(String context, String contextId) throws Exception {
            when(storageService.storeFile(any(), any(), any())).thenReturn("stored/rand.png");
            var req = multipart(BASE).file(validPng()).param("type", "image");
            if (context != null) req = req.param("context", context);
            if (contextId != null) req = req.param("contextId", contextId);
            mockMvc.perform(req).andExpect(status().isOk());
            ArgumentCaptor<String> subdirCap = ArgumentCaptor.forClass(String.class);
            verify(storageService).storeFile(any(), any(), subdirCap.capture());
            return subdirCap.getValue();
        }

        @Test
        void strangerMapsToFlatAnonymousFolder() throws Exception {
            authenticate();
            assertThat(uploadAndCaptureSubdir("stranger", null)).isEqualTo("strangers");
        }

        @Test
        void postMapsToPostsUserFolder() throws Exception {
            authenticate();
            when(moderationService.moderateUpload(any())).thenReturn(ModerationResult.clean()); // moderated context
            assertThat(uploadAndCaptureSubdir("post", null)).isEqualTo("posts/" + USER_UUID);
        }

        @Test
        void storyMapsToStoriesUserFolder() throws Exception {
            authenticate();
            when(moderationService.moderateUpload(any())).thenReturn(ModerationResult.clean()); // moderated context
            assertThat(uploadAndCaptureSubdir("story", null)).isEqualTo("stories/" + USER_UUID);
        }

        @Test
        void lobbyMapsToLobbyUserFolder() throws Exception {
            authenticate();
            assertThat(uploadAndCaptureSubdir("lobby", null)).isEqualTo("lobby/" + USER_UUID);
        }

        @Test
        void nullContextFallsBackToOthers() throws Exception {
            authenticate();
            assertThat(uploadAndCaptureSubdir(null, null)).isEqualTo("others");
        }

        @Test
        void unknownContextFallsBackToOthers() throws Exception {
            authenticate();
            assertThat(uploadAndCaptureSubdir("banana", null)).isEqualTo("others");
        }

        @Test
        void conversationWithMemberMapsToConversationsIdFolder() throws Exception {
            authenticate();
            String cid = "22222222-2222-2222-2222-222222222222";
            Chat chat = mock(Chat.class);
            ChatMember member = mock(ChatMember.class);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(eq(chat), eq(testUser))).thenReturn(Optional.of(member));

            assertThat(uploadAndCaptureSubdir("conversation", cid)).isEqualTo("conversations/" + cid);
        }

        @Test
        void conversationWithNonMemberFallsBackToOthers() throws Exception {
            authenticate();
            String cid = "22222222-2222-2222-2222-222222222222";
            Chat chat = mock(Chat.class);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any())).thenReturn(Optional.empty());

            assertThat(uploadAndCaptureSubdir("conversation", cid)).isEqualTo("others");
        }

        @Test
        void conversationWithUnknownChatFallsBackToOthers() throws Exception {
            authenticate();
            String cid = "22222222-2222-2222-2222-222222222222";
            when(chatRepository.findByUuid(any())).thenReturn(Optional.empty());

            assertThat(uploadAndCaptureSubdir("conversation", cid)).isEqualTo("others");
        }

        @Test
        void conversationWithInvalidContextIdFallsBackToOthersAndNeverHitsRepo() throws Exception {
            authenticate();
            // safeUuid() rejects a non-UUID contextId before the repository is ever queried.
            assertThat(uploadAndCaptureSubdir("conversation", "not-a-uuid")).isEqualTo("others");
            verifyNoInteractions(chatRepository, chatMemberRepository);
        }

        @Test
        void conversationWithNullContextIdFallsBackToOthers() throws Exception {
            authenticate();
            // No contextId → safeUuid(null) hits the (value == null) branch and returns null, so the
            // conversation guard's (cid != null) sub-condition is false and the repo is never queried.
            assertThat(uploadAndCaptureSubdir("conversation", null)).isEqualTo("others");
            verifyNoInteractions(chatRepository, chatMemberRepository);
        }

        @Test
        void conversationWithBlankContextIdFallsBackToOthers() throws Exception {
            authenticate();
            // A whitespace-only contextId → safeUuid hits the (value.isBlank()) branch and returns null.
            assertThat(uploadAndCaptureSubdir("conversation", "   ")).isEqualTo("others");
            verifyNoInteractions(chatRepository, chatMemberRepository);
        }

        // ── Unauthenticated principal: userDetails == null → uid == null everywhere ────────────
        // (No authenticate() call → @AuthenticationPrincipal resolves to null. Each context's
        //  (uid != null) guard is false, so all fall back to "others".)

        @Test
        void profileWithoutAuthenticatedUserFallsBackToOthers() throws Exception {
            when(moderationService.moderateUpload(any())).thenReturn(ModerationResult.clean()); // profile is moderated
            assertThat(uploadAndCaptureSubdir("profile", null)).isEqualTo("others");
        }

        @Test
        void postWithoutAuthenticatedUserFallsBackToOthers() throws Exception {
            when(moderationService.moderateUpload(any())).thenReturn(ModerationResult.clean()); // post is moderated
            assertThat(uploadAndCaptureSubdir("post", null)).isEqualTo("others");
        }

        @Test
        void storyWithoutAuthenticatedUserFallsBackToOthers() throws Exception {
            when(moderationService.moderateUpload(any())).thenReturn(ModerationResult.clean()); // story is moderated
            assertThat(uploadAndCaptureSubdir("story", null)).isEqualTo("others");
        }

        @Test
        void lobbyWithoutAuthenticatedUserFallsBackToOthers() throws Exception {
            // Not moderated; userDetails == null makes uid null → the lobby (uid != null) guard is false.
            assertThat(uploadAndCaptureSubdir("lobby", null)).isEqualTo("others");
        }

        @Test
        void conversationWithoutAuthenticatedUserFallsBackToOthersAndNeverHitsRepo() throws Exception {
            // cid resolves to a valid UUID, but the conversation guard's (userDetails != null)
            // sub-condition short-circuits to false, so the repositories are never consulted.
            String cid = "22222222-2222-2222-2222-222222222222";
            assertThat(uploadAndCaptureSubdir("conversation", cid)).isEqualTo("others");
            verifyNoInteractions(chatRepository, chatMemberRepository);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /uploads — validation / magic-byte negatives
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /uploads — validation negatives")
    class ValidationNegatives {

        @Test
        void shouldReturn415WhenBytesDoNotMatchDeclaredType() throws Exception {
            authenticate();
            // Declares image but carries a PDF signature (%PDF) → UploadValidator rejects it.
            byte[] pdf = {0x25, 0x50, 0x44, 0x46, '-', '1', '.', '4'};
            MockMultipartFile file = new MockMultipartFile("file", "fake.png", "image/png", pdf);

            mockMvc.perform(multipart(BASE).file(file).param("type", "image"))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.messageCode").value("TM_495"));

            verifyNoInteractions(storageService, moderationService);
        }

        @Test
        void shouldReturn415WhenFileIsEmpty() throws Exception {
            authenticate();
            MockMultipartFile file = new MockMultipartFile("file", "empty.png", "image/png", new byte[0]);

            mockMvc.perform(multipart(BASE).file(file).param("type", "image"))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.messageCode").value("TM_493"));

            verifyNoInteractions(storageService);
        }

        @Test
        void shouldReturn415WhenSvgContainsScript() throws Exception {
            authenticate();
            byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                    .getBytes(StandardCharsets.UTF_8);
            MockMultipartFile file = new MockMultipartFile("file", "x.svg", "image/svg+xml", svg);

            mockMvc.perform(multipart(BASE).file(file).param("type", "image"))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.messageCode").value("TM_494"));

            verifyNoInteractions(storageService);
        }

        @Test
        void shouldAllowCleanSvgForImage() throws Exception {
            authenticate();
            when(storageService.storeFile(any(), any(), any())).thenReturn("others/x.svg");
            byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect width=\"1\" height=\"1\"/></svg>"
                    .getBytes(StandardCharsets.UTF_8);
            MockMultipartFile file = new MockMultipartFile("file", "clean.svg", "image/svg+xml", svg);

            mockMvc.perform(multipart(BASE).file(file).param("type", "image"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE));
        }

        @Test
        void shouldReturn500WhenFilePartMissing() throws Exception {
            authenticate();
            // No .file(...) part. @RequestParam("file") MultipartFile → MissingServletRequestPartException.
            // GlobalExceptionHandler has NO dedicated handler for it (it is a plain @ControllerAdvice, not a
            // ResponseEntityExceptionHandler), so it falls through to the catch-all Exception handler → 500 / TM_002.
            mockMvc.perform(multipart(BASE).param("type", "image"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(storageService);
        }

        @Test
        void shouldReturn500WhenTypeParamMissing() throws Exception {
            authenticate();
            // Missing required @RequestParam("type") → MissingServletRequestParameterException → catch-all 500.
            mockMvc.perform(multipart(BASE).file(validPng()))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(storageService);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /uploads/media
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /uploads/media")
    class GetMedia {

        private static final String CHAT_UUID = "22222222-2222-2222-2222-222222222222";

        /** Authenticate the MockMvc request as {@code testUser} via the SecurityContext (Bearer path). */
        private void asTestUser() {
            CustomUserDetails cud = new CustomUserDetails(testUser);
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                    new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                            cud, null, cud.getAuthorities()));
        }

        @org.junit.jupiter.api.AfterEach
        void clearAuth() {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }

        private com.neo.chat.domain.Chat chatWith(User... members) {
            java.util.List<com.neo.chat.domain.ChatMember> ms = new java.util.ArrayList<>();
            for (User u : members) ms.add(com.neo.chat.domain.ChatMember.builder().user(u).build());
            return com.neo.chat.domain.Chat.builder().members(ms).build();
        }

        @Test
        void anonymousRequestForProtectedMediaIs401AndNeverOpensStorage() throws Exception {
            mockMvc.perform(get(MEDIA).param("path", "/media/others/rand.png"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get(MEDIA).param("path", "/media/conversations/" + CHAT_UUID + "/rand.png"))
                    .andExpect(status().isUnauthorized());
            verifyNoInteractions(mediaStorage);
        }

        @Test
        void profilePhotosArePublic() throws Exception {
            byte[] body = pngBytes();
            when(mediaStorage.open(any())).thenReturn(Optional.of(
                    new MediaStorage.MediaContent(new ByteArrayResource(body), "image/png", body.length)));

            mockMvc.perform(get(MEDIA).param("path", "/media/profiles/" + USER_UUID + "/a.png"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "private, max-age=3600"));
        }

        @Test
        void conversationMediaRequiresMembership() throws Exception {
            asTestUser();
            User other = User.builder().username("bob").build();
            when(chatRepository.findByUuidWithMembers(UUID.fromString(CHAT_UUID)))
                    .thenReturn(Optional.of(chatWith(other)));

            mockMvc.perform(get(MEDIA).param("path", "/media/conversations/" + CHAT_UUID + "/rand.png"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(mediaStorage);
        }

        @Test
        void conversationMediaServedToMember() throws Exception {
            asTestUser();
            when(chatRepository.findByUuidWithMembers(UUID.fromString(CHAT_UUID)))
                    .thenReturn(Optional.of(chatWith(testUser)));
            byte[] body = pngBytes();
            when(mediaStorage.open(any())).thenReturn(Optional.of(
                    new MediaStorage.MediaContent(new ByteArrayResource(body), "image/png", body.length)));

            mockMvc.perform(get(MEDIA).param("path", "/media/conversations/" + CHAT_UUID + "/rand.png"))
                    .andExpect(status().isOk());
        }

        @Test
        void conversationMediaWithMalformedChatIdIsForbidden() throws Exception {
            asTestUser();
            mockMvc.perform(get(MEDIA).param("path", "/media/conversations/not-a-uuid/rand.png"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(mediaStorage);
        }

        @Test
        void mediaCookieAuthenticatesTheViewer() throws Exception {
            when(tokenProvider.parseMediaToken("mt")).thenReturn("testuser");
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            byte[] body = pngBytes();
            when(mediaStorage.open(any())).thenReturn(Optional.of(
                    new MediaStorage.MediaContent(new ByteArrayResource(body), "image/png", body.length)));

            mockMvc.perform(get(MEDIA).param("path", "/media/others/rand.png")
                            .cookie(new jakarta.servlet.http.Cookie(AuthController.MEDIA_COOKIE, "mt")))
                    .andExpect(status().isOk());
        }

        @Test
        void mediaCookieOfBannedUserIsRejected() throws Exception {
            testUser.setBanned(true);
            when(tokenProvider.parseMediaToken("mt")).thenReturn("testuser");
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));

            mockMvc.perform(get(MEDIA).param("path", "/media/others/rand.png")
                            .cookie(new jakarta.servlet.http.Cookie(AuthController.MEDIA_COOKIE, "mt")))
                    .andExpect(status().isUnauthorized());
            verifyNoInteractions(mediaStorage);
        }

        @Test
        void invalidMediaCookieIsRejected() throws Exception {
            when(tokenProvider.parseMediaToken("bad")).thenReturn(null);
            mockMvc.perform(get(MEDIA).param("path", "/media/others/rand.png")
                            .cookie(new jakarta.servlet.http.Cookie(AuthController.MEDIA_COOKIE, "bad")))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void shouldReturn200WithBodyAndNosniffForNormalMedia() throws Exception {
            asTestUser();
            byte[] body = pngBytes();
            when(mediaStorage.open(eq("others/rand.png"))).thenReturn(Optional.of(
                    new MediaStorage.MediaContent(new ByteArrayResource(body), "image/png", body.length)));

            mockMvc.perform(get(MEDIA).param("path", "others/rand.png"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "image/png"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().doesNotExist("Content-Disposition"));
        }

        @Test
        void shouldSandboxScriptableSvg() throws Exception {
            asTestUser();
            byte[] body = "<svg/>".getBytes(StandardCharsets.UTF_8);
            when(mediaStorage.open(any())).thenReturn(Optional.of(
                    new MediaStorage.MediaContent(new ByteArrayResource(body), "image/svg+xml", body.length)));

            mockMvc.perform(get(MEDIA).param("path", "others/x.svg"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition", "attachment"))
                    .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        }

        @Test
        void shouldReturn404WhenReferenceNotResolvable() throws Exception {
            asTestUser();
            when(mediaStorage.open(any())).thenReturn(Optional.empty());

            mockMvc.perform(get(MEDIA).param("path", "missing/x.png"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void shouldReturn500WhenStorageOpenThrows() throws Exception {
            asTestUser();
            // The controller wraps open() in try/catch and maps any failure to a 500 with no body.
            when(mediaStorage.open(any())).thenThrow(new RuntimeException("bucket unreachable"));

            mockMvc.perform(get(MEDIA).param("path", "boom/x.png"))
                    .andExpect(status().isInternalServerError());
        }

        @Test
        void shouldReturn500WhenPathParamMissing() throws Exception {
            // Missing required @RequestParam("path"). The param is resolved BEFORE the method body, so the
            // controller's own try/catch does not apply → MissingServletRequestParameterException → catch-all 500.
            mockMvc.perform(get(MEDIA))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(mediaStorage);
        }

        @Test
        void shouldDefaultToOctetStreamWhenContentTypeNull() throws Exception {
            asTestUser();
            // mc.contentType() == null → the ternary falls back to "application/octet-stream"; the type
            // is not scriptable so no Content-Disposition/CSP is added.
            byte[] body = pngBytes();
            when(mediaStorage.open(any())).thenReturn(Optional.of(
                    new MediaStorage.MediaContent(new ByteArrayResource(body), null, body.length)));

            mockMvc.perform(get(MEDIA).param("path", "x/y.bin"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "application/octet-stream"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().doesNotExist("Content-Disposition"));
        }

        @Test
        void shouldSandboxScriptableHtml() throws Exception {
            asTestUser();
            // contentType contains "html" → the scriptable-types guard adds attachment + sandbox CSP.
            byte[] body = "<h1>x</h1>".getBytes(StandardCharsets.UTF_8);
            when(mediaStorage.open(any())).thenReturn(Optional.of(
                    new MediaStorage.MediaContent(new ByteArrayResource(body), "text/html", body.length)));

            mockMvc.perform(get(MEDIA).param("path", "x/y.html"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition", "attachment"))
                    .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"));
        }

        @Test
        void shouldSandboxScriptableXml() throws Exception {
            asTestUser();
            // contentType contains "xml" → scriptable-types guard fires (svg/html both false first).
            byte[] body = "<root/>".getBytes(StandardCharsets.UTF_8);
            when(mediaStorage.open(any())).thenReturn(Optional.of(
                    new MediaStorage.MediaContent(new ByteArrayResource(body), "application/xml", body.length)));

            mockMvc.perform(get(MEDIA).param("path", "x/y.xml"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition", "attachment"))
                    .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"));
        }

        @Test
        void shouldOmitContentLengthWhenNegative() throws Exception {
            // mc.contentLength() < 0 → the controller skips builder.contentLength(...); the body still streams.
            asTestUser();
            byte[] body = pngBytes();
            when(mediaStorage.open(any())).thenReturn(Optional.of(
                    new MediaStorage.MediaContent(new ByteArrayResource(body), "image/png", -1)));

            mockMvc.perform(get(MEDIA).param("path", "x/y.png"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "image/png"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        }
    }
}
