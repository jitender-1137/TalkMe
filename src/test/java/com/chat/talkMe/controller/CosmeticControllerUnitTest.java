package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.CosmeticResponse;
import com.chat.talkMe.enums.CosmeticRarity;
import com.chat.talkMe.enums.CosmeticType;
import com.chat.talkMe.enums.CosmeticUnlockType;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ConflictException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.CosmeticService;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link CosmeticController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link CosmeticService} and the real
 * {@link GlobalExceptionHandler}. Registers only {@link AuthenticationPrincipalArgumentResolver}
 * for {@code @AuthenticationPrincipal} — there is no {@code Pageable} on any endpoint, and the
 * single request body is a {@code Map<String,String>} with no unboxed primitive field, so the
 * standalone default Jackson converter is used as-is (no tolerant mapper needed).
 *
 * <p><b>Scope boundary:</b> the {@code @PreAuthorize("hasRole('USER')")} class guard, the
 * per-endpoint {@code @featureGuard.check('COSMETICS')} feature gate, JWT/CSRF and the security
 * filter chain are enforced by Spring Security at runtime and are OUT OF SCOPE here — a standalone
 * MockMvc does not run method-security or the filter chain. These tests exercise only the
 * controller's own logic: argument binding, the manual slot-enum parse, delegation to the service,
 * and the success/error envelope mapping done by {@link GlobalExceptionHandler}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CosmeticController (unit)")
class CosmeticControllerUnitTest {

    private static final String CATALOG = "/cosmetics/catalog";
    private static final String MINE = "/cosmetics/me";
    private static final String EQUIP = "/cosmetics/equip";
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private CosmeticService cosmeticService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        CosmeticController controller = new CosmeticController(cosmeticService);

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

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void authenticate() {
        CustomUserDetails principal = new CustomUserDetails(testUser);
        Authentication auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static CosmeticResponse cosmetic(String code, CosmeticType type,
                                             boolean owned, boolean equipped, boolean locked) {
        return CosmeticResponse.builder()
                .code(code)
                .type(type)
                .name("Cosmetic " + code)
                .rarity(CosmeticRarity.COMMON)
                .unlockType(CosmeticUnlockType.LEVEL)
                .unlockThreshold(5)
                .assetRef("asset/" + code + ".png")
                .owned(owned)
                .equipped(equipped)
                .locked(locked)
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /cosmetics/catalog
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /catalog")
    class Catalog {

        @Test
        void shouldReturn200WithCatalogAndForwardUser() throws Exception {
            authenticate();
            when(cosmeticService.catalog(any())).thenReturn(List.of(
                    cosmetic("frame_gold", CosmeticType.FRAME, true, true, false),
                    cosmetic("badge_pro", CosmeticType.BADGE, false, false, true)));

            mockMvc.perform(get(CATALOG))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Success"))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data[0].code").value("frame_gold"))
                    .andExpect(jsonPath("$.data[0].type").value("FRAME"))
                    .andExpect(jsonPath("$.data[0].owned").value(true))
                    .andExpect(jsonPath("$.data[0].equipped").value(true))
                    .andExpect(jsonPath("$.data[0].locked").value(false))
                    .andExpect(jsonPath("$.data[1].code").value("badge_pro"))
                    .andExpect(jsonPath("$.data[1].locked").value(true));

            ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
            verify(cosmeticService).catalog(user.capture());
            assertThat(user.getValue()).isSameAs(testUser);
        }

        @Test
        void shouldReturn200WithEmptyListWhenCatalogEmpty() throws Exception {
            authenticate();
            when(cosmeticService.catalog(any())).thenReturn(List.of());

            mockMvc.perform(get(CATALOG))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());

            verify(cosmeticService).catalog(eq(testUser));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(cosmeticService.catalog(any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(CATALOG))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }

        @Test
        void shouldReturn404WhenServiceThrowsNotFound() throws Exception {
            authenticate();
            when(cosmeticService.catalog(any()))
                    .thenThrow(new NotFoundException("Reputation not found", "TM_101"));

            mockMvc.perform(get(CATALOG))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_101"));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /cosmetics/me
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /me")
    class Mine {

        @Test
        void shouldReturn200WithOwnedCosmeticsAndForwardUser() throws Exception {
            authenticate();
            when(cosmeticService.myCosmetics(any())).thenReturn(List.of(
                    cosmetic("frame_gold", CosmeticType.FRAME, true, true, false)));

            mockMvc.perform(get(MINE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data[0].code").value("frame_gold"))
                    .andExpect(jsonPath("$.data[0].owned").value(true))
                    .andExpect(jsonPath("$.data[0].equipped").value(true));

            ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
            verify(cosmeticService).myCosmetics(user.capture());
            assertThat(user.getValue()).isSameAs(testUser);
        }

        @Test
        void shouldReturn200WithEmptyListWhenNoneOwned() throws Exception {
            authenticate();
            when(cosmeticService.myCosmetics(any())).thenReturn(List.of());

            mockMvc.perform(get(MINE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());

            verify(cosmeticService).myCosmetics(eq(testUser));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(cosmeticService.myCosmetics(any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(MINE))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  PUT /cosmetics/equip   (body: {"code": "..."})
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /equip")
    class Equip {

        @Test
        void shouldReturn200AndForwardCodeAndUser() throws Exception {
            authenticate();
            when(cosmeticService.equip(any(), any())).thenReturn(List.of(
                    cosmetic("frame_gold", CosmeticType.FRAME, true, true, false)));

            mockMvc.perform(put(EQUIP)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"frame_gold\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Cosmetic equipped"))
                    .andExpect(jsonPath("$.messageCode").value("TM_066"))
                    .andExpect(jsonPath("$.data[0].code").value("frame_gold"))
                    .andExpect(jsonPath("$.data[0].equipped").value(true));

            ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
            ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
            verify(cosmeticService).equip(user.capture(), code.capture());
            assertThat(user.getValue()).isSameAs(testUser);
            assertThat(code.getValue()).isEqualTo("frame_gold");
        }

        @Test
        void shouldForwardNullCodeWhenBodyMissingCodeKey() throws Exception {
            authenticate();
            when(cosmeticService.equip(any(), any())).thenReturn(List.of());

            // Empty JSON object is a valid Map — body != null, so code resolves to null and is
            // forwarded to the service as-is (the controller performs no validation on it).
            mockMvc.perform(put(EQUIP)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
            verify(cosmeticService).equip(eq(testUser), code.capture());
            assertThat(code.getValue()).isNull();
        }

        @Test
        void shouldForwardBlankCodeUntouched() throws Exception {
            authenticate();
            when(cosmeticService.equip(any(), any())).thenReturn(List.of());

            mockMvc.perform(put(EQUIP)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"   \"}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
            verify(cosmeticService).equip(eq(testUser), code.capture());
            assertThat(code.getValue()).isEqualTo("   ");
        }

        @Test
        void shouldForwardUnicodeAndEmojiCode() throws Exception {
            authenticate();
            when(cosmeticService.equip(any(), any())).thenReturn(List.of());

            mockMvc.perform(put(EQUIP)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"frame_名前_😀\"}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
            verify(cosmeticService).equip(eq(testUser), code.capture());
            assertThat(code.getValue()).isEqualTo("frame_名前_😀");
        }

        @Test
        void shouldPassThroughXssAndSqliCodeUnsanitised() throws Exception {
            authenticate();
            when(cosmeticService.equip(any(), any())).thenReturn(List.of());

            // The controller does not sanitise — payload must reach the service verbatim so the
            // service/persistence layer stays the single sanitisation authority.
            String payload = "<script>alert(1)</script>'; DROP TABLE cosmetics;--";
            mockMvc.perform(put(EQUIP)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"" + payload.replace("\"", "\\\"") + "\"}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
            verify(cosmeticService).equip(eq(testUser), code.capture());
            assertThat(code.getValue()).isEqualTo(payload);
        }

        @Test
        void shouldReturn404WhenCosmeticNotOwned() throws Exception {
            authenticate();
            when(cosmeticService.equip(any(), any()))
                    .thenThrow(new NotFoundException("Cosmetic not owned", "TM_101"));

            mockMvc.perform(put(EQUIP)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"frame_gold\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_101"));
        }

        @Test
        void shouldReturn409WhenAlreadyEquipped() throws Exception {
            authenticate();
            when(cosmeticService.equip(any(), any()))
                    .thenThrow(new ConflictException("Already equipped", "TM_409"));

            mockMvc.perform(put(EQUIP)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"frame_gold\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.messageCode").value("TM_409"));
        }

        @Test
        void shouldReturn400WhenServiceRejectsCode() throws Exception {
            authenticate();
            when(cosmeticService.equip(any(), any()))
                    .thenThrow(new BadRequestException("Unknown cosmetic code", "TM_400"));

            mockMvc.perform(put(EQUIP)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"nope\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_400"));
        }

        @Test
        void shouldReturn403WhenServiceForbids() throws Exception {
            authenticate();
            when(cosmeticService.equip(any(), any()))
                    .thenThrow(new ForbiddenException("Locked cosmetic", "TM_103"));

            mockMvc.perform(put(EQUIP)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"frame_gold\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_103"));
        }

        @Test
        void shouldReturn500WhenBodyMalformed() throws Exception {
            authenticate();

            // Unreadable JSON → HttpMessageNotReadableException; no dedicated handler → catch-all 500.
            mockMvc.perform(put(EQUIP)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{bad"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(cosmeticService);
        }

        @Test
        void shouldReturn500WhenBodyAbsent() throws Exception {
            authenticate();

            // @RequestBody is required by default; a missing body → HttpMessageNotReadableException
            // → catch-all 500 (pins current behaviour).
            mockMvc.perform(put(EQUIP).contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(cosmeticService);
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(cosmeticService.equip(any(), any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(put(EQUIP)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"frame_gold\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  DELETE /cosmetics/equip/{slot}
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /equip/{slot}")
    class Unequip {

        @Test
        void shouldReturn200AndForwardParsedSlotAndUser() throws Exception {
            authenticate();
            when(cosmeticService.unequip(any(), any())).thenReturn(List.of(
                    cosmetic("frame_gold", CosmeticType.FRAME, true, false, false)));

            mockMvc.perform(delete(EQUIP + "/FRAME"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Cosmetic unequipped"))
                    .andExpect(jsonPath("$.messageCode").value("TM_066"))
                    .andExpect(jsonPath("$.data[0].code").value("frame_gold"))
                    .andExpect(jsonPath("$.data[0].equipped").value(false));

            ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
            ArgumentCaptor<CosmeticType> slot = ArgumentCaptor.forClass(CosmeticType.class);
            verify(cosmeticService).unequip(user.capture(), slot.capture());
            assertThat(user.getValue()).isSameAs(testUser);
            assertThat(slot.getValue()).isEqualTo(CosmeticType.FRAME);
        }

        @Test
        void shouldAcceptLowercaseSlotCaseInsensitively() throws Exception {
            authenticate();
            when(cosmeticService.unequip(any(), any())).thenReturn(List.of());

            mockMvc.perform(delete(EQUIP + "/chat_bubble"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_066"));

            ArgumentCaptor<CosmeticType> slot = ArgumentCaptor.forClass(CosmeticType.class);
            verify(cosmeticService).unequip(eq(testUser), slot.capture());
            assertThat(slot.getValue()).isEqualTo(CosmeticType.CHAT_BUBBLE);
        }

        @Test
        void shouldReturn200WithEmptyListWhenNothingEquippedInSlot() throws Exception {
            authenticate();
            when(cosmeticService.unequip(any(), any())).thenReturn(List.of());

            mockMvc.perform(delete(EQUIP + "/BADGE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());

            verify(cosmeticService).unequip(eq(testUser), eq(CosmeticType.BADGE));
        }

        @Test
        void shouldReturn400AndSkipServiceWhenSlotUnknown() throws Exception {
            authenticate();

            // Manual enum parse in the controller catches IllegalArgumentException and rethrows a
            // BadRequestException(TM_934) — the service is never touched.
            mockMvc.perform(delete(EQUIP + "/NOT_A_SLOT"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.messageCode").value("TM_934"));

            verify(cosmeticService, never()).unequip(any(), any());
            verifyNoInteractions(cosmeticService);
        }

        @Test
        void shouldReturn400WhenSlotIsBlankPlaceholder() throws Exception {
            authenticate();

            // A whitespace-only slot trims to "" which is not a valid enum → TM_934, service skipped.
            mockMvc.perform(delete(EQUIP + "/%20%20"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_934"));

            verifyNoInteractions(cosmeticService);
        }

        @Test
        void shouldReturn404WhenServiceThrowsNotFound() throws Exception {
            authenticate();
            when(cosmeticService.unequip(any(), any()))
                    .thenThrow(new NotFoundException("Reputation not found", "TM_101"));

            mockMvc.perform(delete(EQUIP + "/FRAME"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_101"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(cosmeticService.unequip(any(), any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(delete(EQUIP + "/FRAME"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }
}
