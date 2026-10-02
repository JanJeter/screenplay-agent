package com.urke.saasbackendstarter.screenplay.repository;

import com.urke.saasbackendstarter.screenplay.domain.ScreenplayProject;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScreenplayProjectRepository extends JpaRepository<ScreenplayProject, Long> {
    Optional<ScreenplayProject> findByIdAndOrganizationId(Long id, Long organizationId);

    List<ScreenplayProject> findAllByOrganizationIdOrderByCreatedAtDescIdDesc(Long organizationId);
}
