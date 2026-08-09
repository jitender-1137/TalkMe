package com.chat.talkMe.security;

import com.chat.talkMe.domain.User;
import lombok.Getter;
import lombok.NonNull;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Spring Security {@link UserDetails} adapter wrapping the domain {@link User}. Flattens the
 * user's roles (as {@code ROLE_...} authorities) and each role's permissions (as raw authorities),
 * and maps account-status flags (deleted / banned) onto the Spring Security lifecycle checks.
 */
@Getter
public class CustomUserDetails implements UserDetails {
    @Serial
    private static final long serialVersionUID = 1L;

    private final User user;
    private final Collection<? extends GrantedAuthority> authorities;

    /**
     * Wraps the given user, building its granted authorities from roles and their permissions.
     *
     * @param user the domain user to adapt
     */
    public CustomUserDetails(User user) {
        this.user = user;

        List<SimpleGrantedAuthority> auths = new ArrayList<>();
        // Add roles as ROLE_...
        user.getRoles().forEach(role -> {
            auths.add(new SimpleGrantedAuthority(role.getName()));
            // Add corresponding permissions as raw authorities
            role.getPermissions().forEach(permission ->
                    auths.add(new SimpleGrantedAuthority(permission.getName()))
            );
        });
        this.authorities = auths;
    }

    public Long getId() {
        return user.getId();
    }

    public String getEmail() {
        return user.getEmail();
    }

    public boolean isGuest() {
        return user.isGuest();
    }

    @Override
    @NonNull
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return user.getPasswordHash();
    }

    @Override
    @NonNull
    public String getUsername() {
        return user.getUsername();
    }

    /**
     * @return {@code true} unless the account is (soft-)deleted
     */
    @Override
    public boolean isAccountNonExpired() {
        return !user.isDeleted();
    }

    /**
     * @return {@code true} unless the account is deleted or banned (a banned account is locked out)
     */
    @Override
    public boolean isAccountNonLocked() {
        // A banned account is locked out of authentication.
        return !user.isDeleted() && !user.isBanned();
    }

    /**
     * @return {@code true} unless the account is (soft-)deleted
     */
    @Override
    public boolean isCredentialsNonExpired() {
        return !user.isDeleted();
    }

    /**
     * @return {@code true} only when the account is neither deleted nor banned
     */
    @Override
    public boolean isEnabled() {
        return !user.isDeleted() && !user.isBanned();
    }
}
