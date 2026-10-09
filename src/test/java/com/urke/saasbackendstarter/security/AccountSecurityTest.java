package com.urke.saasbackendstarter.security;

import com.urke.saasbackendstarter.domain.*;
import com.urke.saasbackendstarter.repository.UserRepository;
import com.urke.saasbackendstarter.screenplay.agent.security.AgentExecutionClaims;
import com.urke.saasbackendstarter.screenplay.repository.AgentRunRepository;
import com.urke.saasbackendstarter.screenplay.repository.ScriptVersionRepository;
import com.urke.saasbackendstarter.screenplay.service.AgentAuthorizationService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.FilterChain;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountSecurityTest {
    private JwtTokenProvider provider() {
        var provider = new JwtTokenProvider();
        ReflectionTestUtils.setField(provider, "secret", "unit-test-only-jwt-signing-key-with-more-than-32-bytes");
        ReflectionTestUtils.setField(provider, "expirationMs", 3600000L);
        return provider;
    }
    private User user() { return User.builder().id(1L).email("test@example.test").roles(Set.of()).build(); }

    @Test void accessTokenStopsWorkingWhenPasswordVersionOrAccountStateChanges() {
        var provider = provider(); User user = user(); var details = new CustomUserDetails(user);
        String token = provider.generateToken(details);
        assertThat(provider.validateToken(token, details)).isTrue();
        user.setTokenVersion(1); assertThat(provider.validateToken(token, details)).isFalse();
        user.setTokenVersion(0); user.setEnabled(false); assertThat(provider.validateToken(token, details)).isFalse();
        user.setEnabled(true); user.setEmailVerified(false); assertThat(provider.validateToken(token, details)).isFalse();
        user.setEmailVerified(true); user.setDeleted(true); assertThat(provider.validateToken(token, details)).isFalse();
    }

    @Test void invalidAndExpiredBearerTokensBecome401InsteadOfServerErrors() throws Exception {
        var provider = provider();
        ReflectionTestUtils.setField(provider, "expirationMs", -1000L);
        for (String token : List.of("malformed-test-token", provider.generateToken(new CustomUserDetails(user())))) {
            UserDetailsService users = mock(UserDetailsService.class); FilterChain chain = mock(FilterChain.class);
            var request = new MockHttpServletRequest("GET", "/api/v1/auth/me"); request.addHeader("Authorization", "Bearer " + token);
            var response = new MockHttpServletResponse();
            new JwtAuthenticationFilter(provider, users).doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(401); verifyNoInteractions(chain, users);
            assertThat(response.getContentAsString().contains(token)).isFalse();
        }
        SecurityContextHolder.clearContext();
    }

    @Test void foreignRolesAndRoleNamedPermissionsNeverGrantAuthority() {
        Organization own = Organization.builder().id(1L).build(), foreign = Organization.builder().id(2L).build();
        Permission allowed = Permission.builder().name("USER_UPDATE_SELF").organization(own).build();
        Permission injection = Permission.builder().name("ROLE_ADMIN").organization(own).build();
        Permission foreignPermission = Permission.builder().name("USER_DELETE").organization(foreign).build();
        Role local = Role.builder().name("USER").organization(own).permissions(Set.of(allowed, injection, foreignPermission)).build();
        Role foreignAdmin = Role.builder().name("ADMIN").organization(foreign).permissions(Set.of()).build();
        User user = user(); user.setOrganization(own); user.setRoles(Set.of(local, foreignAdmin));
        var details = new CustomUserDetails(user);
        assertThat(details.getAuthorities()).extracting("authority").containsExactlyInAnyOrder("ROLE_USER", "USER_UPDATE_SELF");
        assertThat(details.getScopedRoles()).containsExactly(local); assertThat(details.getScopedPermissions()).containsExactly(allowed);
    }

    @Test void disabledOrUnverifiedAccountsCannotUseExistingAgentExecutionCapabilities() {
        UserRepository users = mock(UserRepository.class); ScriptVersionRepository scripts = mock(ScriptVersionRepository.class);
        AgentRunRepository runs = mock(AgentRunRepository.class);
        var service = new AgentAuthorizationService(users, scripts, runs);
        var claims = new AgentExecutionClaims(1L, 1L, 2L, 3L, "run", "session", Set.of("storyboard:write"));
        User user = user(); when(users.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(user));
        user.setEnabled(false);
        assertThatThrownBy(() -> service.require(claims, 2L, 3L, "storyboard:write")).isInstanceOf(ResponseStatusException.class).hasMessageContaining("401");
        user.setEnabled(true); user.setEmailVerified(false);
        assertThatThrownBy(() -> service.require(claims, 2L, 3L, "storyboard:write")).isInstanceOf(ResponseStatusException.class).hasMessageContaining("401");
        verifyNoInteractions(scripts, runs);
    }
}
