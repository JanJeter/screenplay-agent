package com.urke.saasbackendstarter.service.impl;

import com.urke.saasbackendstarter.domain.Permission;
import com.urke.saasbackendstarter.repository.PermissionRepository;
import com.urke.saasbackendstarter.service.PermissionService;
import com.urke.saasbackendstarter.admin.AdminAccessGuard;
import com.urke.saasbackendstarter.admin.AdminService;
import com.urke.saasbackendstarter.audit.AuditLogService;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Service implementation for managing permissions.
 */
@Service
@RequiredArgsConstructor
@org.springframework.transaction.annotation.Transactional(readOnly = true)
public class PermissionServiceImpl implements PermissionService {

    private final PermissionRepository permissionRepository;
    private final AdminAccessGuard access;
    private final AuditLogService audit;

    @Override
    public List<Permission> findAllByOrganizationId(Long organizationId) {
        access.sameOrganization(access.admin(), organizationId);
        return permissionRepository.findAllByOrganizationId(organizationId);
    }

    @Override
    public Optional<Permission> findByNameAndOrganizationId(String name, Long organizationId) {
        access.sameOrganization(access.admin(), organizationId);
        return permissionRepository.findByNameAndOrganizationId(name, organizationId);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public Permission save(Permission permission) {
        var actor = access.lockedAdmin();
        if (permission.getOrganization() == null) throw AdminAccessGuard.missing();
        access.sameOrganization(actor, permission.getOrganization().getId());
        if (permission.getId() != null || !AdminService.SUPPORTED_PERMISSIONS.contains(permission.getName())) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "只允许创建固定受支持的工作区权限");
        }
        Permission saved = permissionRepository.save(permission);
        audit.log("ADMIN_PERMISSION_CREATED", "Permission", saved.getId(), "创建受支持的工作区权限", actor.getEmail());
        return saved;
    }
}
