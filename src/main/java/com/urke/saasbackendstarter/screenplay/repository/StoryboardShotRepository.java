package com.urke.saasbackendstarter.screenplay.repository;
import com.urke.saasbackendstarter.screenplay.domain.StoryboardShot;
import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface StoryboardShotRepository extends JpaRepository<StoryboardShot,String> { List<StoryboardShot> findAllByStoryboardIdOrderByOrderIndexAsc(String storyboardId); Optional<StoryboardShot> findByIdAndStoryboardId(String id,String storyboardId); }
