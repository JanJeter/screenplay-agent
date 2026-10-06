package com.urke.saasbackendstarter.screenplay.repository;
import com.urke.saasbackendstarter.screenplay.domain.StoryboardShotProposal;
import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface StoryboardShotProposalRepository extends JpaRepository<StoryboardShotProposal,String> { List<StoryboardShotProposal> findAllByStoryboardIdOrderByCreatedAtDesc(String storyboardId); Optional<StoryboardShotProposal> findByIdAndStoryboardId(String id,String storyboardId); }
