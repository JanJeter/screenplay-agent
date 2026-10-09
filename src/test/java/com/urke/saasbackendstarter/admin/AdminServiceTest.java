package com.urke.saasbackendstarter.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urke.saasbackendstarter.audit.AuditLogService;
import com.urke.saasbackendstarter.domain.*;
import com.urke.saasbackendstarter.repository.*;
import com.urke.saasbackendstarter.screenplay.domain.*;
import com.urke.saasbackendstarter.screenplay.repository.*;
import com.urke.saasbackendstarter.security.CurrentUserProvider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class AdminServiceTest {
    @Mock CurrentUserProvider current;
    @Mock UserRepository users;
    @Mock OrganizationRepository organizations;
    @Mock RoleRepository roles;
    @Mock PermissionRepository permissions;
    @Mock AgentRunRepository runs;
    @Mock AgentEventRepository events;
    @Mock AuditLogService audit;
    @Mock EntityManager entityManager;
    private Organization org;
    private Role adminRole, userRole;
    private User actor, member;
    private AdminAccessGuard guard;
    private AdminService service;
    @BeforeEach void setup() {
        org = Organization.builder().id(7L).name("Team").slug("team").build();
        adminRole = Role.builder().id(1L).name("ADMIN").organization(org).permissions(Set.of()).build();
        userRole = Role.builder().id(2L).name("USER").organization(org).permissions(Set.of()).build();
        actor = user(10L, Set.of(adminRole)); member = user(20L, Set.of(userRole));
        lenient().when(current.getCurrentUser()).thenReturn(actor);
        lenient().when(organizations.findLockedById(7L)).thenReturn(Optional.of(org));
        lenient().when(users.findLockedByIdAndOrganizationIdAndDeletedFalse(10L,7L)).thenReturn(Optional.of(actor));
        guard = new AdminAccessGuard(current, organizations, users, entityManager);
        service = new AdminService(guard, users, roles, permissions, runs, events, new AdminUsageCalculator(new ObjectMapper()), audit);
    }
    @Test void nonAdminIsRejectedByServiceEvenWithoutController() {
        when(current.getCurrentUser()).thenReturn(member);
        assertThatThrownBy(() -> service.users(0,20,"")).isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
        assertThatThrownBy(service::roles).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(service::usage).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.setEnabled(10L,false)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(runs, events, audit);
    }
    @Test void memberSearchAlwaysUsesAuthenticatedOrganization() {
        when(users.searchInOrganization(eq(7L),eq("Lin"),any())).thenReturn(new PageImpl<>(List.of(member), PageRequest.of(0,20),1));
        var result = service.users(0,20," Lin ");
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().email()).isEqualTo("user20@example.test");
        verify(users).searchInOrganization(eq(7L),eq("Lin"),any());
    }
    @Test void foreignUserNeverChangesAndNotFoundDoesNotRevealIt() {
        when(users.findLockedByIdAndOrganizationIdAndDeletedFalse(99L,7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.setEnabled(99L,false)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        verify(users,never()).save(any()); verifyNoInteractions(audit);
    }
    @Test void selfDisableAndSelfDemotionAreRejected() {
        when(users.findLockedByIdAndOrganizationIdAndDeletedFalse(10L,7L)).thenReturn(Optional.of(actor));
        when(roles.findByIdAndOrganizationId(2L,7L)).thenReturn(Optional.of(userRole));
        assertThatThrownBy(() -> service.setEnabled(10L,false)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        assertThatThrownBy(() -> service.setRoles(10L,Set.of(2L))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        verify(users,never()).save(any());
    }
    @Test void lastActiveAdministratorIsProtectedInsideTenantLock() {
        member.setRoles(Set.of(adminRole));
        when(users.findLockedByIdAndOrganizationIdAndDeletedFalse(20L,7L)).thenReturn(Optional.of(member));
        when(users.countActiveAdmins(7L)).thenReturn(1L);
        assertThatThrownBy(() -> service.setEnabled(20L,false)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        verify(organizations).findLockedById(7L); verify(entityManager).refresh(actor);
        verify(users,never()).save(any());
    }
    @Test void changingEnabledRevokesOldTokensAndWritesAudit() {
        when(users.findLockedByIdAndOrganizationIdAndDeletedFalse(20L,7L)).thenReturn(Optional.of(member));
        service.setEnabled(20L,false);
        InOrder lockOrder = inOrder(organizations, users, entityManager);
        lockOrder.verify(organizations).findLockedById(7L);
        lockOrder.verify(users).findLockedByIdAndOrganizationIdAndDeletedFalse(10L,7L);
        lockOrder.verify(entityManager).refresh(actor);
        lockOrder.verify(users).findLockedByIdAndOrganizationIdAndDeletedFalse(20L,7L);
        lockOrder.verify(entityManager).refresh(member);
        assertThat(member.isEnabled()).isFalse(); assertThat(member.getTokenVersion()).isEqualTo(1);
        verify(audit).log(eq("ADMIN_USER_DISABLED"),eq("User"),eq(20L),anyString(),eq(actor.getEmail()));
    }
    @Test void foreignRoleCannotBeAssigned() {
        when(users.findLockedByIdAndOrganizationIdAndDeletedFalse(20L,7L)).thenReturn(Optional.of(member));
        when(roles.findByIdAndOrganizationId(99L,7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.setRoles(20L,Set.of(99L))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        verify(users,never()).save(any());
    }
    @Test void systemRolesAndInventedPrivilegeCodesAreRejected() {
        when(roles.findByIdAndOrganizationId(1L,7L)).thenReturn(Optional.of(adminRole));
        assertThatThrownBy(() -> service.updateRole(1L,new AdminDtos.RoleRequest("ADMIN",Set.of()))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        assertThatThrownBy(() -> service.createRole(new AdminDtos.RoleRequest("staff",Set.of("ROLE_ADMIN")))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        assertThatThrownBy(() -> service.createRole(new AdminDtos.RoleRequest("user",Set.of()))).isInstanceOf(ResponseStatusException.class);
        verify(roles,never()).save(any());
    }
    @Test void supportedCustomRoleIsTenantBoundAndAudited() {
        Permission view = Permission.builder().id(3L).name("USER_VIEW_ALL").organization(org).build();
        when(roles.findByNameAndOrganizationId("REVIEWER",7L)).thenReturn(Optional.empty());
        when(permissions.findByNameAndOrganizationId("USER_VIEW_ALL",7L)).thenReturn(Optional.of(view));
        when(roles.save(any())).thenAnswer(call -> { Role value = call.getArgument(0); value.setId(5L); return value; });
        var result = service.createRole(new AdminDtos.RoleRequest("Reviewer",Set.of("USER_VIEW_ALL")));
        assertThat(result.name()).isEqualTo("REVIEWER"); assertThat(result.system()).isFalse();
        assertThat(result.permissions()).containsExactly("USER_VIEW_ALL");
        verify(audit).log(eq("ADMIN_ROLE_CREATED"),eq("Role"),eq(5L),anyString(),eq(actor.getEmail()));
    }
    @Test void taskDetailRejectsForeignRunWithoutLoadingEvents() {
        when(runs.findByIdAndSessionOrganizationId("foreign",7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.run("foreign")).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        verifyNoInteractions(events);
    }
    @Test void taskFiltersAndUsageQueriesCannotEscapeTenant() {
        when(runs.searchForAdmin(eq(7L),eq(AgentRunStatus.FAILED),eq(AgentTaskType.GENERATE_STORYBOARD),any())).thenReturn(Page.empty());
        assertThat(service.runs(0,20,"failed","generate_storyboard").items()).isEmpty();
        when(runs.findAllBySessionOrganizationId(7L)).thenReturn(List.of());
        assertThat(service.usage().estimatedCostUsd()).isNull();
        verifyNoInteractions(events);
    }
    private User user(Long id, Set<Role> roleSet) { return User.builder().id(id).email("user"+id+"@example.test").fullName("Member "+id).organization(org).roles(roleSet).enabled(true).emailVerified(true).build(); }
}
