package com.urke.saasbackendstarter.screenplay.export;

import com.urke.saasbackendstarter.screenplay.dto.*;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class MarkdownExporter {
    public String export(String projectName, ScriptResponse script,
                         List<SceneResponse> scenes, List<ElementResponse> elements) {
        StringBuilder out = new StringBuilder("# ").append(escape(projectName)).append("\n\n")
                .append("剧本版本：").append(escape(script.versionName())).append("\n\n")
                .append("分析状态：").append(script.status()).append("\n\n");
        if (scenes.isEmpty()) {
            return out.append("暂无解析结果，请先分析剧本。\n").toString();
        }
        Map<Long, List<ElementResponse>> byScene = elements.stream()
                .collect(Collectors.groupingBy(ElementResponse::sceneId));
        for (SceneResponse scene : scenes) {
            out.append("## ").append(escape(scene.sceneNo())).append(". ")
                    .append(escape(scene.heading())).append("\n\n")
                    .append("- 内外景：").append(scene.interiorExterior()).append("\n")
                    .append("- 地点：").append(escape(scene.location())).append("\n")
                    .append("- 时间：").append(scene.timeOfDay()).append("\n\n")
                    .append("### 制片元素\n\n");
            for (ElementResponse element : byScene.getOrDefault(scene.id(), List.of())) {
                out.append("- ").append(element.type()).append("：")
                        .append(escape(element.name())).append("\n");
            }
            out.append("\n### 场景原文\n\n");
            // Indented code preserves source text without allowing embedded Markdown/HTML to become markup.
            for (String line : scene.rawText().split("\n", -1)) {
                out.append("    ").append(line).append("\n");
            }
            out.append("\n");
        }
        return out.toString();
    }

    private static String escape(String text) {
        if (text == null) return "—";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\\", "\\\\").replace("*", "\\*").replace("_", "\\_")
                .replace("[", "\\[").replace("]", "\\]").replace("#", "\\#")
                .replace("`", "\\`").replace("\r", " ").replace("\n", " ");
    }
}

