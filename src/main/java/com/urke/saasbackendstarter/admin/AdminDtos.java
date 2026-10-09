package com.urke.saasbackendstarter.admin;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

public final class AdminDtos {
    private AdminDtos() { }
    public record PageResult<T>(List<T> items, int page, int size, long totalElements, int totalPages) { }
    public record RoleReference(Long id, String name) { }
    public record UserView(Long id, String email, String fullName, boolean enabled, boolean emailVerified,
                           List<RoleReference> roles, boolean self) { }
    public record RoleView(Long id, String name, boolean system, List<String> permissions) { }
    public record PermissionView(String code, String label, String description) { }
    public record StatusRequest(@NotNull Boolean enabled) { }
    public record RolesRequest(@NotEmpty @Size(max = 20) Set<@NotNull @Positive Long> roleIds) { }
    public record RoleRequest(@NotBlank @Size(max = 32) String name, @NotNull @Size(max = 10) Set<@NotBlank String> permissions) { }
    public record Usage(String mode, long providerRequests, Long inputTokens, Long outputTokens, Long totalTokens,
                        BigDecimal estimatedCostUsd, String costStatus) { }
    public record RunView(String id, String status, String taskType, Long userId, String userName, String userEmail,
                          Long projectId, String projectName, Long scriptId, Instant createdAt, Instant startedAt,
                          Instant endedAt, String errorCode, String resultType, String resultId,
                          String resultStoryboardId, Usage usage) { }
    public record UsageSummary(long totalRuns, long liveRuns, long mockRuns, long unknownRuns, long providerRequests,
                               Long inputTokens, Long outputTokens, Long totalTokens, BigDecimal estimatedCostUsd, String costStatus) { }
}
