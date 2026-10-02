package com.urke.saasbackendstarter.screenplay.repository;

import com.urke.saasbackendstarter.screenplay.domain.ScriptVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScriptVersionRepository extends JpaRepository<ScriptVersion, Long> {
    Optional<ScriptVersion> findByIdAndProjectOrganizationId(Long id, Long organizationId);

    List<ScriptVersion> findAllByProjectIdAndProjectOrganizationIdOrderByCreatedAtDescIdDesc(
            Long projectId, Long organizationId);
}
