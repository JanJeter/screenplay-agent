package com.urke.saasbackendstarter.screenplay.repository;
import com.urke.saasbackendstarter.screenplay.domain.StoryboardRunContext;
import org.springframework.data.jpa.repository.JpaRepository;
public interface StoryboardRunContextRepository extends JpaRepository<StoryboardRunContext,String> { boolean existsByRunIdAndArtifactIdIsNotNull(String runId); }
