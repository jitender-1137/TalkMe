package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Friend;
import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.repository.BlockUserRepository;
import com.chat.talkMe.repository.FriendRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.WingmanService;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link WingmanController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link WingmanService} + the three repositories the
 * controller consults directly ({@link UserRepository}, {@link FriendRepository},
 * {@link BlockUserRepository}) and the real {@link GlobalExceptionHandler}.
 *
 * <p><b>Scope boundary:</b> {@code @PreAuthorize("hasRole('USER')")} on the class and
 * {@code @PreAuthorize("@featureGuard.check('AI_WINGMAN')")} on each method are method-security
 * concerns (inactive in standalone MockMvc) — the AI_WINGMAN entitlement gate is exercised by the
 * integration test, not here. This test drives the controller's own logic: IDOR guards
 * (self / block / friendship), the {@code clamp()} bounds, request-body defaulting and the
 * exception-to-status mapping.
 *
 * <p>No tolerant Jackson converter is registered: neither {@code @RequestBody} field is an unboxed
 * primitive ({@code SuggestRequest.max} is a boxed {@link Integer}), and {@code icebreakers}' only
 * numeric input is a {@code @RequestParam int} bound outside JSON. No {@link org.springframework.data.domain.Pageable}
 * endpoints, so no PageableHandlerMethodArgumentResolver. {@code SuggestRequest} carries no bean-validation
 * annotations and the controller does not {@code @Valid} it, so there is no VE_101 path to assert.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WingmanController (unit)")
class WingmanControllerUnitTest {

    private static final String BASE = "/match/wingman";
    private static final String OTHER_UUID = "11111111-1111-1111-1111-111111111111";
    private static final long ME_ID = 1L;
    private static final long OTHER_ID = 2L;
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private WingmanService wingmanService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private FriendRepository friendRepository;
    @Mock
    private BlockUserRepository blockUserRepository;

    private MockMvc mockMvc;
    private User testUser;
    private User other;

    @BeforeEach
    void setUp() {
        WingmanController controller =
                new WingmanController(wingmanService, userRepository, friendRepository, blockUserRepository);

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
        testUser.setId(ME_ID);
        testUser.setUuid(UUID.randomUUID());

        other = User.builder()
                .username("other").email("o@e.com").name("Other User")
                .isGuest(false).roles(Set.of(role))
                .build();
        other.setId(OTHER_ID);
        other.setUuid(UUID.fromString(OTHER_UUID));
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

    /** Wire the happy-path relationship gates: other exists, no blocks either way, and friends. */
    private void wireFriendship() {
        when(userRepository.findByUuid(UUID.fromString(OTHER_UUID))).thenReturn(Optional.of(other));
        when(blockUserRepository.existsByUserAndBlocked(eq(testUser), eq(other))).thenReturn(false);
        when(blockUserRepository.existsByUserAndBlocked(eq(other), eq(testUser))).thenReturn(false);
        Friend friend = Friend.builder().user(testUser).friend(other).build();
        friend.setDeleted(false);
        when(friendRepository.findByUserAndFriend(eq(testUser), eq(other))).thenReturn(Optional.of(friend));
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /match/wingman/icebreakers/{userUuid}
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /icebreakers/{userUuid}")
    class Icebreakers {

        @Test
        void shouldReturn200AndForwardMeOtherAndClampedMaxWhenFriends() throws Exception {
            authenticate();
            wireFriendship();
            when(wingmanService.icebreakers(eq(testUser), eq(other), anyInt()))
                    .thenReturn(List.of("Hi there!", "Love your taste in music"));

            mockMvc.perform(get(BASE + "/icebreakers/" + OTHER_UUID).param("max", "5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data[0]").value("Hi there!"))
                    .andExpect(jsonPath("$.data[1]").value("Love your taste in music"));

            ArgumentCaptor<Integer> maxCap = ArgumentCaptor.forClass(Integer.class);
            verify(wingmanService).icebreakers(eq(testUser), eq(other), maxCap.capture());
            assertThat(maxCap.getValue()).isEqualTo(5);
        }

        @Test
        void shouldClampMaxDownToHardCapTen() throws Exception {
            authenticate();
            wireFriendship();
            when(wingmanService.icebreakers(any(), any(), anyInt())).thenReturn(List.of());

            mockMvc.perform(get(BASE + "/icebreakers/" + OTHER_UUID).param("max", "100"))
                    .andExpect(status().isOk());

            ArgumentCaptor<Integer> maxCap = ArgumentCaptor.forClass(Integer.class);
            verify(wingmanService).icebreakers(eq(testUser), eq(other), maxCap.capture());
            assertThat(maxCap.getValue()).isEqualTo(10); // HARD_CAP
        }

        @Test
        void shouldClampNonPositiveMaxToDefaultFive() throws Exception {
            authenticate();
            wireFriendship();
            when(wingmanService.icebreakers(any(), any(), anyInt())).thenReturn(List.of());

            mockMvc.perform(get(BASE + "/icebreakers/" + OTHER_UUID).param("max", "0"))
                    .andExpect(status().isOk());

            ArgumentCaptor<Integer> maxCap = ArgumentCaptor.forClass(Integer.class);
            verify(wingmanService).icebreakers(eq(testUser), eq(other), maxCap.capture());
            assertThat(maxCap.getValue()).isEqualTo(5); // DEFAULT_MAX
        }

        @Test
        void shouldClampNegativeMaxToDefaultFive() throws Exception {
            authenticate();
            wireFriendship();
            when(wingmanService.icebreakers(any(), any(), anyInt())).thenReturn(List.of());

            mockMvc.perform(get(BASE + "/icebreakers/" + OTHER_UUID).param("max", "-3"))
                    .andExpect(status().isOk());

            ArgumentCaptor<Integer> maxCap = ArgumentCaptor.forClass(Integer.class);
            verify(wingmanService).icebreakers(eq(testUser), eq(other), maxCap.capture());
            assertThat(maxCap.getValue()).isEqualTo(5);
        }

        @Test
        void shouldUseDefaultMaxWhenParamAbsent() throws Exception {
            authenticate();
            wireFriendship();
            when(wingmanService.icebreakers(any(), any(), anyInt())).thenReturn(List.of());

            // @RequestParam(defaultValue = "5") → absent param resolves to 5.
            mockMvc.perform(get(BASE + "/icebreakers/" + OTHER_UUID))
                    .andExpect(status().isOk());

            ArgumentCaptor<Integer> maxCap = ArgumentCaptor.forClass(Integer.class);
            verify(wingmanService).icebreakers(eq(testUser), eq(other), maxCap.capture());
            assertThat(maxCap.getValue()).isEqualTo(5);
        }

        @Test
        void shouldReturn200WithEmptyListWhenServiceProducesNothing() throws Exception {
            authenticate();
            wireFriendship();
            when(wingmanService.icebreakers(any(), any(), anyInt())).thenReturn(List.of());

            mockMvc.perform(get(BASE + "/icebreakers/" + OTHER_UUID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @Test
        void shouldReturn400AndSkipServiceWhenTargetIsSelf() throws Exception {
            authenticate();
            // findByUuid returns a user whose id equals mine → self-icebreaker guard trips.
            when(userRepository.findByUuid(UUID.fromString(OTHER_UUID))).thenReturn(Optional.of(testUser));

            mockMvc.perform(get(BASE + "/icebreakers/" + OTHER_UUID))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_025"));

            verifyNoInteractions(wingmanService);
            verify(blockUserRepository, never()).existsByUserAndBlocked(any(), any());
        }

        @Test
        void shouldReturn404WhenIBlockedThem() throws Exception {
            authenticate();
            when(userRepository.findByUuid(UUID.fromString(OTHER_UUID))).thenReturn(Optional.of(other));
            when(blockUserRepository.existsByUserAndBlocked(eq(testUser), eq(other))).thenReturn(true);

            // Block state is masked as "User not found" (TM_024) to avoid leaking it.
            mockMvc.perform(get(BASE + "/icebreakers/" + OTHER_UUID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_024"));

            verifyNoInteractions(wingmanService);
            verify(friendRepository, never()).findByUserAndFriend(any(), any());
        }

        @Test
        void shouldReturn404WhenTheyBlockedMe() throws Exception {
            authenticate();
            when(userRepository.findByUuid(UUID.fromString(OTHER_UUID))).thenReturn(Optional.of(other));
            when(blockUserRepository.existsByUserAndBlocked(eq(testUser), eq(other))).thenReturn(false);
            when(blockUserRepository.existsByUserAndBlocked(eq(other), eq(testUser))).thenReturn(true);

            mockMvc.perform(get(BASE + "/icebreakers/" + OTHER_UUID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_024"));

            verifyNoInteractions(wingmanService);
            verify(friendRepository, never()).findByUserAndFriend(any(), any());
        }

        @Test
        void shouldReturn403WhenNotFriends() throws Exception {
            authenticate();
            when(userRepository.findByUuid(UUID.fromString(OTHER_UUID))).thenReturn(Optional.of(other));
            when(blockUserRepository.existsByUserAndBlocked(any(), any())).thenReturn(false);
            when(friendRepository.findByUserAndFriend(eq(testUser), eq(other))).thenReturn(Optional.empty());

            mockMvc.perform(get(BASE + "/icebreakers/" + OTHER_UUID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_026"));

            verifyNoInteractions(wingmanService);
        }

        @Test
        void shouldReturn403WhenFriendRecordIsSoftDeleted() throws Exception {
            authenticate();
            when(userRepository.findByUuid(UUID.fromString(OTHER_UUID))).thenReturn(Optional.of(other));
            when(blockUserRepository.existsByUserAndBlocked(any(), any())).thenReturn(false);
            Friend deleted = Friend.builder().user(testUser).friend(other).build();
            deleted.setDeleted(true);
            when(friendRepository.findByUserAndFriend(eq(testUser), eq(other))).thenReturn(Optional.of(deleted));

            mockMvc.perform(get(BASE + "/icebreakers/" + OTHER_UUID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_026"));

            verifyNoInteractions(wingmanService);
        }

        @Test
        void shouldReturn404WhenTargetUserMissing() throws Exception {
            authenticate();
            when(userRepository.findByUuid(UUID.fromString(OTHER_UUID))).thenReturn(Optional.empty());

            mockMvc.perform(get(BASE + "/icebreakers/" + OTHER_UUID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_024"));

            verifyNoInteractions(wingmanService);
        }

        @Test
        void shouldReturn400WhenPathUuidMalformed() throws Exception {
            authenticate();
            // UUID.fromString("not-a-uuid") throws IllegalArgumentException("Invalid UUID string: …")
            // → GlobalExceptionHandler maps to TM_INVALID_UUID / 400 before any repo lookup runs.
            mockMvc.perform(get(BASE + "/icebreakers/not-a-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_INVALID_UUID"));

            verifyNoInteractions(userRepository);
            verifyNoInteractions(wingmanService);
        }

        @Test
        void shouldReturn500WhenMaxParamNotNumeric() throws Exception {
            authenticate();
            // A non-numeric `max` fails @RequestParam int binding → MethodArgumentTypeMismatchException,
            // which is not explicitly handled → catch-all 500 / TM_002. (Pinning observed behaviour.)
            mockMvc.perform(get(BASE + "/icebreakers/" + OTHER_UUID).param("max", "abc"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));

            verifyNoInteractions(wingmanService);
        }

        @Test
        void shouldReturn500WhenServiceThrowsRuntimeException() throws Exception {
            authenticate();
            wireFriendship();
            when(wingmanService.icebreakers(any(), any(), anyInt()))
                    .thenThrow(new RuntimeException("heuristic blew up"));

            mockMvc.perform(get(BASE + "/icebreakers/" + OTHER_UUID))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /match/wingman/suggest
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /suggest")
    class Suggest {

        @Test
        void shouldReturn200AndForwardLastMessageAndMaxToService() throws Exception {
            authenticate();
            when(wingmanService.replySuggestions(eq("How was your day?"), anyInt()))
                    .thenReturn(List.of("It was great!", "Busy but good"));

            mockMvc.perform(post(BASE + "/suggest").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"lastMessage\":\"How was your day?\",\"max\":3}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data[0]").value("It was great!"))
                    .andExpect(jsonPath("$.data[1]").value("Busy but good"));

            ArgumentCaptor<String> msgCap = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Integer> maxCap = ArgumentCaptor.forClass(Integer.class);
            verify(wingmanService).replySuggestions(msgCap.capture(), maxCap.capture());
            assertThat(msgCap.getValue()).isEqualTo("How was your day?");
            assertThat(maxCap.getValue()).isEqualTo(3);
        }

        @Test
        void shouldDefaultMaxToFiveWhenMaxAbsent() throws Exception {
            authenticate();
            when(wingmanService.replySuggestions(any(), anyInt())).thenReturn(List.of());

            // max omitted → SuggestRequest.max() == null → DEFAULT_MAX (5).
            mockMvc.perform(post(BASE + "/suggest").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"lastMessage\":\"hey\"}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<Integer> maxCap = ArgumentCaptor.forClass(Integer.class);
            verify(wingmanService).replySuggestions(eq("hey"), maxCap.capture());
            assertThat(maxCap.getValue()).isEqualTo(5);
        }

        @Test
        void shouldForwardNullMessageAndDefaultMaxWhenBodyEmptyObject() throws Exception {
            authenticate();
            when(wingmanService.replySuggestions(isNull(), anyInt())).thenReturn(List.of());

            // "{}" → SuggestRequest(null, null): null lastMessage, max defaults to 5.
            mockMvc.perform(post(BASE + "/suggest").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<Integer> maxCap = ArgumentCaptor.forClass(Integer.class);
            verify(wingmanService).replySuggestions(isNull(), maxCap.capture());
            assertThat(maxCap.getValue()).isEqualTo(5);
        }

        @Test
        void shouldClampMaxDownToHardCapTen() throws Exception {
            authenticate();
            when(wingmanService.replySuggestions(any(), anyInt())).thenReturn(List.of());

            mockMvc.perform(post(BASE + "/suggest").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"lastMessage\":\"hi\",\"max\":999}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<Integer> maxCap = ArgumentCaptor.forClass(Integer.class);
            verify(wingmanService).replySuggestions(eq("hi"), maxCap.capture());
            assertThat(maxCap.getValue()).isEqualTo(10);
        }

        @Test
        void shouldClampNonPositiveMaxToDefaultFive() throws Exception {
            authenticate();
            when(wingmanService.replySuggestions(any(), anyInt())).thenReturn(List.of());

            mockMvc.perform(post(BASE + "/suggest").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"lastMessage\":\"hi\",\"max\":0}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<Integer> maxCap = ArgumentCaptor.forClass(Integer.class);
            verify(wingmanService).replySuggestions(eq("hi"), maxCap.capture());
            assertThat(maxCap.getValue()).isEqualTo(5);
        }

        @Test
        void shouldReturn200WithEmptyListWhenServiceProducesNothing() throws Exception {
            authenticate();
            when(wingmanService.replySuggestions(any(), anyInt())).thenReturn(List.of());

            mockMvc.perform(post(BASE + "/suggest").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"lastMessage\":\"hi\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @Test
        void shouldReturn200WithNonEmptyList() throws Exception {
            authenticate();
            when(wingmanService.replySuggestions(any(), anyInt())).thenReturn(List.of("Sure!"));

            mockMvc.perform(post(BASE + "/suggest").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"lastMessage\":\"ok?\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0]").value("Sure!"));
        }

        @Test
        void shouldPassThroughUnicodeAndEmojiLastMessageVerbatim() throws Exception {
            authenticate();
            when(wingmanService.replySuggestions(any(), anyInt())).thenReturn(List.of());

            String unicode = "こんにちは 😀 café — naïve";
            mockMvc.perform(post(BASE + "/suggest").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"lastMessage\":\"" + unicode + "\"}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> msgCap = ArgumentCaptor.forClass(String.class);
            verify(wingmanService).replySuggestions(msgCap.capture(), anyInt());
            assertThat(msgCap.getValue()).isEqualTo(unicode);
        }

        @Test
        void shouldPassThroughXssAndSqliPayloadUnsanitised() throws Exception {
            authenticate();
            when(wingmanService.replySuggestions(any(), anyInt())).thenReturn(List.of());

            // Controller does not sanitise; escaping/parameterisation is the sink's job.
            String payload = "<script>alert(1)</script>'; DROP TABLE users;--";
            String json = "{\"lastMessage\":\"<script>alert(1)</script>'; DROP TABLE users;--\"}";
            mockMvc.perform(post(BASE + "/suggest").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> msgCap = ArgumentCaptor.forClass(String.class);
            verify(wingmanService).replySuggestions(msgCap.capture(), anyInt());
            assertThat(msgCap.getValue()).isEqualTo(payload);
        }

        @Test
        void shouldReturn500WhenBodyMalformed() throws Exception {
            authenticate();
            // Unreadable JSON → HttpMessageNotReadableException (not explicitly handled) → catch-all 500.
            mockMvc.perform(post(BASE + "/suggest").contentType(MediaType.APPLICATION_JSON).content("{bad"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));

            verifyNoInteractions(wingmanService);
        }

        @Test
        void shouldReturn500WhenBodyEntirelyMissing() throws Exception {
            authenticate();
            // @RequestBody is required (no required=false) → a missing body fails before the
            // controller's null-guard runs → HttpMessageNotReadableException → catch-all 500.
            mockMvc.perform(post(BASE + "/suggest").contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));

            verifyNoInteractions(wingmanService);
        }

        @Test
        void shouldReturn500WhenServiceThrowsRuntimeException() throws Exception {
            authenticate();
            when(wingmanService.replySuggestions(any(), anyInt()))
                    .thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(BASE + "/suggest").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"lastMessage\":\"hi\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }
}
