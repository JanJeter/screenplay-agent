package com.urke.saasbackendstarter.screenplay.service;

import com.urke.saasbackendstarter.screenplay.agent.*;
import com.urke.saasbackendstarter.screenplay.domain.*;
import com.urke.saasbackendstarter.screenplay.dto.AnalysisResponse;
import com.urke.saasbackendstarter.screenplay.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AnalysisTransactions {
    private final ScriptVersionRepository scripts;
    private final ScriptSceneRepository scenes;
    private final SceneElementRepository elements;
    private final AgentClient agentClient;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AnalysisResponse replace(Long scriptId, Long organizationId) {
        ScriptVersion script = findScript(scriptId, organizationId);
        ScriptAnalysisResult result = agentClient.analyzeScript(script.getRawText());
        // Bulk deletion order respects the foreign keys; the transaction rolls all changes back on failure.
        elements.deleteByScriptVersionId(scriptId);
        scenes.deleteByScriptVersionId(scriptId);
        int elementCount = 0;
        for (ParsedScene parsed : result.scenes()) {
            ScriptScene scene = new ScriptScene();
            scene.setScriptVersion(script);
            scene.setSceneNo(parsed.sceneNo());
            scene.setHeading(prefix(parsed.heading(), 255));
            scene.setInteriorExterior(parsed.interiorExterior());
            scene.setLocation(prefix(parsed.location(), 120));
            scene.setTimeOfDay(parsed.timeOfDay());
            scene.setRawText(parsed.rawText());
            scene.setSortOrder(parsed.sortOrder());
            scenes.save(scene);
            for (ParsedElement parsedElement : parsed.elements()) {
                SceneElement element = new SceneElement();
                element.setScene(scene);
                element.setType(parsedElement.type());
                element.setName(prefix(parsedElement.name(), 120));
                element.setDescription(parsedElement.description());
                element.setConfidence(parsedElement.confidence());
                elements.save(element);
                elementCount++;
            }
        }
        script.setStatus(ScriptStatus.ANALYZED);
        // Flush here so persistence errors are raised before reporting a successful analysis.
        scripts.flush();
        return new AnalysisResponse(scriptId, script.getStatus(), result.scenes().size(), elementCount);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long scriptId, Long organizationId) {
        findScript(scriptId, organizationId).setStatus(ScriptStatus.ANALYZE_FAILED);
    }

    private ScriptVersion findScript(Long id, Long organizationId) {
        return scripts.findByIdAndProjectOrganizationId(id, organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Script not found"));
    }

    private static String prefix(String text, int length) {
        if (text == null || text.length() <= length) return text;
        int end = Character.isHighSurrogate(text.charAt(length - 1)) ? length - 1 : length;
        return text.substring(0, end);
    }
}

