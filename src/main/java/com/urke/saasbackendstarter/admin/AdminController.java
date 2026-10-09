package com.urke.saasbackendstarter.admin;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminController {
    private final AdminService service;
    @GetMapping("/users") public AdminDtos.PageResult<AdminDtos.UserView> users(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String q) { return service.users(page, size, q); }
    @PatchMapping("/users/{id}/status") public AdminDtos.UserView status(@PathVariable Long id, @Valid @RequestBody AdminDtos.StatusRequest request) { return service.setEnabled(id, request.enabled()); }
    @PutMapping("/users/{id}/roles") public AdminDtos.UserView userRoles(@PathVariable Long id, @Valid @RequestBody AdminDtos.RolesRequest request) { return service.setRoles(id, request.roleIds()); }
    @GetMapping("/roles") public List<AdminDtos.RoleView> roles() { return service.roles(); }
    @GetMapping("/permissions") public List<AdminDtos.PermissionView> permissions() { return service.permissions(); }
    @PostMapping("/roles") public AdminDtos.RoleView createRole(@Valid @RequestBody AdminDtos.RoleRequest request) { return service.createRole(request); }
    @PutMapping("/roles/{id}") public AdminDtos.RoleView updateRole(@PathVariable Long id, @Valid @RequestBody AdminDtos.RoleRequest request) { return service.updateRole(id, request); }
    @GetMapping("/runs") public AdminDtos.PageResult<AdminDtos.RunView> runs(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String status,
            @RequestParam(required = false) String taskType) { return service.runs(page, size, status, taskType); }
    @GetMapping("/runs/{id}") public AdminDtos.RunView run(@PathVariable String id) { return service.run(id); }
    @GetMapping("/usage") public AdminDtos.UsageSummary usage() { return service.usage(); }
}
