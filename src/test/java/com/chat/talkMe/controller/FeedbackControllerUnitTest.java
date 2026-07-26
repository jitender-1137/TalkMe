package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.FeedbackRequest;
import com.chat.talkMe.dto.response.FeedbackResponse;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.FeedbackService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link FeedbackController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link FeedbackService} and the real
 * {@link GlobalExceptionHandler}. Verifies request/response wiring, bean validation on the
 * {@link FeedbackRequest}, and delegation. The {@code @PreAuthorize("hasRole('USER')")} gate is
 * enforced by Spring method security (not active here) and is out of scope, per the repo's
 * controller-unit-test convention.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FeedbackController (unit)")
class FeedbackControllerUnitTest {

    private static final String BASE = "/feedback";
    private static final String VALIDATION_CODE = "VE_101";
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private FeedbackService feedbackService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        FeedbackController controller = new FeedbackController(feedbackService);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        // FeedbackRequest has a primitive `int rating`; a body omitting it would make the
        // default converter fail. Production's Jackson config tolerates this, so mirror it.
        JsonMapper jsonMapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .setValidator(validator)
                .setMessageConverters(new JacksonJsonHttpMessageConverter(jsonMapper))
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

    private static FeedbackResponse feedback() {
        return FeedbackResponse.builder()
                .id("fb-1").rating(5).reason("Compliment").comment("Love it")
                .type("MANUAL").status("NEW").build();
    }

    @Test
    void shouldReturn200AndForwardRequestWhenValid() throws Exception {
        authenticate();
        when(feedbackService.submit(any(), any())).thenReturn(feedback());

        String body = """
                {"rating":5,"reason":"Compliment","comment":"Love it","type":"MANUAL"}""";
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Thanks for your feedback!"))
                .andExpect(jsonPath("$.messageCode").value("TM_310"))
                .andExpect(jsonPath("$.data.id").value("fb-1"))
                .andExpect(jsonPath("$.data.rating").value(5))
                .andExpect(jsonPath("$.data.type").value("MANUAL"));

        ArgumentCaptor<FeedbackRequest> req = ArgumentCaptor.forClass(FeedbackRequest.class);
        verify(feedbackService).submit(req.capture(), eq(testUser));
        assertThat(req.getValue().getRating()).isEqualTo(5);
        assertThat(req.getValue().getReason()).isEqualTo("Compliment");
        assertThat(req.getValue().getComment()).isEqualTo("Love it");
        assertThat(req.getValue().getType()).isEqualTo("MANUAL");
    }

    @Test
    void shouldAcceptCommentOnlyFeedbackWithoutRating() throws Exception {
        authenticate();
        when(feedbackService.submit(any(), any())).thenReturn(feedback());

        String body = """
                {"comment":"Please add dark mode","type":"LEAVE_GROUP","contextRef":"Hikers"}""";
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        ArgumentCaptor<FeedbackRequest> req = ArgumentCaptor.forClass(FeedbackRequest.class);
        verify(feedbackService).submit(req.capture(), any());
        assertThat(req.getValue().getRating()).isZero();
        assertThat(req.getValue().getContextRef()).isEqualTo("Hikers");
    }

    @Test
    void shouldReturn400AndSkipServiceWhenRatingOutOfRange() throws Exception {
        authenticate();
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":9,\"comment\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
        verifyNoInteractions(feedbackService);
    }

    @Test
    void shouldReturn400WhenServiceRejectsEmptySubmission() throws Exception {
        authenticate();
        when(feedbackService.submit(any(), any()))
                .thenThrow(new BadRequestException("Please share a rating or a comment.", "TM_312"));
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("TM_312"));
    }

    @Test
    void shouldReturn500WhenBodyMalformed() throws Exception {
        authenticate();
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{bad"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        verifyNoInteractions(feedbackService);
    }

    @Test
    void shouldReturn500OnUnexpectedServiceError() throws Exception {
        authenticate();
        when(feedbackService.submit(any(), any())).thenThrow(new RuntimeException("boom"));
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":4,\"comment\":\"ok\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
    }
}
