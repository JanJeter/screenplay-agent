package com.urke.saasbackendstarter.controller;

import com.urke.saasbackendstarter.domain.Organization;
import com.urke.saasbackendstarter.dto.organization.OrganizationCreateRequest;
import com.urke.saasbackendstarter.dto.organization.OrganizationSummary;
import com.urke.saasbackendstarter.mapper.OrganizationMapper;
import com.urke.saasbackendstarter.service.OrganizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;

/**
 * REST controller for organization management: create, list, and soft-delete organizations.
 */
@Tag(
    name = "Organizations",
    description = "Endpoints for organization management: create, list, and soft delete organizations."
)
@RestController
@RequestMapping("/api/v1/organizations")
@RequiredArgsConstructor
public class OrganizationController {

    private final OrganizationService organizationService;
    private final OrganizationMapper organizationMapper;
    private final MessageSource messageSource;
    private final com.urke.saasbackendstarter.security.CurrentUserProvider currentUser;

    /**
     * Create and register a new organization entity.
     */
    @Operation(
        summary = "Organization creation is unavailable in the fixed workbench",
        description = "This workbench uses the configured team workspace; authenticated requests receive 403.",
        security = @SecurityRequirement(name = "bearerAuth"),
        responses = {
            @ApiResponse(responseCode = "200", description = "Organization created successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid input"),
            @ApiResponse(responseCode = "401", description = "Unauthorized")
        }
    )
    @PostMapping
    public ResponseEntity<OrganizationSummary> createOrg(
            @Valid @RequestBody OrganizationCreateRequest request,
            Locale locale) {
        throw fixedWorkspace();
    }

    /**
     * Retrieve a paged list of all organizations, optionally filtered by name.
     */
    @Operation(
        summary = "Get paged organizations",
        description = "Return only the authenticated user's workspace, optionally filtered by name.",
        security = @SecurityRequirement(name = "bearerAuth"),
        parameters = {
            @Parameter(name = "page", description = "Page number (zero-based)", example = "0"),
            @Parameter(name = "size", description = "Page size", example = "10"),
            @Parameter(name = "name", description = "Optional name filter (case-insensitive)")
        },
        responses = {
            @ApiResponse(responseCode = "200", description = "List of organizations returned"),
            @ApiResponse(responseCode = "401", description = "Unauthorized")
        }
    )
    @GetMapping
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<Page<OrganizationSummary>> getAllPaged(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String name) {

        if (page < 0 || size < 1 || size > 100) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "分页参数无效");
        Pageable pageable = PageRequest.of(page, size);
        Organization own = currentUser.getCurrentOrganization();
        boolean matches = own != null && !own.isDeleted() && (name == null || name.isBlank()
                || own.getName().toLowerCase(Locale.ROOT).contains(name.trim().toLowerCase(Locale.ROOT)));
        java.util.List<OrganizationSummary> content = matches && page == 0 ? java.util.List.of(organizationMapper.toSummary(own)) : java.util.List.of();
        return ResponseEntity.ok(new org.springframework.data.domain.PageImpl<>(content, pageable, matches ? 1 : 0));
    }

    /**
     * Soft delete an organization by its ID. Only accessible by admins.
     */
    @Operation(
        summary = "Organization deletion is unavailable in the fixed workbench",
        description = "This workbench uses the configured team workspace; authenticated requests receive 403.",
        security = @SecurityRequirement(name = "bearerAuth"),
        parameters = {
            @Parameter(name = "id", description = "ID of the organization to delete", required = true)
        },
        responses = {
            @ApiResponse(responseCode = "204", description = "Organization deleted successfully (no content)"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden – insufficient privileges"),
            @ApiResponse(responseCode = "404", description = "Organization not found")
        }
    )
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteOrganization(@PathVariable Long id, Locale locale) {
        throw fixedWorkspace();
    }
    private org.springframework.web.server.ResponseStatusException fixedWorkspace() {
        return new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,
                "当前工作台使用固定团队工作区，不支持此操作");
    }
}
