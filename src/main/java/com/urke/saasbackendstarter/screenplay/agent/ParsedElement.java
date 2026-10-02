package com.urke.saasbackendstarter.screenplay.agent;

import com.urke.saasbackendstarter.screenplay.domain.ElementType;

public record ParsedElement(ElementType type, String name, String description, Double confidence) {
}
