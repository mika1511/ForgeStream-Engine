package com.factory.eventsystem.service;

import com.factory.eventsystem.dto.BatchResponse;
import com.factory.eventsystem.dto.EventRequest;
import com.factory.eventsystem.dto.StatsResponse;
import com.factory.eventsystem.dto.TopLineResponse;
import com.factory.eventsystem.model.MachineEvent;
import com.factory.eventsystem.model.MachineStatus;
import com.factory.eventsystem.repository.EventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class EventService {

    private final EventRepository repository;
    private final Clock clock;
    private final MachineStatusService machineStatusService;


    public EventService(EventRepository repository, Clock clock, MachineStatusService machineStatusService) {
        this.repository = repository;
        this.clock=clock;
        this.machineStatusService=machineStatusService;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public BatchResponse processBatch(List<EventRequest> requests) {
        int accepted = 0;
        int deduped = 0;
        int updated = 0;
        int rejected = 0;

        List<BatchResponse.Rejection> rejections = new ArrayList<>();

        for (EventRequest req : requests) {

            String error = validate(req);
            if (error != null) {
                rejections.add(new BatchResponse.Rejection(req.eventId(), error));
                rejected++;
                continue;
            }


            int currentHash = Objects.hash(
                    req.machineId(),
                    req.lineId(),
                    req.factoryId(),
                    req.eventTime(),
                    req.durationMs(),
                    req.defectCount()
            );


            Instant incomingReceivedTime = Instant.now(clock);


            Optional<MachineEvent> existing = repository.findById(req.eventId());

            if (existing.isPresent()) {

                MachineEvent dbRecord = existing.get();


                if (incomingReceivedTime.isBefore(dbRecord.getReceivedTime())) {
                    rejected++;
                    rejections.add(new BatchResponse.Rejection(req.eventId(), "OLDER_DATA_IGNORED"));
                }

                else if (dbRecord.getPayloadHash() == currentHash) {
                    deduped++;
                }

                else {
                    updateExistingRecord(dbRecord, req, currentHash, incomingReceivedTime);
                    repository.save(dbRecord);
                    updated++;
                }
            } else {

                try {
                    MachineEvent newRecord = createNewRecord(req, currentHash, incomingReceivedTime);
                    repository.save(newRecord);
                    repository.flush(); // Force immediate write to catch constraint violations
                    accepted++;
                } catch (Exception e) {
                    // If unique constraint is violated, it means another thread inserted it
                    // Re-check and handle as duplicate
                    Optional<MachineEvent> nowExists = repository.findById(req.eventId());
                    if (nowExists.isPresent()) {
                        MachineEvent dbRecord = nowExists.get();
                        if (dbRecord.getPayloadHash() == currentHash) {
                            deduped++;
                        } else if (incomingReceivedTime.isAfter(dbRecord.getReceivedTime())) {
                            updateExistingRecord(dbRecord, req, currentHash, incomingReceivedTime);
                            repository.save(dbRecord);
                            updated++;
                        } else {
                            rejected++;
                            rejections.add(new BatchResponse.Rejection(req.eventId(), "OLDER_DATA_IGNORED"));
                        }
                    }
                }
            }
        }

        return new BatchResponse(accepted, deduped, updated, rejected, rejections);
    }



    private String validate(EventRequest req) {
        // Duration check-> 0 to 6 hours (21,600,000 ms)
        if (req.durationMs() < 0 || req.durationMs() > 21_600_000) {
            return "INVALID_DURATION";
        }

        try {
            Instant eventTime = Instant.parse(req.eventTime());
            Instant now = clock.instant();
            Instant maxAllowedFuture = now.plus(Duration.ofMinutes(15));

            if (eventTime.isAfter(maxAllowedFuture)) {
                return "FUTURE_EVENT_TIME";
            }

        } catch (Exception e) {
            return "INVALID_DATE_FORMAT";
        }
        return null;
    }



    private MachineEvent createNewRecord(EventRequest req, int hash, Instant incomingReceivedTime) {
        MachineEvent event = new MachineEvent();
        event.setEventId(req.eventId());
        event.setEventTime(Instant.parse(req.eventTime()));
        event.setReceivedTime(incomingReceivedTime);
        event.setMachineId(req.machineId());
        event.setLineId(req.lineId());
        event.setFactoryId(req.factoryId());
        event.setDurationMs(req.durationMs());
        event.setDefectCount(req.defectCount());
        event.setPayloadHash(hash);
        return event;
    }



    private void updateExistingRecord(MachineEvent existing, EventRequest req, int hash, Instant incomingReceivedTime) {
        existing.setEventTime(Instant.parse(req.eventTime()));
        existing.setReceivedTime(incomingReceivedTime);
        existing.setMachineId(req.machineId());
        existing.setLineId(req.lineId());
        existing.setFactoryId(req.factoryId());
        existing.setDurationMs(req.durationMs());
        existing.setDefectCount(req.defectCount());
        existing.setPayloadHash(hash);
    }



    public StatsResponse getMachineStats(String machineId, String startStr, String endStr) {
        // 1. Convert the Strings from the URL into Instant objects
        Instant start = Instant.parse(startStr);
        Instant end = Instant.parse(endStr);

        // 2. Call the exact method name you added to the Repository
        // This sends the filtering logic to the database (very efficient!)
        List<MachineEvent> events = repository.findByMachineIdAndEventTimeGreaterThanEqualAndEventTimeLessThan(
                machineId,
                start,
                end
        );

        // 3. Calculate the stats from the filtered list
        long eventsCount = events.size();

        long defectsCount = events.stream()
                .mapToInt(MachineEvent::getDefectCount)
                .filter(count -> count != -1) // Rule: Ignore -1
                .sum();

        // 4. Calculate Defect Rate per Hour
        // Formula: Total Defects / (Time Window in Hours)
        long seconds = java.time.Duration.between(start, end).getSeconds();
        double windowHours = seconds / 3600.0;
        double avgDefectRate = (windowHours > 0) ? (defectsCount / windowHours) : 0.0;

        // 5. Determine Status based on the 2.0 threshold

        MachineStatus status= machineStatusService.evaluate(avgDefectRate);

        return new StatsResponse(
                machineId,
                startStr,
                endStr,
                eventsCount,
                defectsCount,
                Math.round(avgDefectRate * 10.0) / 10.0, // Rounds to 1 decimal place
                status.name()
        );
    }



    public List<TopLineResponse> getTopDefectLines(String factoryId, String fromStr, String toStr, int limit) {
        Instant from = Instant.parse(fromStr);
        Instant to = Instant.parse(toStr);

        // 1. Fetch events for this factory in this time range
        List<MachineEvent> events = repository.findByFactoryIdAndEventTimeGreaterThanEqualAndEventTimeLessThan(
                factoryId, from, to);




        // 2. Group by lineId and calculate stats
        return events.stream()
                .collect(java.util.stream.Collectors.groupingBy(MachineEvent::getLineId))
                .entrySet().stream()
                .map(entry -> {
                    String lineId = entry.getKey();
                    List<MachineEvent> lineEvents = entry.getValue();

                    long totalDefects = lineEvents.stream()
                            .mapToInt(MachineEvent::getDefectCount)
                            .filter(d -> d != -1)
                            .sum();

                    long eventCount = lineEvents.size();

                    // Defect Rate per 100 events
                    double percent = (eventCount > 0) ? (totalDefects * 100.0 / eventCount) : 0.0;

                    return new TopLineResponse(
                            lineId,
                            totalDefects,
                            eventCount,
                            Math.round(percent * 100.0) / 100.0
                    );
                })
                .sorted((a, b) -> Long.compare(b.totalDefects(), a.totalDefects())) // Highest defects first
                .limit(limit)
                .toList();
    }
}