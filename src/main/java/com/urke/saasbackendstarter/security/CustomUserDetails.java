package com.urke.saasbackendstarter.security;

import com.urke.saasbackendstarter.domain.Role;
import com.urke.saasbackendstarter.domain.User;
import com.urke.saasbackendstarter.domain.Permission;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Custom UserDetails implementation for Spring Security.
 * Wraps the application's User entity.
 */
@RequiredArgsConstructor
public class CustomUserDetails implements UserDetails {

    private final User user;

    public User getUser() {
        return user;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        Set<GrantedAuthority> authorities = new HashSet<>();
        for (Role role : getScopedRoles()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role.getName()));
        }
        getScopedPermissions().forEach(permission -> authorities.add(new SimpleGrantedAuthority(permission.getName())));
        return authorities;
    }

    public Set<Role> getScopedRoles() {
        if (user.getOrganization() == null || user.getRoles() == null) return Set.of();
        return user.getRoles().stream().filter(role -> role.getOrganization() != null
                && Objects.equals(role.getOrganization().getId(), user.getOrganization().getId())).collect(Collectors.toSet());
    }

    public Set<Permission> getScopedPermissions() {
        return getScopedRoles().stream().filter(role -> role.getPermissions() != null).flatMap(role -> role.getPermissions().stream())
                .filter(permission -> permission.getOrganization() != null
                        && Objects.equals(permission.getOrganization().getId(), user.getOrganization().getId())
                        && permission.getName() != null && !permission.getName().startsWith("ROLE_"))
                .collect(Collectors.toSet());
    }

    @Override
    public String getPassword() { return user.getPassword(); }

    @Override
    public String getUsername() { return user.getEmail(); }

    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() { return true; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }

    @Override
    public boolean isEnabled() { return user.isEnabled() && user.isEmailVerified() && !user.isDeleted(); }
}
