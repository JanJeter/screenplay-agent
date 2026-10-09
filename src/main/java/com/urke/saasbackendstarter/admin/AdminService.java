package com.urke.saasbackendstarter.admin;

import com.urke.saasbackendstarter.audit.AuditLogService;
import com.urke.saasbackendstarter.domain.*;
import com.urke.saasbackendstarter.repository.*;
import com.urke.saasbackendstarter.screenplay.domain.*;
import com.urke.saasbackendstarter.screenplay.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminService {
    public static final Set<String> SUPPORTED_PERMISSIONS = Set.of("USER_VIEW_ALL", "USER_UPDATE_SELF", "USER_DELETE");
    private static final Set<String> SYSTEM_ROLES = Set.of("ADMIN", "USER");
    private final AdminAccessGuard access;
    private final UserRepository users;
    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final AgentRunRepository runs;
    private final AgentEventRepository events;
    private final AdminUsageCalculator usage;
    private final AuditLogService audit;

    public AdminDtos.PageResult<AdminDtos.UserView> users(int page, int size, String query) {
        User actor = access.admin();
        String search = query == null ? "" : query.trim();
        if (search.length() > 100) throw bad("搜索内容过长");
        Page<User> result = users.searchInOrganization(actor.getOrganization().getId(), search, pageable(page, size, "id"));
        return page(result.map(user -> userView(user, actor)));
    }
    @Transactional
    public AdminDtos.UserView setEnabled(Long userId, boolean enabled) {
        User actor = access.lockedAdmin();
        User target = targetUser(actor, userId);
        access.protectAdministrator(actor, target, enabled, target.getRoles());
        if (target.isEnabled() != enabled) {
            target.setEnabled(enabled);
            target.setTokenVersion(target.getTokenVersion() + 1);
            users.save(target);
            audit.log(enabled ? "ADMIN_USER_ENABLED" : "ADMIN_USER_DISABLED", "User", target.getId(), "更新工作区成员登录状态", actor.getEmail());
        }
        return userView(target, actor);
    }
    @Transactional
    public AdminDtos.UserView setRoles(Long userId, Set<Long> roleIds) {
        User actor = access.lockedAdmin();
        User target = targetUser(actor, userId);
        if (roleIds == null || roleIds.isEmpty() || roleIds.size() > 20) throw bad("至少选择一个角色");
        Set<Role> selected = roleIds.stream().map(id -> roles.findByIdAndOrganizationId(id, actor.getOrganization().getId())
                .orElseThrow(AdminAccessGuard::missing)).collect(Collectors.toSet());
        access.protectAdministrator(actor, target, target.isEnabled(), selected);
        target.setRoles(selected);
        users.save(target);
        audit.log("ADMIN_USER_ROLES_CHANGED", "User", target.getId(), "更新工作区成员角色", actor.getEmail());
        return userView(target, actor);
    }
    public List<AdminDtos.RoleView> roles() {
        User actor = access.admin();
        return roles.findAllByOrganizationId(actor.getOrganization().getId()).stream().sorted(Comparator.comparing(Role::getId)).map(this::roleView).toList();
    }
    public List<AdminDtos.PermissionView> permissions() {
        access.admin();
        return List.of(new AdminDtos.PermissionView("USER_VIEW_ALL", "查看工作区成员", "允许使用现有成员查询与导出功能；不授予后台管理员身份"),
                new AdminDtos.PermissionView("USER_UPDATE_SELF", "修改个人资料", "仅能修改自己的姓名与密码"),
                new AdminDtos.PermissionView("USER_DELETE", "删除自己的账号", "仅能删除自己；管理员仍受自删除与最后管理员保护"));
    }
    @Transactional
    public AdminDtos.RoleView createRole(AdminDtos.RoleRequest request) {
        User actor = access.lockedAdmin();
        Role role = new Role(); role.setOrganization(actor.getOrganization());
        updateRoleFields(role, request, actor);
        roles.save(role);
        audit.log("ADMIN_ROLE_CREATED", "Role", role.getId(), "创建工作区自定义角色", actor.getEmail());
        return roleView(role);
    }
    @Transactional
    public AdminDtos.RoleView updateRole(Long roleId, AdminDtos.RoleRequest request) {
        User actor = access.lockedAdmin();
        Role role = roles.findByIdAndOrganizationId(roleId, actor.getOrganization().getId()).orElseThrow(AdminAccessGuard::missing);
        if (SYSTEM_ROLES.contains(role.getName())) throw new ResponseStatusException(HttpStatus.CONFLICT, "系统角色不可修改");
        updateRoleFields(role, request, actor);
        roles.save(role);
        audit.log("ADMIN_ROLE_UPDATED", "Role", role.getId(), "更新工作区自定义角色与权限", actor.getEmail());
        return roleView(role);
    }
    public AdminDtos.PageResult<AdminDtos.RunView> runs(int page, int size, String status, String taskType) {
        User actor = access.admin();
        AgentRunStatus state = null; AgentTaskType type = null;
        try {
            if (status != null && !status.isBlank()) state = AgentRunStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
            if (taskType != null && !taskType.isBlank()) type = AgentTaskType.valueOf(taskType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) { throw bad("不支持的任务状态或类型"); }
        Page<AgentRun> result = runs.searchForAdmin(actor.getOrganization().getId(), state, type, pageable(page, size, "createdAt"));
        Map<String, AdminDtos.Usage> records = usageFor(actor, result.getContent());
        return page(result.map(run -> runView(run, records.get(run.getId()))));
    }
    public AdminDtos.RunView run(String id) {
        User actor = access.admin();
        AgentRun run = runs.findByIdAndSessionOrganizationId(id, actor.getOrganization().getId()).orElseThrow(AdminAccessGuard::missing);
        return runView(run, usageFor(actor, List.of(run)).get(id));
    }
    public AdminDtos.UsageSummary usage() {
        User actor = access.admin();
        return usage.summarize(usageFor(actor, runs.findAllBySessionOrganizationId(actor.getOrganization().getId())).values());
    }
    private Map<String, AdminDtos.Usage> usageFor(User actor, List<AgentRun> selected) {
        Map<String, AdminDtos.Usage> result = new LinkedHashMap<>();
        for (int start = 0; start < selected.size(); start += 500) {
            List<String> ids = selected.subList(start, Math.min(start + 500, selected.size())).stream().map(AgentRun::getId).toList();
            Map<String, List<AgentEvent>> grouped = events.findAdminUsageEvents(actor.getOrganization().getId(), ids, AdminUsageCalculator.EVENT_TYPES)
                    .stream().collect(Collectors.groupingBy(event -> event.getRun().getId()));
            for (String id : ids) result.put(id, usage.calculate(grouped.getOrDefault(id, List.of())));
        }
        return result;
    }
    private void updateRoleFields(Role role, AdminDtos.RoleRequest request, User actor) {
        String name = request.name() == null ? "" : request.name().trim().toUpperCase(Locale.ROOT);
        if (!name.matches("[\\p{L}\\p{N}_-]{2,32}") || SYSTEM_ROLES.contains(name) || name.startsWith("ROLE_")) throw bad("角色名称需为2至32位文字、数字、下划线或横线，且不能使用系统角色名");
        if (request.permissions() == null || !SUPPORTED_PERMISSIONS.containsAll(request.permissions())) throw bad("包含不受支持的权限");
        roles.findByNameAndOrganizationId(name, actor.getOrganization().getId()).filter(existing -> !Objects.equals(existing.getId(), role.getId()))
                .ifPresent(existing -> { throw new ResponseStatusException(HttpStatus.CONFLICT, "角色名称已存在"); });
        Set<Permission> selected = request.permissions().stream().map(code -> permissions.findByNameAndOrganizationId(code, actor.getOrganization().getId())
                .orElseGet(() -> permissions.save(Permission.builder().name(code).organization(actor.getOrganization()).build()))).collect(Collectors.toSet());
        role.setName(name); role.setPermissions(selected);
    }
    private User targetUser(User actor, Long id) { return access.lockedTarget(actor, id); }
    private AdminDtos.UserView userView(User user, User actor) {
        return new AdminDtos.UserView(user.getId(), user.getEmail(), user.getFullName(), user.isEnabled(), user.isEmailVerified(),
                user.getRoles().stream().filter(role -> Objects.equals(role.getOrganization().getId(), actor.getOrganization().getId()))
                        .sorted(Comparator.comparing(Role::getId)).map(role -> new AdminDtos.RoleReference(role.getId(), role.getName())).toList(), Objects.equals(user.getId(), actor.getId()));
    }
    private AdminDtos.RoleView roleView(Role role) {
        return new AdminDtos.RoleView(role.getId(), role.getName(), SYSTEM_ROLES.contains(role.getName()), role.getPermissions().stream()
                .filter(permission -> Objects.equals(permission.getOrganization().getId(), role.getOrganization().getId())).map(Permission::getName).sorted().toList());
    }
    private AdminDtos.RunView runView(AgentRun run, AdminDtos.Usage meter) {
        AgentSession session = run.getSession();
        return new AdminDtos.RunView(run.getId(), run.getStatus().name().toLowerCase(Locale.ROOT), session.getTaskType().name().toLowerCase(Locale.ROOT),
                session.getUser().getId(), session.getUser().getFullName(), session.getUser().getEmail(), session.getProject().getId(), session.getProject().getName(),
                session.getScript().getId(), run.getCreatedAt(), run.getStartedAt(), run.getEndedAt(), run.getErrorCode(), run.getResultType(), run.getResultId(), run.getResultStoryboardId(), meter);
    }
    private Pageable pageable(int page, int size, String sort) {
        if (page < 0 || size < 1 || size > 100) throw bad("分页参数无效，size需在1至100之间");
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, sort));
    }
    private <T> AdminDtos.PageResult<T> page(Page<T> page) { return new AdminDtos.PageResult<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages()); }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
