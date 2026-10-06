package com.urke.saasbackendstarter.screenplay.repository;
import com.urke.saasbackendstarter.screenplay.domain.StoryboardDraft;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import java.util.*;
public interface StoryboardDraftRepository extends JpaRepository<StoryboardDraft,String> {
 Optional<StoryboardDraft> findByIdAndOrganizationId(String id, Long organizationId);
 @Lock(LockModeType.PESSIMISTIC_WRITE)
 @Query("select d from StoryboardDraft d where d.id = :id and d.organization.id = :organizationId")
 Optional<StoryboardDraft> findLockedByIdAndOrganizationId(@Param("id") String id, @Param("organizationId") Long organizationId);
 List<StoryboardDraft> findAllByProjectIdAndOrganizationIdOrderByCreatedAtDesc(Long projectId, Long organizationId);
 List<StoryboardDraft> findAllByProjectIdAndOrganizationIdAndSourceScriptIdOrderByCreatedAtDesc(Long projectId,Long organizationId,Long sourceScriptId);
}
