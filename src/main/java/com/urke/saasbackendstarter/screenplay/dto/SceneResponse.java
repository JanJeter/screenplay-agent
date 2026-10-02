package com.urke.saasbackendstarter.screenplay.dto;

import com.urke.saasbackendstarter.screenplay.domain.InteriorExterior;
import com.urke.saasbackendstarter.screenplay.domain.TimeOfDay;
import java.math.BigDecimal;

public record SceneResponse(Long id, Long scriptVersionId, String sceneNo, String heading,
                            InteriorExterior interiorExterior, String location, TimeOfDay timeOfDay,
                            String summary, String rawText, BigDecimal pageEstimate, int sortOrder) {}

