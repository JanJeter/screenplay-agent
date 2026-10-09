package com.urke.saasbackendstarter.service.impl;

import com.urke.saasbackendstarter.domain.User;
import com.urke.saasbackendstarter.domain.Organization;
import com.urke.saasbackendstarter.domain.Role;
import com.urke.saasbackendstarter.admin.AdminAccessGuard;
import com.urke.saasbackendstarter.dto.user.UserCreateRequest;
import com.urke.saasbackendstarter.dto.user.UserUpdateRequest;
import com.urke.saasbackendstarter.events.UserEvent;
import com.urke.saasbackendstarter.exception.UserAlreadyExistsException;
import com.urke.saasbackendstarter.exception.UserNotFoundException;
import com.urke.saasbackendstarter.repository.UserRepository;
import com.urke.saasbackendstarter.service.UserService;
import com.urke.saasbackendstarter.repository.OrganizationRepository;
import com.urke.saasbackendstarter.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final OrganizationRepository organizationRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;
    private final MessageSource messageSource;
    private final com.urke.saasbackendstarter.service.AccountLifecycleService accounts;
    private final com.urke.saasbackendstarter.repository.RefreshTokenRepository refreshTokens;
    private final AdminAccessGuard adminAccess;
    private final com.urke.saasbackendstarter.audit.AuditLogService audit;

    @Override
    @Transactional
    public User register(UserCreateRequest request) {
        return accounts.register(request);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmailAndDeletedFalse(email);
    }

    @Override
    public Optional<User> findByEmailWithOrganization(String email) {
        return userRepository.findByEmailWithOrganization(email);
    }

    @Override
    public Optional<User> findById(Long id) {
        User actor = adminAccess.actor();
        if (!Objects.equals(actor.getId(), id) && !adminAccess.isAdmin(actor) && !adminAccess.hasPermission(actor, "USER_VIEW_ALL")) AdminAccessGuard.forbidden();
        return userRepository.findByIdAndOrganizationIdAndDeletedFalse(id, actor.getOrganization().getId());
    }

    @Override
    public List<User> findAll() {
        User actor = viewActor();
        return userRepository.findAllByOrganizationAndDeletedFalse(actor.getOrganization());
    }

    @Override
    public List<User> findAllByOrganization(Organization organization) {
        User actor = viewActor();
        adminAccess.sameOrganization(actor, organization.getId());
        return userRepository.findAllByOrganizationAndDeletedFalse(actor.getOrganization());
    }

    @Override
    public Page<User> findAll(Pageable pageable) {
        return findAllByEmailFilter("", pageable);
    }

    @Override
    public Page<User> findAllByEmailFilter(String email, Pageable pageable) {
        User actor = viewActor();
        if (pageable.getPageSize() > 100) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "每页最多100条记录");
        return userRepository.searchInOrganization(actor.getOrganization().getId(), email == null ? "" : email.trim(), pageable);
    }

    @Override
    @Transactional
    public User updateUser(Long id, UserUpdateRequest request) {
        User actor = adminAccess.lockActor();
        if (!adminAccess.isAdmin(actor) && (!Objects.equals(actor.getId(), id) || !adminAccess.hasPermission(actor, "USER_UPDATE_SELF"))) AdminAccessGuard.forbidden();
        User user = adminAccess.lockedTarget(actor, id);

        user.setFullName(request.getFullName());
        if (request.getNewPassword() != null && !request.getNewPassword().isBlank()) {
            user.setPassword(passwordEncoder.encode(com.urke.saasbackendstarter.security.AccountInputs.password(request.getNewPassword())));
            user.setTokenVersion(user.getTokenVersion() + 1);
            refreshTokens.deleteByUser(user);
        }
        User updated = userRepository.save(user);
        audit.log("USER_PROFILE_UPDATED", "User", user.getId(), "更新工作区成员资料", actor.getEmail());
        eventPublisher.publishEvent(new UserEvent(this, UserEvent.Type.UPDATED, updated));
        return updated;
    }

    @Override
    @Transactional
    public void deleteUser(Long id) {
        User actor = adminAccess.lockActor();
        if (!adminAccess.isAdmin(actor) && (!Objects.equals(actor.getId(), id) || !adminAccess.hasPermission(actor, "USER_DELETE"))) AdminAccessGuard.forbidden();
        User user = adminAccess.lockedTarget(actor, id);
        adminAccess.protectAdministrator(actor, user, false, user.getRoles());
        user.setDeleted(true);
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);
        audit.log("USER_DELETED", "User", user.getId(), "删除工作区成员账号", actor.getEmail());
        eventPublisher.publishEvent(new UserEvent(this, UserEvent.Type.DELETED, user));
    }

    private User viewActor() {
        User actor = adminAccess.actor();
        if (!adminAccess.isAdmin(actor) && !adminAccess.hasPermission(actor, "USER_VIEW_ALL")) AdminAccessGuard.forbidden();
        return actor;
    }
}
