package com.urke.saasbackendstarter.screenplay.dto;

import com.urke.saasbackendstarter.screenplay.domain.ElementType;

public record ElementResponse(Long id, Long sceneId, ElementType type, String name,
                              String description, Double confidence) {}

