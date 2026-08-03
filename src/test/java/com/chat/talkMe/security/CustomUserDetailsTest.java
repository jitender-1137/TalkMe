package com.chat.talkMe.security;

import com.chat.talkMe.domain.Permission;
import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test for {@link CustomUserDetails} — the Spring Security {@code UserDetails}
 * adapter over the domain {@link User}. Covers authority flattening (roles + their
 * permissions), the passthrough getters, and the four account-status flags whose truth
 * table is driven by the {@code isDeleted}/{@code banned} security fields.
 */
@DisplayName("CustomUserDetails (unit)")
class CustomUserDetailsTest {

    private static Role roleWithPerms(String roleName, String... perms) {
        Set<Permission> permissions = java.util.Arrays.stream(perms)
                .map(p -> Permission.builder().name(p).build())
                .collect(Collectors.toSet());
        return Role.builder().name(roleName).permissions(permissions).build();
    }

    private static User baseUser() {
        User u = User.builder()
                .username("alice")
                .email("alice@example.com")
                .passwordHash("hashed-secret")
                .isGuest(false)
                .banned(false)
                .roles(Set.of(roleWithPerms("ROLE_USER", "READ_MESSAGE")))
                .build();
        u.setId(42L);
        return u;
    }

    @Nested
    @DisplayName("authorities")
    class Authorities {

        @Test
        @DisplayName("flattens each role and its permissions into granted authorities")
        void flattensRolesAndPermissions() {
            User u = User.builder()
                    .username("bob")
                    .roles(Set.of(roleWithPerms("ROLE_ADMIN", "READ", "WRITE")))
                    .build();

            CustomUserDetails details = new CustomUserDetails(u);

            Set<String> names = details.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .collect(Collectors.toSet());
            assertThat(names).containsExactlyInAnyOrder("ROLE_ADMIN", "READ", "WRITE");
        }

        @Test
        @DisplayName("role with no permissions → only the role authority")
        void roleWithoutPermissions() {
            User u = User.builder()
                    .username("bob")
                    .roles(Set.of(Role.builder().name("ROLE_USER").build()))
                    .build();

            CustomUserDetails details = new CustomUserDetails(u);

            assertThat(details.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority))
                    .containsExactly("ROLE_USER");
        }

        @Test
        @DisplayName("no roles → empty authority collection")
        void noRoles() {
            User u = User.builder().username("bob").roles(Set.of()).build();

            CustomUserDetails details = new CustomUserDetails(u);

            assertThat(details.getAuthorities()).isEmpty();
        }

        @Test
        @DisplayName("getAuthorities returns the same collection built in the constructor")
        void authoritiesGetterConsistent() {
            CustomUserDetails details = new CustomUserDetails(baseUser());
            assertThat(details.getAuthorities()).isSameAs(details.getAuthorities());
        }
    }

    @Nested
    @DisplayName("passthrough getters")
    class Getters {

        @Test
        @DisplayName("id / email / username / password / guest reflect the wrapped user")
        void passthrough() {
            CustomUserDetails details = new CustomUserDetails(baseUser());

            assertThat(details.getId()).isEqualTo(42L);
            assertThat(details.getEmail()).isEqualTo("alice@example.com");
            assertThat(details.getUsername()).isEqualTo("alice");
            assertThat(details.getPassword()).isEqualTo("hashed-secret");
            assertThat(details.isGuest()).isFalse();
            assertThat(details.getUser()).isNotNull();
        }

        @Test
        @DisplayName("guest account → isGuest true")
        void guestUser() {
            User u = User.builder().username("g").isGuest(true).roles(Set.of()).build();
            assertThat(new CustomUserDetails(u).isGuest()).isTrue();
        }

        @Test
        @DisplayName("null password hash (OAuth/guest account) is returned as-is")
        void nullPassword() {
            User u = User.builder().username("g").roles(Set.of()).build();
            assertThat(new CustomUserDetails(u).getPassword()).isNull();
        }
    }

    @Nested
    @DisplayName("account status flags")
    class StatusFlags {

        @Test
        @DisplayName("active account → all flags true (enabled, non-locked, non-expired)")
        void activeAccount() {
            CustomUserDetails details = new CustomUserDetails(baseUser());

            assertThat(details.isAccountNonExpired()).isTrue();
            assertThat(details.isAccountNonLocked()).isTrue();
            assertThat(details.isCredentialsNonExpired()).isTrue();
            assertThat(details.isEnabled()).isTrue();
        }

        @Test
        @DisplayName("banned account → locked and disabled, but not expired")
        void bannedAccount() {
            User u = baseUser();
            u.setBanned(true);

            CustomUserDetails details = new CustomUserDetails(u);

            assertThat(details.isAccountNonLocked()).isFalse();
            assertThat(details.isEnabled()).isFalse();
            // Expiry is driven only by deletion, not by ban.
            assertThat(details.isAccountNonExpired()).isTrue();
            assertThat(details.isCredentialsNonExpired()).isTrue();
        }

        @Test
        @DisplayName("deleted account → expired, credentials-expired, locked and disabled")
        void deletedAccount() {
            User u = baseUser();
            u.setDeleted(true);

            CustomUserDetails details = new CustomUserDetails(u);

            assertThat(details.isAccountNonExpired()).isFalse();
            assertThat(details.isCredentialsNonExpired()).isFalse();
            assertThat(details.isAccountNonLocked()).isFalse();
            assertThat(details.isEnabled()).isFalse();
        }

        @Test
        @DisplayName("deleted AND banned account → all flags false")
        void deletedAndBanned() {
            User u = baseUser();
            u.setDeleted(true);
            u.setBanned(true);

            CustomUserDetails details = new CustomUserDetails(u);

            assertThat(details.isAccountNonExpired()).isFalse();
            assertThat(details.isCredentialsNonExpired()).isFalse();
            assertThat(details.isAccountNonLocked()).isFalse();
            assertThat(details.isEnabled()).isFalse();
        }
    }
}
