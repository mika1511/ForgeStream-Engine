package com.factory.eventsystem.controller;


import com.factory.eventsystem.dto.BatchResponse;
import com.factory.eventsystem.dto.EventRequest;
import com.factory.eventsystem.dto.StatsResponse;
import com.factory.eventsystem.dto.TopLineResponse;
import com.factory.eventsystem.service.EventService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class EventController {
    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping("/events/batch")
    public BatchResponse ingestBatch(@RequestBody List<EventRequest> events) {
        // @RequestBody turns the incoming JSON array into a Java List of our Records
        return eventService.processBatch(events);
    }

    @GetMapping("/stats")
    public StatsResponse getStats(
            @RequestParam String machineId,
            @RequestParam String start,
            @RequestParam String end) {
        return eventService.getMachineStats(machineId, start, end);
    }

    @GetMapping("/stats/top-defect-lines")
    public List<TopLineResponse> getTopLines(
            @RequestParam String factoryId,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "10") int limit) {
        return eventService.getTopDefectLines(factoryId, from, to, limit);
    }
}
