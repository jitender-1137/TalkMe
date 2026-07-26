package com.chat.talkMe.security;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link JwtTokenProvider}. Constructs the provider directly with a test secret and
 * exercises token generation, parsing, validation, and the delivery-token path — including the
 * security-critical rejection of delivery-scoped tokens on the access-token validation path.
 */
@DisplayName("JwtTokenProvider (unit)")
class JwtTokenProviderUnitTest {

    // Base64 secret from application-test.yml (decodes to a ≥256-bit HS256 key).
    private static final String SECRET =
            "c2VjdXJla2V5c2VjdXJla2V5c2VjdXJla2V5c2VjdXJla2V5c2VjdXJla2V5c2VjdXJla2V5";
    // A different, independently-valid HS256 key (for wrong-signature tests).
    private static final String OTHER_SECRET =
            "YW5vdGhlcmtleWFub3RoZXJrZXlhbm90aGVya2V5YW5vdGhlcmtleWFub3RoZXI=";
    private static final long TTL_MS = 900_000L;

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider(SECRET, TTL_MS);
    }

    private static User user(String username, boolean guest) {
        return User.builder().username(username).email(username + "@e.com").name("N")
                .isGuest(guest).roles(Set.of(Role.builder().name("ROLE_USER").build())).build();
    }

    // ── generate / parse ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("generate & parse")
    class GenerateParse {

        @Test
        void shouldRoundTripUsername() {
            String token = provider.generateToken("alice", false);
            assertThat(provider.getUsernameFromToken(token)).isEqualTo("alice");
        }

        @Test
        void shouldGenerateFromAuthenticationPrincipal() {
            CustomUserDetails cud = new CustomUserDetails(user("bob", true));
            Authentication auth = new UsernamePasswordAuthenticationToken(cud, null, cud.getAuthorities());

            String token = provider.generateToken(auth);

            assertThat(provider.getUsernameFromToken(token)).isEqualTo("bob");
            assertThat(provider.validateToken(token)).isTrue();
        }

        @Test
        void shouldPreserveUnicodeUsername() {
            String token = provider.generateToken("名字_😀", false);
            assertThat(provider.getUsernameFromToken(token)).isEqualTo("名字_😀");
        }
    }

    // ── validate ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("validateToken")
    class Validate {

        @Test
        void shouldAcceptFreshToken() {
            assertThat(provider.validateToken(provider.generateToken("alice", false))).isTrue();
        }

        @Test
        void shouldRejectExpiredToken() {
            JwtTokenProvider shortLived = new JwtTokenProvider(SECRET, -1_000L); // already expired
            String token = shortLived.generateToken("alice", false);
            assertThat(provider.validateToken(token)).isFalse();
        }

        @Test
        void shouldRejectTokenSignedWithDifferentKey() {
            JwtTokenProvider other = new JwtTokenProvider(OTHER_SECRET, TTL_MS);
            String foreign = other.generateToken("alice", false);
            assertThat(provider.validateToken(foreign)).isFalse();
        }

        @Test
        void shouldRejectMalformedToken() {
            assertThat(provider.validateToken("not-a-jwt")).isFalse();
        }

        @Test
        void shouldRejectEmptyToken() {
            assertThat(provider.validateToken("")).isFalse();
        }

        @Test
        void shouldRejectTamperedToken() {
            String token = provider.generateToken("alice", false);
            String tampered = token.substring(0, token.length() - 3) + "abc";
            assertThat(provider.validateToken(tampered)).isFalse();
        }

        @Test
        void shouldRejectDeliveryTokenOnAccessPath() {
            // SECURITY: a push-delivery token is signed with the same key but must NEVER be
            // accepted as a Bearer access credential.
            String delivery = provider.generateDeliveryToken("alice", "chat-1");
            assertThat(provider.validateToken(delivery)).isFalse();
        }
    }

    // ── delivery tokens ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("delivery tokens")
    class Delivery {

        @Test
        void shouldParseValidDeliveryToken() {
            String token = provider.generateDeliveryToken("alice", "chat-1");
            Claims claims = provider.parseDeliveryToken(token);

            assertThat(claims).isNotNull();
            assertThat(claims.getSubject()).isEqualTo("alice");
            assertThat(claims.get("chatUuid", String.class)).isEqualTo("chat-1");
            assertThat(claims.get("purpose", String.class)).isEqualTo("push-delivery");
        }

        @Test
        void shouldReturnNullWhenParsingAccessTokenAsDelivery() {
            String access = provider.generateToken("alice", false);
            assertThat(provider.parseDeliveryToken(access)).isNull();
        }

        @Test
        void shouldReturnNullForMalformedDeliveryToken() {
            assertThat(provider.parseDeliveryToken("garbage")).isNull();
        }

        @Test
        void shouldReturnNullForDeliveryTokenSignedWithDifferentKey() {
            JwtTokenProvider other = new JwtTokenProvider(OTHER_SECRET, TTL_MS);
            String foreign = other.generateDeliveryToken("alice", "chat-1");
            assertThat(provider.parseDeliveryToken(foreign)).isNull();
        }
    }
}
