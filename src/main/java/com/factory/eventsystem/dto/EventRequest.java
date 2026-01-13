package com.factory.eventsystem.dto;

public record EventRequest(
        String eventId,
        String eventTime,
        String receivedTime, //ignore this later
        String machineId,
        String lineId,
        String factoryId,
        Long durationMs,
        Integer defectCount
) {}
