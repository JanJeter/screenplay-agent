package com.urke.saasbackendstarter.admin;

import com.urke.saasbackendstarter.audit.AuditLogService;
import com.urke.saasbackendstarter.domain.*;
import com.urke.saasbackendstarter.dto.user.UserUpdateRequest;
import com.urke.saasbackendstarter.repository.*;
import com.urke.saasbackendstarter.security.CurrentUserProvider;
import com.urke.saasbackendstarter.service.AccountLifecycleService;
import com.urke.saasbackendstarter.service.impl.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class LegacyManagementBoundaryTest {
    @Mock CurrentUserProvider current;
    @Mock UserRepository users;
    @Mock RoleRepository roles;
    @Mock PermissionRepository permissions;
    @Mock OrganizationRepository organizations;
    @Mock RefreshTokenRepository refreshTokens;
    @Mock AccountLifecycleService accounts;
    @Mock BCryptPasswordEncoder encoder;
    @Mock ApplicationEventPublisher publisher;
    @Mock MessageSource messages;
    @Mock EntityManager entityManager;
    @Mock AuditLogService audit;
    private Organization org, foreign;
    private User actor, member;
    private UserServiceImpl userService;
    private RoleServiceImpl roleService;
    private PermissionServiceImpl permissionService;
    @BeforeEach void setup() {
        org = Organization.builder().id(1L).name("Own").slug("own").build();
        foreign = Organization.builder().id(2L).name("Other").slug("other").build();
        Role admin = Role.builder().id(1L).name("ADMIN").organization(org).permissions(Set.of()).build();
        Role user = Role.builder().id(2L).name("USER").organization(org).permissions(Set.of()).build();
        actor = User.builder().id(10L).email("admin@example.test").fullName("Admin").organization(org).roles(Set.of(admin)).build();
        member = User.builder().id(20L).email("member@example.test").fullName("Member").organization(org).roles(Set.of(user)).build();
        lenient().when(current.getCurrentUser()).thenReturn(actor);
        lenient().when(organizations.findLockedById(1L)).thenReturn(Optional.of(org));
        lenient().when(users.findLockedByIdAndOrganizationIdAndDeletedFalse(10L,1L)).thenReturn(Optional.of(actor));
        AdminAccessGuard guard = new AdminAccessGuard(current,organizations,users,entityManager);
        userService = new UserServiceImpl(users,roles,organizations,encoder,publisher,messages,accounts,refreshTokens,guard,audit);
        roleService = new RoleServiceImpl(roles,messages,guard,audit);
        permissionService = new PermissionServiceImpl(permissions,guard,audit);
    }
    @Test void oldRoleAndPermissionListingCannotSelectAnotherOrganization() {
        assertThatThrownBy(() -> roleService.findAllByOrganizationId(2L)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        assertThatThrownBy(() -> permissionService.findAllByOrganizationId(2L)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        verifyNoInteractions(roles,permissions);
    }
    @Test void oldRoleCreationRejectsForeignPermissionAndSystemRole() {
        Permission foreignPermission = Permission.builder().id(5L).name("USER_VIEW_ALL").organization(foreign).build();
        assertThatThrownBy(() -> roleService.save(Role.builder().name("STAFF").organization(org).permissions(Set.of(foreignPermission)).build()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        assertThatThrownBy(() -> roleService.save(Role.builder().name("admin").organization(org).permissions(Set.of()).build()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        verify(roles,never()).save(any());
    }
    @Test void oldPermissionEndpointCannotMintRoleAuthority() {
        assertThatThrownBy(() -> permissionService.save(Permission.builder().name("ROLE_ADMIN").organization(org).build()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        assertThatThrownBy(() -> permissionService.save(Permission.builder().name("USER_VIEW_ALL").organization(foreign).build()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        verify(permissions,never()).save(any());
    }
    @Test void oldAdminUserListingIsAlwaysTenantScoped() {
        when(users.searchInOrganization(eq(1L),eq(""),any())).thenReturn(Page.empty());
        assertThat(userService.findAll(PageRequest.of(0,20))).isEmpty();
        verify(users).searchInOrganization(eq(1L),eq(""),any());
        verify(users,never()).findAllByDeletedFalse(any(Pageable.class));
    }
    @Test void oldUserUpdateAndDeleteCannotReachForeignUser() {
        when(users.findLockedByIdAndOrganizationIdAndDeletedFalse(99L,1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> userService.updateUser(99L,new UserUpdateRequest("Name",null))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        assertThatThrownBy(() -> userService.deleteUser(99L)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        verify(users,never()).save(any());
    }
    @Test void oldSelfDeleteCannotRemoveAdministrator() {
        assertThatThrownBy(() -> userService.deleteUser(10L)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        verify(users,never()).save(any());
    }
    @Test void oldPasswordChangeLocksUserRevokesSessionsAndAuditsWithoutSecrets() {
        when(users.findLockedByIdAndOrganizationIdAndDeletedFalse(20L,1L)).thenReturn(Optional.of(member));
        when(encoder.encode("Strong-Password-2026!")).thenReturn("encoded");
        when(users.save(member)).thenReturn(member);
        userService.updateUser(20L,new UserUpdateRequest("Changed","Strong-Password-2026!"));
        assertThat(member.getTokenVersion()).isEqualTo(1);
        assertThat(member.getPassword()).isEqualTo("encoded");
        InOrder locks = inOrder(organizations,users,entityManager,refreshTokens);
        locks.verify(organizations).findLockedById(1L);
        locks.verify(users).findLockedByIdAndOrganizationIdAndDeletedFalse(10L,1L);
        locks.verify(entityManager).refresh(actor);
        locks.verify(users).findLockedByIdAndOrganizationIdAndDeletedFalse(20L,1L);
        locks.verify(entityManager).refresh(member);
        locks.verify(refreshTokens).deleteByUser(member);
        verify(audit).log(eq("USER_PROFILE_UPDATED"),eq("User"),eq(20L),eq("更新工作区成员资料"),eq(actor.getEmail()));
    }
}
