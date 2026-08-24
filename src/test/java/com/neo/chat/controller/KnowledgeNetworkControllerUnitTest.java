package com.neo.chat.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.chat.domain.Role;
import com.neo.chat.domain.User;
import com.neo.chat.dto.request.UpdateExperiencesRequest;
import com.neo.chat.dto.response.ExperienceResponse;
import com.neo.chat.dto.response.KnowledgePersonResponse;
import com.neo.chat.dto.response.KnowledgeSearchPageResponse;
import com.neo.chat.enums.ExperienceCategory;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.ForbiddenException;
import com.neo.chat.exception.GlobalExceptionHandler;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.KnowledgeNetworkService;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link KnowledgeNetworkController} (Human Knowledge Network).
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link KnowledgeNetworkService} and the real
 * {@link GlobalExceptionHandler}. The class- and method-level {@code @PreAuthorize}
 * (role + {@code @featureGuard.check('KNOWLEDGE_NETWORK')}) is enforced by Spring's
 * method-security interceptor, inactive under standalone MockMvc — covered by the integration
 * test. Success responses emit {@code TM_000} directly from the controller.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("KnowledgeNetworkController (unit)")
class KnowledgeNetworkControllerUnitTest {

    private static final String BASE = "/knowledge";
    private static final String SUCCESS_CODE = "TM_000";
    private static final String INTERNAL_ERROR_CODE = "TM_002";
    private static final String TARGET_ID = "target-uuid-1";

    @Mock
    private KnowledgeNetworkService knowledgeNetworkService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private User testUser;

    @BeforeEach
    void setUp() {
        KnowledgeNetworkController controller = new KnowledgeNetworkController(knowledgeNetworkService);

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

    private static ExperienceResponse experience(String tag, String category) {
        return ExperienceResponse.builder()
                .uuid("exp-1").tag(tag).category(category).note("n").openToQuestions(true).build();
    }

    private static KnowledgePersonResponse person(String username, String presence) {
        return KnowledgePersonResponse.builder()
                .userUuid(TARGET_ID).name("Expert").username(username)
                .avatar("https://cdn/a.png").country("CA").city("Toronto").mood("HELPFUL")
                .presence(presence)
                .matchedExperience(experience("Moved to Canada", "RELOCATION"))
                .build();
    }

    // ── GET /knowledge/mine ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /knowledge/mine")
    class GetMine {

        @Test
        void returns200WithExperiences() throws Exception {
            authenticate();
            when(knowledgeNetworkService.getMine(any()))
                    .thenReturn(List.of(experience("Java developer", "TECHNOLOGY")));

            mockMvc.perform(get(BASE + "/mine"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.data[0].tag").value("Java developer"))
                    .andExpect(jsonPath("$.data[0].category").value("TECHNOLOGY"));

            verify(knowledgeNetworkService).getMine(testUser);
        }

        @Test
        void returns500OnUnexpectedError() throws Exception {
            authenticate();
            when(knowledgeNetworkService.getMine(any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(BASE + "/mine"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ── PUT /knowledge/mine ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /knowledge/mine")
    class UpdateMine {

        private String body() throws Exception {
            UpdateExperiencesRequest req = UpdateExperiencesRequest.builder()
                    .experiences(List.of(UpdateExperiencesRequest.ExperienceItem.builder()
                            .tag("Moved to Canada").category(ExperienceCategory.RELOCATION)
                            .note("2021").openToQuestions(true).build()))
                    .build();
            return objectMapper.writeValueAsString(req);
        }

        @Test
        void returns200AndForwardsRequest() throws Exception {
            authenticate();
            when(knowledgeNetworkService.updateExperiences(any(), any()))
                    .thenReturn(List.of(experience("Moved to Canada", "RELOCATION")));

            mockMvc.perform(put(BASE + "/mine")
                            .contentType(MediaType.APPLICATION_JSON).content(body()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.message").value("Experiences updated"))
                    .andExpect(jsonPath("$.data[0].tag").value("Moved to Canada"));

            ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
            ArgumentCaptor<UpdateExperiencesRequest> reqCaptor =
                    ArgumentCaptor.forClass(UpdateExperiencesRequest.class);
            verify(knowledgeNetworkService).updateExperiences(user.capture(), reqCaptor.capture());
            assertThat(user.getValue()).isSameAs(testUser);
            assertThat(reqCaptor.getValue().getExperiences()).hasSize(1);
        }

        @Test
        void returns400WhenServiceRejectsTag() throws Exception {
            authenticate();
            when(knowledgeNetworkService.updateExperiences(any(), any()))
                    .thenThrow(new BadRequestException("Experience tag must not be blank", "TM_942"));

            mockMvc.perform(put(BASE + "/mine")
                            .contentType(MediaType.APPLICATION_JSON).content(body()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_942"));
        }

        @Test
        void returns400WhenServiceRejectsOverCap() throws Exception {
            authenticate();
            when(knowledgeNetworkService.updateExperiences(any(), any()))
                    .thenThrow(new BadRequestException("Too many experiences", "TM_943"));

            mockMvc.perform(put(BASE + "/mine")
                            .contentType(MediaType.APPLICATION_JSON).content(body()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_943"));
        }
    }

    // ── GET /knowledge/search ────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /knowledge/search")
    class Search {

        @Test
        void returns200AndForwardsAllParams() throws Exception {
            authenticate();
            KnowledgeSearchPageResponse page = KnowledgeSearchPageResponse.builder()
                    .items(List.of(person("expert", "ONLINE")))
                    .nextCursor("1").hasMore(true).build();
            when(knowledgeNetworkService.search(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(page);

            mockMvc.perform(get(BASE + "/search")
                            .param("query", "canada")
                            .param("category", "RELOCATION")
                            .param("cursor", "0")
                            .param("limit", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.items[0].username").value("expert"))
                    .andExpect(jsonPath("$.data.items[0].presence").value("ONLINE"))
                    .andExpect(jsonPath("$.data.items[0].matchedExperience.tag").value("Moved to Canada"))
                    .andExpect(jsonPath("$.data.nextCursor").value("1"))
                    .andExpect(jsonPath("$.data.hasMore").value(true));

            ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<ExperienceCategory> cat = ArgumentCaptor.forClass(ExperienceCategory.class);
            ArgumentCaptor<String> cursor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
            verify(knowledgeNetworkService)
                    .search(eq(testUser), query.capture(), cat.capture(), cursor.capture(), limit.capture());
            assertThat(query.getValue()).isEqualTo("canada");
            assertThat(cat.getValue()).isEqualTo(ExperienceCategory.RELOCATION);
            assertThat(cursor.getValue()).isEqualTo("0");
            assertThat(limit.getValue()).isEqualTo(10);
        }

        @Test
        void usesDefaultLimitAndNullsWhenParamsOmitted() throws Exception {
            authenticate();
            when(knowledgeNetworkService.search(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(KnowledgeSearchPageResponse.builder()
                            .items(List.of()).nextCursor(null).hasMore(false).build());

            mockMvc.perform(get(BASE + "/search"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items").isArray())
                    .andExpect(jsonPath("$.data.hasMore").value(false));

            ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
            verify(knowledgeNetworkService)
                    .search(eq(testUser), isNull(), isNull(), isNull(), limit.capture());
            assertThat(limit.getValue()).isEqualTo(20); // controller default
        }
    }

    // ── POST /knowledge/{userUuid}/ask ──────────────────────────────────────────

    @Nested
    @DisplayName("POST /knowledge/{userUuid}/ask")
    class Ask {

        @Test
        void returns200WithPersonCardAndForwardsArgs() throws Exception {
            authenticate();
            when(knowledgeNetworkService.askPerson(any(), any())).thenReturn(person("expert", "AWAY"));

            mockMvc.perform(post(BASE + "/" + TARGET_ID + "/ask"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.data.userUuid").value(TARGET_ID))
                    .andExpect(jsonPath("$.data.username").value("expert"))
                    .andExpect(jsonPath("$.data.presence").value("AWAY"));

            ArgumentCaptor<String> uuid = ArgumentCaptor.forClass(String.class);
            verify(knowledgeNetworkService).askPerson(eq(testUser), uuid.capture());
            assertThat(uuid.getValue()).isEqualTo(TARGET_ID);
        }

        @Test
        void returns400WhenInvalidUuid() throws Exception {
            authenticate();
            when(knowledgeNetworkService.askPerson(any(), any()))
                    .thenThrow(new BadRequestException("Invalid user id", "TM_944"));

            mockMvc.perform(post(BASE + "/not-a-uuid/ask"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_944"));
        }

        @Test
        void returns404WhenTargetNotFound() throws Exception {
            authenticate();
            when(knowledgeNetworkService.askPerson(any(), any()))
                    .thenThrow(new NotFoundException("User not found", "TM_946"));

            mockMvc.perform(post(BASE + "/ghost/ask"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_946"));
        }

        @Test
        void returns403WhenBlocked() throws Exception {
            authenticate();
            when(knowledgeNetworkService.askPerson(any(), any()))
                    .thenThrow(new ForbiddenException("You cannot message this user", "TM_945"));

            mockMvc.perform(post(BASE + "/" + TARGET_ID + "/ask"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_945"));
        }

        @Test
        void returns500OnUnexpectedError() throws Exception {
            authenticate();
            when(knowledgeNetworkService.askPerson(any(), any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(BASE + "/" + TARGET_ID + "/ask"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }
}
