package com.urke.saasbackendstarter.screenplay.parser;

import com.urke.saasbackendstarter.screenplay.agent.ParsedElement;
import com.urke.saasbackendstarter.screenplay.agent.ParsedScene;
import com.urke.saasbackendstarter.screenplay.agent.ScriptAnalysisResult;
import com.urke.saasbackendstarter.screenplay.domain.ElementType;
import com.urke.saasbackendstarter.screenplay.domain.InteriorExterior;
import com.urke.saasbackendstarter.screenplay.domain.TimeOfDay;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RuleBasedScriptParser {
    public static final int MAX_SCRIPT_LENGTH = 500_000;
    private static final int MAX_HEADING_LENGTH = 255;
    private static final int MAX_NAME_LENGTH = 120;
    private static final Pattern ENGLISH_HEADING = Pattern.compile(
            "^(INT\\./EXT\\.|INT/EXT\\.|INT/EXT|INT\\.|EXT\\.)\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHINESE_HEADING = Pattern.compile("^(内景|外景)\\s+(.+)$");
    private static final Pattern NUMBERED_HEADING = Pattern.compile("^第\\s*(\\d+)\\s*场\\s+(.+)$");
    private static final Pattern ENGLISH_CUE = Pattern.compile(
            "^([A-Z][A-Z0-9 .'-]{0,79}?)(?:\\s*\\((?:V\\.O\\.|O\\.S\\.|O\\.C\\.|CONT'D|CONTINUED)\\))?[:：]?$");
    private static final Pattern CHINESE_CUE = Pattern.compile(
            "^([\\p{IsHan}·]{2,12})(?:\\s*[(（][^()（）]{1,24}[)）])?[:：]?$");

    private final ParserRules rules;

    public RuleBasedScriptParser() {
        this(ParserRules.defaults());
    }

    public RuleBasedScriptParser(ParserRules rules) {
        this.rules = rules;
    }

    public ScriptAnalysisResult parse(String scriptText) {
        if (scriptText == null || scriptText.isBlank()) {
            throw new IllegalArgumentException("Script text must not be blank");
        }
        if (scriptText.length() > MAX_SCRIPT_LENGTH) {
            throw new IllegalArgumentException("Script text must not exceed 500000 characters");
        }
        String normalized = scriptText.replace("\r\n", "\n").replace('\r', '\n');
        List<SceneStart> starts = new ArrayList<>();
        int offset = 0;
        for (String line : normalized.split("\n", -1)) {
            Heading heading = readHeading(line.strip());
            if (heading != null) {
                starts.add(new SceneStart(offset, line, heading));
            }
            offset += line.length() + 1;
        }
        if (starts.isEmpty()) {
            return new ScriptAnalysisResult(List.of(new ParsedScene("1", "UNSEGMENTED SCRIPT",
                    InteriorExterior.UNKNOWN, null, TimeOfDay.UNKNOWN, normalized, 1,
                    extractElements(normalized, null, TimeOfDay.UNKNOWN))));
        }

        List<ParsedScene> scenes = new ArrayList<>(starts.size());
        for (int i = 0; i < starts.size(); i++) {
            SceneStart start = starts.get(i);
            // Keep any title/preamble with the first scene so no source text is lost.
            int from = i == 0 ? 0 : start.offset();
            int to = i + 1 < starts.size() ? starts.get(i + 1).offset() : normalized.length();
            String rawText = normalized.substring(from, to);
            Heading heading = start.heading();
            String location = prefix(heading.location(), MAX_NAME_LENGTH);
            scenes.add(new ParsedScene(heading.sceneNo() == null ? Integer.toString(i + 1) : heading.sceneNo(),
                    prefix(start.originalLine(), MAX_HEADING_LENGTH), heading.interiorExterior(), location,
                    heading.timeOfDay(), rawText, i + 1, extractElements(rawText, location, heading.timeOfDay())));
        }
        return new ScriptAnalysisResult(scenes);
    }

    private Heading readHeading(String line) {
        Matcher english = ENGLISH_HEADING.matcher(line);
        if (english.matches()) {
            String marker = english.group(1).toUpperCase(Locale.ROOT);
            InteriorExterior interior = marker.contains("/") ? InteriorExterior.INT_EXT
                    : marker.startsWith("INT") ? InteriorExterior.INT : InteriorExterior.EXT;
            return heading(null, english.group(2), interior);
        }
        Matcher chinese = CHINESE_HEADING.matcher(line);
        if (chinese.matches()) {
            return heading(null, chinese.group(2), "内景".equals(chinese.group(1))
                    ? InteriorExterior.INT : InteriorExterior.EXT);
        }
        Matcher numbered = NUMBERED_HEADING.matcher(line);
        if (numbered.matches()) {
            String remainder = numbered.group(2).strip();
            String last = lastToken(remainder);
            InteriorExterior interior = switch (last) {
                case "内", "内景" -> InteriorExterior.INT;
                case "外", "外景" -> InteriorExterior.EXT;
                case "内外", "内外景" -> InteriorExterior.INT_EXT;
                default -> InteriorExterior.UNKNOWN;
            };
            if (interior != InteriorExterior.UNKNOWN) {
                remainder = remainder.substring(0, remainder.length() - last.length()).stripTrailing();
            }
            return heading(numbered.group(1), remainder, interior);
        }
        return null;
    }

    private Heading heading(String number, String remainder, InteriorExterior interior) {
        String location = remainder.strip();
        String last = lastToken(location);
        TimeOfDay time = switch (last.toUpperCase(Locale.ROOT)) {
            case "DAY", "日", "白天", "白昼" -> TimeOfDay.DAY;
            case "NIGHT", "夜", "晚上", "夜晚" -> TimeOfDay.NIGHT;
            default -> TimeOfDay.UNKNOWN;
        };
        if (time != TimeOfDay.UNKNOWN) {
            location = location.substring(0, location.length() - last.length()).stripTrailing();
            if (!location.isEmpty() && isDash(location.charAt(location.length() - 1))) {
                location = location.substring(0, location.length() - 1).stripTrailing();
            }
        }
        return new Heading(number, interior, location.isEmpty() ? null : location, time);
    }

    private static String lastToken(String text) {
        int at = text.length() - 1;
        while (at >= 0 && !Character.isWhitespace(text.charAt(at)) && !isDash(text.charAt(at))) {
            at--;
        }
        return text.substring(at + 1);
    }

    private static boolean isDash(char character) {
        return character == '-' || character == '–' || character == '—';
    }

    private List<ParsedElement> extractElements(String text, String location, TimeOfDay time) {
        Map<ElementKey, ParsedElement> elements = new LinkedHashMap<>();
        if (location != null) {
            add(elements, ElementType.LOCATION, location, "Scene heading location");
        }
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length - 1; i++) {
            String cue = lines[i].strip();
            // Bound regex input and avoid treating headings/transitions as character names.
            if (cue.isEmpty() || cue.length() > 120 || readHeading(cue) != null) {
                continue;
            }
            Matcher english = ENGLISH_CUE.matcher(cue);
            Matcher chinese = CHINESE_CUE.matcher(cue);
            String name = english.matches() ? english.group(1).strip()
                    : chinese.matches() ? chinese.group(1) : null;
            if (name == null || rules.excludedCharacterCues().contains(name)) {
                continue;
            }
            // Dialogue must follow immediately (an optional parenthetical is permitted).
            String dialogue = lines[i + 1].strip();
            if (isParenthetical(dialogue) && i + 2 < lines.length) {
                dialogue = lines[i + 2].strip();
            }
            if (!dialogue.isEmpty() && readHeading(dialogue) == null
                    && !rules.excludedCharacterCues().contains(dialogue)
                    && !ENGLISH_CUE.matcher(dialogue).matches()) {
                add(elements, ElementType.CHARACTER, name, "Dialogue cue");
                // A short Chinese reply may itself resemble a name; do not scan it again as a cue.
                i += isParenthetical(lines[i + 1].strip()) ? 2 : 1;
            }
        }
        rules.keywords().forEach((type, words) -> words.forEach(word -> {
            if (text.contains(word)) {
                add(elements, type, word, "Keyword: " + word);
            }
        }));
        if (time == TimeOfDay.NIGHT) {
            add(elements, ElementType.RISK, "夜戏", "Night scene heading");
        }
        return List.copyOf(elements.values());
    }

    private static boolean isParenthetical(String text) {
        return text.length() > 1 && ((text.startsWith("(") && text.endsWith(")"))
                || (text.startsWith("（") && text.endsWith("）")));
    }

    private static void add(Map<ElementKey, ParsedElement> elements, ElementType type, String name,
                            String description) {
        String boundedName = prefix(name, MAX_NAME_LENGTH);
        elements.putIfAbsent(new ElementKey(type, boundedName), new ParsedElement(type, boundedName, description, null));
    }

    private static String prefix(String value, int length) {
        if (value == null || value.length() <= length) {
            return value;
        }
        // Do not split a supplementary Unicode character in half at the DB boundary.
        int end = Character.isHighSurrogate(value.charAt(length - 1)) ? length - 1 : length;
        return value.substring(0, end);
    }

    private record Heading(String sceneNo, InteriorExterior interiorExterior, String location, TimeOfDay timeOfDay) {}
    private record SceneStart(int offset, String originalLine, Heading heading) {}
    private record ElementKey(ElementType type, String name) {}
}
