package com.chat.talkMe.security;

import com.chat.talkMe.domain.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Getter
public class CustomUserDetails implements UserDetails {

    private final User user;
    private final Collection<? extends GrantedAuthority> authorities;

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
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return user.getPasswordHash();
    }

    @Override
    public String getUsername() {
        return user.getUsername();
    }

    @Override
    public boolean isAccountNonExpired() {
        return !user.isDeleted();
    }

    @Override
    public boolean isAccountNonLocked() {
        // A banned account is locked out of authentication.
        return !user.isDeleted() && !user.isBanned();
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return !user.isDeleted();
    }

    @Override
    public boolean isEnabled() {
        return !user.isDeleted() && !user.isBanned();
    }
}
