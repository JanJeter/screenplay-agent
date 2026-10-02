package com.urke.saasbackendstarter.screenplay.agent;

import com.urke.saasbackendstarter.screenplay.domain.InteriorExterior;
import com.urke.saasbackendstarter.screenplay.domain.TimeOfDay;

import java.util.List;

public record ParsedScene(String sceneNo, String heading, InteriorExterior interiorExterior,
                          String location, TimeOfDay timeOfDay, String rawText,
                          int sortOrder, List<ParsedElement> elements) {
    public ParsedScene {
        elements = List.copyOf(elements);
    }
}
