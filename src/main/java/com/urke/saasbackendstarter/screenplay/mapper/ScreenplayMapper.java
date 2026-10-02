package com.urke.saasbackendstarter.screenplay.mapper;

import com.urke.saasbackendstarter.screenplay.domain.*;
import com.urke.saasbackendstarter.screenplay.dto.*;

public final class ScreenplayMapper {
    private ScreenplayMapper() {}

    public static ProjectResponse project(ScreenplayProject p) {
        return new ProjectResponse(p.getId(), p.getOrganization().getId(), p.getName(),
                p.getDescription(), p.getGenre(), p.getStatus(), p.getCreatedBy().getId(),
                p.getCreatedAt(), p.getUpdatedAt());
    }

    public static ScriptSummary summary(ScriptVersion s) {
        return new ScriptSummary(s.getId(), s.getProject().getId(), s.getVersionName(),
                s.getOriginalFilename(), s.getStatus(), s.getCreatedAt());
    }

    public static ScriptResponse script(ScriptVersion s) {
        return new ScriptResponse(s.getId(), s.getProject().getId(), s.getVersionName(),
                s.getOriginalFilename(), s.getRawText(), s.getStatus(), s.getCreatedAt());
    }

    public static SceneResponse scene(ScriptScene s) {
        return new SceneResponse(s.getId(), s.getScriptVersion().getId(), s.getSceneNo(),
                s.getHeading(), s.getInteriorExterior(), s.getLocation(), s.getTimeOfDay(),
                s.getSummary(), s.getRawText(), s.getPageEstimate(), s.getSortOrder());
    }

    public static ElementResponse element(SceneElement e) {
        return new ElementResponse(e.getId(), e.getScene().getId(), e.getType(), e.getName(),
                e.getDescription(), e.getConfidence());
    }
}

