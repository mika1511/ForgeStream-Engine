package com.factory.eventsystem.dto;

import java.util.List;

public record BatchResponse(
        int accepted,
        int deduped,
        int updated,
        int rejected,
        List<Rejection> rejections
) {
    public record Rejection(String eventId, String reason) {}
}