package com.factory.eventsystem.dto;

public record StatsResponse(
        String machineId,
        String start,
        String end,
        long eventsCount,
        long defectsCount,
        double avgDefectRate,
        String status
) {
}
