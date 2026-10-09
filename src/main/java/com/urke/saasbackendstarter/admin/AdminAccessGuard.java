package com.urke.saasbackendstarter.admin;

import com.urke.saasbackendstarter.domain.User;
import com.urke.saasbackendstarter.domain.Role;
import com.urke.saasbackendstarter.repository.OrganizationRepository;
import com.urke.saasbackendstarter.repository.UserRepository;
import com.urke.saasbackendstarter.security.CurrentUserProvider;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.util.Objects;
import java.util.Set;

/** Every management service checks the authenticated tenant; request IDs are never an authority. */
@Component
@RequiredArgsConstructor
public class AdminAccessGuard {
    private final CurrentUserProvider current;
    private final OrganizationRepository organizations;
    private final UserRepository users;
    private final EntityManager entityManager;

    public User actor() {
        User actor = current.getCurrentUser();
        if (actor.isDeleted() || !actor.isEnabled() || !actor.isEmailVerified()
                || actor.getOrganization() == null || actor.getOrganization().isDeleted()) forbidden();
        return actor;
    }
    public User admin() {
        User actor = actor();
        if (!isAdmin(actor)) forbidden();
        return actor;
    }
    public User lockedAdmin() {
        User actor = lockActor();
        if (!isAdmin(actor)) forbidden();
        return actor;
    }
    public User lockActor() {
        User actor = actor();
        organizations.findLockedById(actor.getOrganization().getId()).orElseThrow(() -> missing());
        // Lock order is tenant -> actor -> target. Authentication only locks
        // user rows. Refresh after locking also replaces a stale managed entity
        // loaded earlier by a compatibility controller in the same request.
        actor = lockedTarget(actor, actor.getId());
        if (actor.isDeleted() || !actor.isEnabled() || !actor.isEmailVerified()) forbidden();
        return actor;
    }
    public User lockedTarget(User actor, Long userId) {
        User target = users.findLockedByIdAndOrganizationIdAndDeletedFalse(userId, actor.getOrganization().getId())
                .orElseThrow(AdminAccessGuard::missing);
        entityManager.refresh(target);
        sameOrganization(actor, target.getOrganization().getId());
        if (target.isDeleted()) throw missing();
        return target;
    }
    public void sameOrganization(User actor, Long organizationId) {
        if (!Objects.equals(actor.getOrganization().getId(), organizationId)) throw missing();
    }
    public boolean isAdmin(User user) {
        return user.getRoles() != null && user.getRoles().stream().anyMatch(role ->
                "ADMIN".equals(role.getName()) && role.getOrganization() != null
                        && Objects.equals(role.getOrganization().getId(), user.getOrganization().getId()));
    }
    public boolean hasPermission(User user, String permission) {
        return user.getRoles() != null && user.getRoles().stream()
                .filter(role -> role.getOrganization() != null && Objects.equals(role.getOrganization().getId(), user.getOrganization().getId()))
                .filter(role -> role.getPermissions() != null).flatMap(role -> role.getPermissions().stream())
                .anyMatch(value -> permission.equals(value.getName()) && value.getOrganization() != null
                        && Objects.equals(value.getOrganization().getId(), user.getOrganization().getId()));
    }
    public void protectAdministrator(User actor, User target, boolean willBeEnabled, Set<Role> nextRoles) {
        boolean nextAdmin = nextRoles.stream().anyMatch(role -> "ADMIN".equals(role.getName()));
        if (isAdmin(target) && Objects.equals(actor.getId(), target.getId()) && (!willBeEnabled || !nextAdmin)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "不能停用自己或移除自己的管理员角色");
        }
        if (isAdmin(target) && target.isEnabled() && target.isEmailVerified() && (!willBeEnabled || !nextAdmin)
                && users.countActiveAdmins(actor.getOrganization().getId()) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "必须保留至少一名可登录的管理员");
        }
    }
    public static void forbidden() { throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅当前工作区管理员可执行此操作"); }
    public static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到当前工作区的记录"); }
}
