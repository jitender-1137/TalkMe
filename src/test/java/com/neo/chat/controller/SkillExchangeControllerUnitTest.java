package com.neo.chat.controller;

import com.neo.chat.domain.Role;
import com.neo.chat.domain.User;
import com.neo.chat.dto.request.UpdateSkillsRequest;
import com.neo.chat.dto.response.CompatibilityScore;
import com.neo.chat.dto.response.SkillMatchResponse;
import com.neo.chat.dto.response.SkillProfileResponse;
import com.neo.chat.enums.SkillLevel;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.ForbiddenException;
import com.neo.chat.exception.GlobalExceptionHandler;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.exception.TooManyRequestsException;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.SkillExchangeService;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link SkillExchangeController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link SkillExchangeService} and the real
 * {@link GlobalExceptionHandler}. Method-security ({@code hasRole('USER')} and the
 * {@code @featureGuard.check('SKILL_EXCHANGE')} gate) is inactive in standalone MockMvc and is
 * covered by the integration test. All happy paths emit {@code TM_000} from the controller.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillExchangeController (unit)")
class SkillExchangeControllerUnitTest {

    private static final String BASE = "/skills";
    private static final String TARGET_ID = "target-uuid-1";
    private static final String SUCCESS_CODE = "TM_000";
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private SkillExchangeService skillExchangeService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        SkillExchangeController controller = new SkillExchangeController(skillExchangeService);

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
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate() {
        CustomUserDetails principal = new CustomUserDetails(testUser);
        Authentication auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static SkillProfileResponse profile() {
        return SkillProfileResponse.builder()
                .offers(List.of(SkillProfileResponse.SkillItem.builder()
                        .name("JavaScript").level("ADVANCED").build()))
                .wants(List.of(SkillProfileResponse.SkillItem.builder()
                        .name("Hindi").build()))
                .build();
    }

    private static SkillMatchResponse matchCard() {
        return SkillMatchResponse.builder()
                .userUuid(TARGET_ID)
                .name("Rahul")
                .username("rahul")
                .avatar("https://cdn/rahul.jpg")
                .country("IN")
                .theyCanTeachYou(List.of("JavaScript"))
                .youCanTeachThem(List.of("Hindi"))
                .reciprocal(true)
                .online(true)
                .compatibility(CompatibilityScore.builder().overall(82).bucket("HIGH").build())
                .build();
    }

    // ── GET /skills/mine ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /skills/mine")
    class GetMine {

        @Test
        void shouldReturn200WithProfile() throws Exception {
            authenticate();
            when(skillExchangeService.getMine(any())).thenReturn(profile());

            mockMvc.perform(get(BASE + "/mine"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.data.offers[0].name").value("JavaScript"))
                    .andExpect(jsonPath("$.data.offers[0].level").value("ADVANCED"))
                    .andExpect(jsonPath("$.data.wants[0].name").value("Hindi"));

            verify(skillExchangeService).getMine(testUser);
        }

        @Test
        void shouldReturn500OnUnexpectedRuntimeException() throws Exception {
            authenticate();
            when(skillExchangeService.getMine(any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(BASE + "/mine"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ── PUT /skills/mine ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /skills/mine")
    class UpdateMine {

        @Test
        void shouldReturn200AndForwardOffersAndWantsWithLevels() throws Exception {
            authenticate();
            when(skillExchangeService.updateSkills(any(), any(), any())).thenReturn(profile());

            // New request shape: each entry is { name, level? } with level optional/nullable.
            String body = "{\"offers\":[{\"name\":\"JavaScript\",\"level\":\"ADVANCED\"},"
                    + "{\"name\":\"Python\"}],\"wants\":[{\"name\":\"Hindi\",\"level\":\"BEGINNER\"}]}";
            mockMvc.perform(put(BASE + "/mine").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.message").value("Skills updated"))
                    .andExpect(jsonPath("$.data.offers[0].name").value("JavaScript"));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<UpdateSkillsRequest.SkillItem>> offers = ArgumentCaptor.forClass(List.class);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<UpdateSkillsRequest.SkillItem>> wants = ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
            verify(skillExchangeService).updateSkills(user.capture(), offers.capture(), wants.capture());
            assertThat(user.getValue()).isSameAs(testUser);
            assertThat(offers.getValue())
                    .extracting(UpdateSkillsRequest.SkillItem::getName,
                            UpdateSkillsRequest.SkillItem::getLevel)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("JavaScript", SkillLevel.ADVANCED),
                            org.assertj.core.groups.Tuple.tuple("Python", null));
            assertThat(wants.getValue())
                    .extracting(UpdateSkillsRequest.SkillItem::getName,
                            UpdateSkillsRequest.SkillItem::getLevel)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("Hindi", SkillLevel.BEGINNER));
        }

        @Test
        void shouldReturn400WhenSkillNameBlank() throws Exception {
            authenticate();
            when(skillExchangeService.updateSkills(any(), any(), any()))
                    .thenThrow(new BadRequestException("Skill name cannot be blank", "TM_916"));

            mockMvc.perform(put(BASE + "/mine")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"offers\":[{\"name\":\"\"}]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_916"));
        }

        @Test
        void shouldReturn429WhenTooManySkills() throws Exception {
            authenticate();
            when(skillExchangeService.updateSkills(any(), any(), any()))
                    .thenThrow(new TooManyRequestsException(
                            "You can list at most 20 skills per direction", "TM_915"));

            mockMvc.perform(put(BASE + "/mine")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"offers\":[]}"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.messageCode").value("TM_915"));
        }
    }

    // ── GET /skills/matches ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /skills/matches")
    class GetMatches {

        @Test
        void shouldReturn200WithEmptyList() throws Exception {
            authenticate();
            when(skillExchangeService.findMatches(any())).thenReturn(List.of());

            mockMvc.perform(get(BASE + "/matches"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());

            verify(skillExchangeService).findMatches(testUser);
        }

        @Test
        void shouldReturn200WithMatchCards() throws Exception {
            authenticate();
            when(skillExchangeService.findMatches(any())).thenReturn(List.of(matchCard()));

            mockMvc.perform(get(BASE + "/matches"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].username").value("rahul"))
                    .andExpect(jsonPath("$.data[0].reciprocal").value(true))
                    .andExpect(jsonPath("$.data[0].theyCanTeachYou[0]").value("JavaScript"))
                    .andExpect(jsonPath("$.data[0].youCanTeachThem[0]").value("Hindi"))
                    .andExpect(jsonPath("$.data[0].compatibility.bucket").value("HIGH"));
        }

        @Test
        void shouldReturn500OnUnexpectedRuntimeException() throws Exception {
            authenticate();
            when(skillExchangeService.findMatches(any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(BASE + "/matches"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ── POST /skills/{userUuid}/study ──────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /skills/{userUuid}/study")
    class StartStudy {

        @Test
        void shouldReturn200AndForwardArgs() throws Exception {
            authenticate();
            when(skillExchangeService.startStudySession(eq(testUser), eq(TARGET_ID))).thenReturn(matchCard());

            mockMvc.perform(post(BASE + "/" + TARGET_ID + "/study"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.message").value("Study session ready"))
                    .andExpect(jsonPath("$.data.username").value("rahul"))
                    .andExpect(jsonPath("$.data.userUuid").value(TARGET_ID));

            ArgumentCaptor<String> uuid = ArgumentCaptor.forClass(String.class);
            verify(skillExchangeService).startStudySession(eq(testUser), uuid.capture());
            assertThat(uuid.getValue()).isEqualTo(TARGET_ID);
            verify(skillExchangeService, never()).findMatches(any());
        }

        @Test
        void shouldReturn400WhenUuidInvalid() throws Exception {
            authenticate();
            when(skillExchangeService.startStudySession(any(), any()))
                    .thenThrow(new BadRequestException("Invalid user id", "TM_914"));

            mockMvc.perform(post(BASE + "/not-a-uuid/study"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_914"));
        }

        @Test
        void shouldReturn404WhenTargetNotFound() throws Exception {
            authenticate();
            when(skillExchangeService.startStudySession(any(), any()))
                    .thenThrow(new NotFoundException("User not found", "TM_917"));

            mockMvc.perform(post(BASE + "/ghost/study"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_917"));
        }

        @Test
        void shouldReturn400WhenStartingWithSelf() throws Exception {
            authenticate();
            when(skillExchangeService.startStudySession(any(), any()))
                    .thenThrow(new BadRequestException(
                            "You cannot start a study session with yourself", "TM_919"));

            mockMvc.perform(post(BASE + "/" + TARGET_ID + "/study"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_919"));
        }

        @Test
        void shouldReturn403WhenBlocked() throws Exception {
            authenticate();
            when(skillExchangeService.startStudySession(any(), any()))
                    .thenThrow(new ForbiddenException(
                            "You cannot start a study session with this user", "TM_918"));

            mockMvc.perform(post(BASE + "/" + TARGET_ID + "/study"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_918"));
        }

        @Test
        void shouldReturn500OnUnexpectedRuntimeException() throws Exception {
            authenticate();
            when(skillExchangeService.startStudySession(any(), any()))
                    .thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(BASE + "/" + TARGET_ID + "/study"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }
}
