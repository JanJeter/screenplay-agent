package com.urke.saasbackendstarter.screenplay.repository;

import com.urke.saasbackendstarter.screenplay.domain.ScriptScene;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ScriptSceneRepository extends JpaRepository<ScriptScene, Long> {
    List<ScriptScene> findAllByScriptVersionIdOrderBySortOrderAsc(Long scriptId);

    @Modifying(flushAutomatically = true)
    @Query("delete from ScriptScene scene where scene.scriptVersion.id = :scriptId")
    int deleteByScriptVersionId(@Param("scriptId") Long scriptId);
}
