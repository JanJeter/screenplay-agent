package com.urke.saasbackendstarter.service.impl;

import com.urke.saasbackendstarter.domain.Role;
import com.urke.saasbackendstarter.exception.RoleAlreadyExistsException;
import com.urke.saasbackendstarter.repository.RoleRepository;
import com.urke.saasbackendstarter.service.RoleService;
import com.urke.saasbackendstarter.admin.AdminAccessGuard;
import com.urke.saasbackendstarter.admin.AdminService;
import com.urke.saasbackendstarter.audit.AuditLogService;
import com.urke.saasbackendstarter.domain.User;

import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.List;

/**
 * Service implementation for managing roles (per organization).
 */
@Service
@RequiredArgsConstructor
@org.springframework.transaction.annotation.Transactional(readOnly = true)
public class RoleServiceImpl implements RoleService {

    private final RoleRepository roleRepository;
    private final MessageSource messageSource;
    private final AdminAccessGuard access;
    private final AuditLogService audit;

    @Override
    public Optional<Role> findByNameAndOrganizationId(String name, Long organizationId) {
        access.sameOrganization(access.admin(), organizationId);
        return roleRepository.findByNameAndOrganizationId(name, organizationId);
    }

    @Override
    public List<Role> findAllByOrganizationId(Long organizationId) {
        access.sameOrganization(access.admin(), organizationId);
        return roleRepository.findAllByOrganizationId(organizationId);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public Role save(Role role) {
        User actor = access.lockedAdmin();
        if (role.getOrganization() == null) throw AdminAccessGuard.missing();
        access.sameOrganization(actor, role.getOrganization().getId());
        String name = role.getName() == null ? "" : role.getName().trim().toUpperCase(java.util.Locale.ROOT);
        if (role.getId() != null || !name.matches("[\\p{L}\\p{N}_-]{2,32}") || java.util.Set.of("ADMIN", "USER").contains(name) || name.startsWith("ROLE_")) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "系统角色受保护，旧接口仅可创建自定义角色");
        }
        if (role.getPermissions() != null) for (var permission : role.getPermissions()) {
            if (permission.getOrganization() == null) throw AdminAccessGuard.missing();
            access.sameOrganization(actor, permission.getOrganization().getId());
            if (!AdminService.SUPPORTED_PERMISSIONS.contains(permission.getName())) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "不受支持的权限");
        }
        role.setName(name);
        if (roleRepository.existsByNameAndOrganizationId(role.getName(), role.getOrganization().getId())) {
            throw new RoleAlreadyExistsException(
                messageSource.getMessage("role.exists", null, LocaleContextHolder.getLocale())
            );
        }
        Role saved = roleRepository.save(role);
        audit.log("ADMIN_ROLE_CREATED", "Role", saved.getId(), "通过兼容接口创建工作区角色", actor.getEmail());
        return saved;
    }
}
