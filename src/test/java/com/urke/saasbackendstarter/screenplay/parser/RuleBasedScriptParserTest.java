package com.urke.saasbackendstarter.screenplay.parser;

import com.urke.saasbackendstarter.screenplay.agent.ParsedElement;
import com.urke.saasbackendstarter.screenplay.agent.ParsedScene;
import com.urke.saasbackendstarter.screenplay.domain.ElementType;
import com.urke.saasbackendstarter.screenplay.domain.InteriorExterior;
import com.urke.saasbackendstarter.screenplay.domain.TimeOfDay;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeout;

class RuleBasedScriptParserTest {
    private final RuleBasedScriptParser parser = new RuleBasedScriptParser();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "INT. OLD STUDIO - NIGHT | INT | OLD STUDIO | NIGHT | 1",
            "EXT. ALLEY - DAY | EXT | ALLEY | DAY | 1",
            "INT./EXT. CAR - NIGHT | INT_EXT | CAR | NIGHT | 1",
            "内景 旧摄影棚 夜 | INT | 旧摄影棚 | NIGHT | 1",
            "外景 摄影棚后巷 夜 | EXT | 摄影棚后巷 | NIGHT | 1",
            "第1场 旧摄影棚 夜 内 | INT | 旧摄影棚 | NIGHT | 1",
            "第 1 场 旧摄影棚 夜 内景 | INT | 旧摄影棚 | NIGHT | 1",
            "第 12 场 房间 日 外景 | EXT | 房间 | DAY | 12"
    })
    void acceptsDocumentedHeadingFormats(String heading, InteriorExterior interior, String location,
                                        TimeOfDay time, String number) {
        ParsedScene scene = parser.parse(heading + "\n动作描述。\n").scenes().getFirst();

        assertThat(scene.heading()).isEqualTo(heading);
        assertThat(scene.interiorExterior()).isEqualTo(interior);
        assertThat(scene.location()).isEqualTo(location);
        assertThat(scene.timeOfDay()).isEqualTo(time);
        assertThat(scene.sceneNo()).isEqualTo(number);
        assertThat(scene.sortOrder()).isEqualTo(1);
    }

    @Test
    void preservesEverySourceCharacterExceptNormalizedLineEndings() {
        String text = "TITLE: 雨夜试镜\r\n\r\n  INT. 旧摄影棚 - NIGHT  \r\n场内动作。\r\n\r\n"
                + "EXT. 摄影棚后巷 - NIGHT\r场外动作。  \r\n";
        List<ParsedScene> scenes = parser.parse(text).scenes();

        assertThat(scenes).hasSize(2);
        assertThat(scenes).extracting(ParsedScene::sceneNo).containsExactly("1", "2");
        assertThat(scenes).extracting(ParsedScene::sortOrder).containsExactly(1, 2);
        assertThat(scenes.getFirst().heading()).isEqualTo("  INT. 旧摄影棚 - NIGHT  ");
        assertThat(scenes.getFirst().rawText()).endsWith("场内动作。\n\n");
        assertThat(scenes.stream().map(ParsedScene::rawText).reduce("", String::concat))
                .isEqualTo(text.replace("\r\n", "\n").replace('\r', '\n'));
    }

    @Test
    void fallsBackToOneSceneWithoutLosingWhitespace() {
        String text = "\n  没有分场标题。\r\n\r另一行。  \n";
        ParsedScene scene = parser.parse(text).scenes().getFirst();

        assertThat(scene.sceneNo()).isEqualTo("1");
        assertThat(scene.heading()).isEqualTo("UNSEGMENTED SCRIPT");
        assertThat(scene.location()).isNull();
        assertThat(scene.interiorExterior()).isEqualTo(InteriorExterior.UNKNOWN);
        assertThat(scene.timeOfDay()).isEqualTo(TimeOfDay.UNKNOWN);
        assertThat(scene.rawText()).isEqualTo("\n  没有分场标题。\n\n另一行。  \n");
    }

    @Test
    void leavesUnspecifiedTimeUnknown() {
        ParsedScene scene = parser.parse("INT. OLD STUDIO\nA lamp swings.").scenes().getFirst();
        assertThat(scene.location()).isEqualTo("OLD STUDIO");
        assertThat(scene.timeOfDay()).isEqualTo(TimeOfDay.UNKNOWN);
        assertThat(scene.elements()).noneMatch(element -> element.type() == ElementType.RISK);
    }

    @Test
    void extractsSampleDialogueLocationsAndKeywordsPerSceneWithoutDuplicates() {
        String text = """
                TITLE: 雨夜试镜

                INT. 旧摄影棚 - NIGHT

                雨声砸在铁皮屋顶上。
                                    周远
                          你迟到了二十分钟。

                                    林夏
                          地铁停了。我跑过来的。

                一台收音机响起。林夏拿起收音机和手电筒。

                                    周远
                          那就开始吧。

                CUT TO BLACK.

                EXT. 摄影棚后巷 - NIGHT

                一辆面包车停在雨里。车内的人拿起手机。
                """;
        List<ParsedScene> scenes = parser.parse(text).scenes();

        assertThat(scenes).hasSize(2);
        assertThat(scenes).extracting(ParsedScene::location).containsExactly("旧摄影棚", "摄影棚后巷");
        assertThat(names(scenes.getFirst(), ElementType.CHARACTER)).containsExactly("周远", "林夏");
        assertThat(names(scenes.getFirst(), ElementType.PROP)).containsExactly("收音机", "手电筒");
        assertThat(names(scenes.getFirst(), ElementType.SOUND)).containsExactly("雨声");
        assertThat(names(scenes.getFirst(), ElementType.RISK)).contains("雨", "雨声", "夜戏");
        assertThat(names(scenes.get(1), ElementType.PROP)).containsExactly("车", "手机");
        assertThat(names(scenes.get(1), ElementType.RISK)).contains("夜戏");
        assertThat(names(scenes.get(1), ElementType.CHARACTER)).isEmpty();
    }

    @Test
    void detectsEnglishAndChineseCuesWithParentheticalsAndShortReplies() {
        String text = """
                INT. ROOM - DAY

                JOHN (V.O.)
                We should go now.

                林夏（低声）
                我来了

                周远
                （点头）
                你好

                CUT TO BLACK.
                The screen goes dark.
                """;

        assertThat(names(parser.parse(text).scenes().getFirst(), ElementType.CHARACTER))
                .containsExactly("JOHN", "林夏", "周远");
    }

    @Test
    void truncatesPersistedFieldsButRetainsLongHeadingAndBodyInSource() {
        String location = "棚".repeat(300);
        String text = "INT. " + location + " - NIGHT\n" + "正文。".repeat(100);
        ParsedScene scene = parser.parse(text).scenes().getFirst();

        assertThat(scene.heading()).hasSize(255).isEqualTo(text.substring(0, 255));
        assertThat(scene.location()).hasSize(120).isEqualTo(location.substring(0, 120));
        assertThat(names(scene, ElementType.LOCATION)).containsExactly(location.substring(0, 120));
        assertThat(scene.rawText()).isEqualTo(text);
        assertThat(scene.timeOfDay()).isEqualTo(TimeOfDay.NIGHT);
    }

    @Test
    void keywordsCanBeChangedThroughCentralConfiguration() {
        RuleBasedScriptParser configured = new RuleBasedScriptParser(
                new ParserRules(Map.of(ElementType.PROP, List.of("指南针")), Set.of()));
        ParsedScene scene = configured.parse("内景 房间 日\n桌上有指南针和手机。").scenes().getFirst();

        assertThat(names(scene, ElementType.PROP)).containsExactly("指南针");
    }

    @Test
    void keepsSupplementaryUnicodeValidAtDatabaseLengthBoundaries() {
        String location = "A".repeat(119) + "🎥" + "B".repeat(128) + "🎥" + "C".repeat(20);
        String text = "INT. " + location + " - NIGHT\n灯光亮起。";
        ParsedScene scene = parser.parse(text).scenes().getFirst();

        assertThat(scene.location()).isEqualTo("A".repeat(119));
        assertThat(scene.heading()).isEqualTo("INT. " + "A".repeat(119) + "🎥" + "B".repeat(128));
        assertThat(scene.rawText()).isEqualTo(text);
    }

    @Test
    void handlesMaximumSizeWithLongLinesWithoutRegexRecursion() {
        String heading = "INT. ";
        String text = heading + "棚".repeat(500_000 - heading.length() - " - NIGHT".length()) + " - NIGHT";

        assertTimeout(Duration.ofSeconds(5), () -> {
            ParsedScene scene = parser.parse(text).scenes().getFirst();
            assertThat(scene.rawText()).hasSize(500_000);
            assertThat(scene.location()).hasSize(120);
            assertThat(scene.timeOfDay()).isEqualTo(TimeOfDay.NIGHT);
        });
    }

    @Test
    void rejectsInvalidInputBeforeParsing() {
        for (String text : new String[]{null, "", " \r\n\t", "x".repeat(500_001)}) {
            assertThatThrownBy(() -> parser.parse(text)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static List<String> names(ParsedScene scene, ElementType type) {
        return scene.elements().stream().filter(element -> element.type() == type).map(ParsedElement::name).toList();
    }
}
