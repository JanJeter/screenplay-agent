package com.urke.saasbackendstarter.screenplay.repository;

import com.urke.saasbackendstarter.screenplay.domain.SceneElement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SceneElementRepository extends JpaRepository<SceneElement, Long> {
    List<SceneElement> findAllBySceneScriptVersionIdOrderBySceneSortOrderAscIdAsc(Long scriptId);

    @Modifying(flushAutomatically = true)
    @Query("""
            delete from SceneElement element
            where element.scene.id in (
                select scene.id from ScriptScene scene where scene.scriptVersion.id = :scriptId
            )
            """)
    int deleteByScriptVersionId(@Param("scriptId") Long scriptId);
}
