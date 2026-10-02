package com.urke.saasbackendstarter.screenplay.export;

import com.urke.saasbackendstarter.screenplay.domain.ElementType;
import com.urke.saasbackendstarter.screenplay.domain.InteriorExterior;
import com.urke.saasbackendstarter.screenplay.domain.ScriptStatus;
import com.urke.saasbackendstarter.screenplay.domain.TimeOfDay;
import com.urke.saasbackendstarter.screenplay.dto.ElementResponse;
import com.urke.saasbackendstarter.screenplay.dto.SceneResponse;
import com.urke.saasbackendstarter.screenplay.dto.ScriptResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownExporterTest {
    private final MarkdownExporter exporter = new MarkdownExporter();

    @Test
    void rawHtmlLinksAndFenceDelimitersRemainLiteralSourceText() {
        String source = "<script>alert('source')</script>\n"
                + "```\n\n# Forged heading\n"
                + "[open](javascript:alert(1))\n"
                + "~~~html\n<img src=x onerror=alert(1)>\n~~~\n";

        String markdown = exporter.export("雨夜试镜", script(ScriptStatus.ANALYZED, "Draft 1"),
                List.of(scene(10L, "1", "INT. 旧摄影棚 - NIGHT", "旧摄影棚", source)), List.of());

        // CommonMark indented code cannot be terminated by the screenplay's own fence delimiters.
        assertThat(markdown).contains("""
                ### 场景原文

                    <script>alert('source')</script>
                    ```
                \s\s\s\s
                    # Forged heading
                    [open](javascript:alert(1))
                    ~~~html
                    <img src=x onerror=alert(1)>
                    ~~~
                """);
        assertThat(markdown).doesNotContain("\n<script", "\n<img", "\n# Forged heading", "\n```", "\n~~~");
    }

    @Test
    void escapesUntrustedMetadataAndKeepsItOnItsOriginalLine() {
        String markdown = exporter.export("<img src=x> & **Title**\n# injected",
                script(ScriptStatus.ANALYZED, "[draft](javascript:alert(1))\r\n# injected"),
                List.of(scene(10L, "1\n# injected", "<b>Room</b> _night_", "`location`", "Plain source")),
                List.of(new ElementResponse(20L, 10L, ElementType.PROP,
                        "[click](https://example.invalid)\n<script>alert(1)</script>", null, 1.0)));

        assertThat(markdown)
                .startsWith("# &lt;img src=x&gt; &amp; \\*\\*Title\\*\\* \\# injected\n")
                .contains("剧本版本：\\[draft\\](javascript:alert(1))  \\# injected")
                .contains("## 1 \\# injected. &lt;b&gt;Room&lt;/b&gt; \\_night\\_")
                .contains("- 地点：\\`location\\`")
                .contains("- PROP：\\[click\\](https://example.invalid) &lt;script&gt;alert(1)&lt;/script&gt;")
                .doesNotContain("<img", "<b>", "<script>", "\n# injected", "[draft]", "[click]");
    }

    @Test
    void failedReanalysisStillExportsPreviouslyStoredScenesAndElements() {
        String markdown = exporter.export("雨夜试镜", script(ScriptStatus.ANALYZE_FAILED, "Draft 1"),
                List.of(scene(10L, "1", "INT. 旧摄影棚 - NIGHT", "旧摄影棚", "旧结果里的收音机与雨声。")),
                List.of(new ElementResponse(20L, 10L, ElementType.PROP, "收音机", null, 1.0),
                        new ElementResponse(21L, 10L, ElementType.SOUND, "雨声", null, 1.0)));

        assertThat(markdown)
                .contains("分析状态：ANALYZE_FAILED")
                .contains("## 1. INT. 旧摄影棚 - NIGHT")
                .contains("- PROP：收音机", "- SOUND：雨声")
                .contains("    旧结果里的收音机与雨声。")
                .doesNotContain("暂无解析结果");
    }

    @Test
    void groupsElementsUnderTheirOwnSceneAndPreservesSceneOrder() {
        String markdown = exporter.export("雨夜试镜", script(ScriptStatus.ANALYZED, "Draft 1"),
                List.of(scene(10L, "1", "INT. 旧摄影棚 - NIGHT", "旧摄影棚", "第一场"),
                        scene(11L, "2", "EXT. 摄影棚后巷 - DAY", "摄影棚后巷", "第二场")),
                List.of(new ElementResponse(21L, 11L, ElementType.PROP, "手电筒", null, 1.0),
                        new ElementResponse(20L, 10L, ElementType.PROP, "收音机", null, 1.0)));

        int secondScene = markdown.indexOf("## 2.");
        assertThat(secondScene).isGreaterThan(markdown.indexOf("## 1."));
        assertThat(markdown.substring(0, secondScene)).contains("- PROP：收音机").doesNotContain("手电筒");
        assertThat(markdown.substring(secondScene)).contains("- PROP：手电筒").doesNotContain("收音机");
    }

    @Test
    void explainsMissingResultsWithoutDiscardingScriptStatus() {
        String markdown = exporter.export("雨夜试镜", script(ScriptStatus.ANALYZE_FAILED, "Draft 1"),
                List.of(), List.of());

        assertThat(markdown).contains("分析状态：ANALYZE_FAILED", "暂无解析结果，请先分析剧本。");
        assertThat(markdown).doesNotContain("### 场景原文");
    }

    private ScriptResponse script(ScriptStatus status, String versionName) {
        return new ScriptResponse(1L, 2L, versionName, "sample-screenplay.txt", "Original script", status,
                Instant.parse("2026-10-03T00:00:00Z"));
    }

    private SceneResponse scene(Long id, String number, String heading, String location, String rawText) {
        return new SceneResponse(id, 1L, number, heading, InteriorExterior.INT, location, TimeOfDay.NIGHT,
                null, rawText, null, 1);
    }
}
