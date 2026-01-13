package com.factory.eventsystem.dto;

public record TopLineResponse(
        String lineId,
        long totalDefects,
        long eventCount,
        double defectsPercent
) {}