package com.urke.saasbackendstarter.screenplay.parser;

import com.urke.saasbackendstarter.screenplay.domain.ElementType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Small, centralized vocabulary for deterministic MVP extraction. */
public record ParserRules(Map<ElementType, List<String>> keywords, Set<String> excludedCharacterCues) {
    public ParserRules {
        Map<ElementType, List<String>> copy = new LinkedHashMap<>();
        keywords.forEach((type, words) -> {
            if (words.stream().anyMatch(word -> word == null || word.isBlank())) {
                throw new IllegalArgumentException("Parser keywords must not be blank");
            }
            copy.put(type, List.copyOf(words));
        });
        keywords = Collections.unmodifiableMap(copy);
        excludedCharacterCues = Set.copyOf(excludedCharacterCues);
    }

    public static ParserRules defaults() {
        Map<ElementType, List<String>> keywords = new LinkedHashMap<>();
        keywords.put(ElementType.PROP, List.of("收音机", "手电筒", "车", "手机"));
        keywords.put(ElementType.SOUND, List.of("雨声", "雷声", "电话铃", "脚步声"));
        keywords.put(ElementType.RISK, List.of("夜戏", "雨", "雨声", "爆炸", "打斗", "火"));
        return new ParserRules(keywords, Set.of(
                "CUT TO", "CUT TO BLACK", "CUT TO BLACK.", "FADE IN", "FADE OUT", "FADE TO BLACK",
                "DISSOLVE TO", "THE END", "END", "DAY", "NIGHT", "CONTINUED",
                "旁白说明", "画外音", "转场", "淡入", "淡出", "切黑", "结束", "全剧终"));
    }
}
