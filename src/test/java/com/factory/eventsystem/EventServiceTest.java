package com.factory.eventsystem;
import com.factory.eventsystem.dto.*;
import com.factory.eventsystem.model.MachineEvent;
import com.factory.eventsystem.repository.EventRepository;
import com.factory.eventsystem.service.EventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class EventServiceTest {

    @MockitoBean
    private Clock clock;

    @Autowired
    private EventService eventService;

    @Autowired
    private EventRepository repository;

    @BeforeEach
    void setup() {
        repository.deleteAll();
        Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        Mockito.when(clock.instant()).thenReturn(Instant.parse("2026-01-13T11:00:00Z"));
    }

    // ========== TEST 1: Identical duplicate eventId ==========
    @Test
    void test1_IdenticalDuplicateEventId_ShouldBeDuplicated() {

        EventRequest req = new EventRequest(
                "E1",
                "2026-01-12T10:00:00Z",
                "2026-01-12T10:05:00Z",
                "M1", "L1", "F1",
                1000L, 5
        );

        // First submission
        BatchResponse res1 = eventService.processBatch(List.of(req));
        assertEquals(1, res1.accepted(), "First submission should be accepted");

        // Second submission - identical
        BatchResponse res2 = eventService.processBatch(List.of(req));
        assertEquals(1, res2.deduped(), "Identical duplicate should be deduped");
        assertEquals(0, res2.updated(), "Should not be updated");
        assertEquals(0, res2.rejected(), "Should not be rejected");

        // Verify only 1 record exists
        assertEquals(1, repository.count(), "Only 1 record should exist in DB");
    }


    // ========== TEST 2: Different payload + newer receivedTime → update happens ==========
    @Test
    void test2_DifferentPayloadWithNewerReceivedTime_ShouldUpdate() {
        String id = "E2";

        // 1. Set Clock to 10:05 and send first version
        Mockito.when(clock.instant()).thenReturn(Instant.parse("2026-01-12T10:05:00Z"));
        EventRequest first = new EventRequest(id, "2026-01-12T10:00:00Z", "ignored", "M1", "L1", "F1", 1000L, 5);
        eventService.processBatch(List.of(first));

        // 2. Set Clock to 10:10 (NEWER) and send updated payload
        Mockito.when(clock.instant()).thenReturn(Instant.parse("2026-01-12T10:10:00Z"));
        EventRequest newer = new EventRequest(id, "2026-01-12T10:00:00Z", "ignored", "M1", "L1", "F1", 2000L, 10);

        BatchResponse res2 = eventService.processBatch(List.of(newer));

        assertEquals(1, res2.updated(), "Should update because service clock is now newer");
        MachineEvent record = repository.findById(id).orElseThrow();
        assertEquals(2000L, record.getDurationMs());
    }

    // ========== TEST 3: Different payload + older receivedTime → ignored ==========
    @Test
    void test3_DifferentPayloadWithOlderReceivedTime_ShouldBeIgnored() {
        String id = "E3";

        // 1. Set Clock to 10:10 and save first version
        Mockito.when(clock.instant()).thenReturn(Instant.parse("2026-01-12T10:10:00Z"));
        EventRequest first = new EventRequest(id, "2026-01-12T10:00:00Z", "ignored", "M1", "L1", "F1", 1000L, 5);
        eventService.processBatch(List.of(first));

        // 2. Set Clock to 10:05
        Mockito.when(clock.instant()).thenReturn(Instant.parse("2026-01-12T10:05:00Z"));
        EventRequest older = new EventRequest(id, "2026-01-12T10:00:00Z", "ignored", "M1", "L1", "F1", 2000L, 99);

        BatchResponse res2 = eventService.processBatch(List.of(older));

        assertEquals(1, res2.rejected(), "Should reject because service clock (10:05) < DB record (10:10)");
        assertEquals("OLDER_DATA_IGNORED", res2.rejections().get(0).reason());
    }

    // ========== TEST 4: Invalid duration rejected ==========
    @Test
    void test4_InvalidDuration_ShouldBeRejected() {
        EventRequest tooLong = new EventRequest(
                "E-LONG",
                "2026-01-12T10:00:00Z",
                "2026-01-12T10:05:00Z",
                "M1", "L1", "F1",
                30000000L,  // 30 million ms = > 6 hours
                0
        );

        BatchResponse res1 = eventService.processBatch(List.of(tooLong));
        assertEquals(1, res1.rejected(), "Too long duration should be rejected");
        assertEquals("INVALID_DURATION", res1.rejections().get(0).reason());
        assertEquals(0, repository.count(), "No record should be saved");

        // Negative duration
        EventRequest negative = new EventRequest(
                "E-NEG",
                "2026-01-12T10:00:00Z",
                "2026-01-12T10:05:00Z",
                "M1", "L1", "F1",
                -100L,  // Negative duration
                0
        );

        BatchResponse res2 = eventService.processBatch(List.of(negative));
        assertEquals(1, res2.rejected(), "Negative duration should be rejected");
        assertEquals("INVALID_DURATION", res2.rejections().get(0).reason());
        assertEquals(0, repository.count(), "Still no records should be saved");
    }

    // ========== TEST 5: Future eventTime rejected ==========
    @Test
    void test5_FutureEventTime_ShouldBeRejected() {

        EventRequest futureEvent = new EventRequest(
                "E-FUTURE",
                "2026-01-14T10:00:00Z",  // Way in the future ( next day)
                "2026-01-12T10:05:00Z",
                "M1", "L1", "F1",
                1000L, 0
        );

        BatchResponse res = eventService.processBatch(List.of(futureEvent));
        assertEquals(1, res.rejected(), "Future event should be rejected");
        assertEquals("FUTURE_EVENT_TIME", res.rejections().get(0).reason());
        assertEquals(0, repository.count(), "No record should be saved");
    }

    // ========== TEST 6: DefectCount = -1 ignored in defect totals ==========
    @Test
    void test6_DefectCountNegativeOne_ShouldBeIgnoredInStats() {
        // Event with defectCount = 10
        EventRequest withDefects = new EventRequest(
                "D1",
                "2026-01-12T10:00:00Z",
                "2026-01-12T10:05:00Z",
                "M1", "L1", "F1",
                1000L,
                10
        );

        // Event with defectCount = -1 (unknown)
        EventRequest unknownDefects = new EventRequest(
                "D2",
                "2026-01-12T10:10:00Z",
                "2026-01-12T10:15:00Z",
                "M1", "L1", "F1",
                1000L,
                -1
        );

        // Event with defectCount = 5
        EventRequest moreDefects = new EventRequest(
                "D3",
                "2026-01-12T10:20:00Z",
                "2026-01-12T10:25:00Z",
                "M1", "L1", "F1",
                1000L,
                5
        );

        eventService.processBatch(List.of(withDefects, unknownDefects, moreDefects));

        // Query stats
        StatsResponse stats = eventService.getMachineStats(
                "M1",
                "2026-01-12T00:00:00Z",
                "2026-01-12T23:59:59Z"
        );

        assertEquals(3, stats.eventsCount(), "All 3 events should be counted");
        assertEquals(15, stats.defectsCount(), "Only 10 + 5 = 15 defects (the -1 should be ignored)");
    }

    // ========== TEST 7: start/end boundary) ==========
    @Test
    void test7_QueryBoundaries_ShouldBeInclusiveStartExclusiveEnd() {
        // Event exactly at start boundary - should be INCLUDED
        EventRequest atStart = new EventRequest(
                "B1",
                "2026-01-12T10:00:00Z",
                "2026-01-12T10:01:00Z",
                "M1", "L1", "F1",
                1000L, 2
        );

        // Event in the middle - should be INCLUDED
        EventRequest inMiddle = new EventRequest(
                "B2",
                "2026-01-12T10:30:00Z",
                "2026-01-12T10:31:00Z",
                "M1", "L1", "F1",
                1000L, 3
        );

        // Event exactly at end boundary- should be EXCLUDED
        EventRequest atEnd = new EventRequest(
                "B3",
                "2026-01-12T11:00:00Z",
                "2026-01-12T11:01:00Z",
                "M1", "L1", "F1",
                1000L, 4
        );

        eventService.processBatch(List.of(atStart, inMiddle, atEnd));

        // Query from 10:00 to 11:00 (start inclusive, end exclusive)
        StatsResponse stats = eventService.getMachineStats(
                "M1",
                "2026-01-12T10:00:00Z",
                "2026-01-12T11:00:00Z"
        );

        assertEquals(2, stats.eventsCount(), "Should count 2 events (at start and in middle, NOT at end)");

        assertEquals(5, stats.defectsCount(), "Should count 2 + 3 = 5 defects (not the 4 from the excluded event)");
    }

    // ========== EST 8:Thread-safety ==========
    @Test
    void test8_ConcurrentIngestion_ShouldBeThreadSafe() throws InterruptedException {
        int threadCount = 20;
        java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger successCount =
                new java.util.concurrent.atomic.AtomicInteger(0);

        // All threads will try to insert the SAME eventId simultaneously
        for (int i = 0; i < threadCount; i++) {
            executor.execute(() -> {
                try {
                    latch.await(); // Wait for signal to start

                    EventRequest req = new EventRequest(
                            "THREAD-TEST",
                            "2026-01-12T10:00:00Z",
                            "2026-01-12T10:05:00Z",
                            "M1", "L1", "F1",
                            1000L, 5
                    );

                    BatchResponse res = eventService.processBatch(List.of(req));
                    if (res.accepted() > 0) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // Ignore exceptions (some threads may fail due to conflicts)
                }
            });
        }

        latch.countDown(); // Signal all threads to start simultaneously
        executor.shutdown();
        executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);

        // Verify thread-safety: Only 1 record should exist
        assertEquals(1, repository.count(),
                "Only 1 record should exist despite " + threadCount + " concurrent attempts");

        // Only 1 thread should have successfully accepted
        assertEquals(1, successCount.get(),
                "Only 1 thread should have accepted the event");
    }


    @Test
    void runBenchmark() {
        int batchSize = 1000;
        List<EventRequest> largeBatch = new java.util.ArrayList<>();

        for (int i = 0; i < batchSize; i++) {
            largeBatch.add(new EventRequest(
                    "BENCH-" + i,
                    "2026-01-12T10:00:00Z",
                    "2026-01-12T10:05:00Z",
                    "M1", "L1", "F1",
                    1000L, 0
            ));
        }

        long startTime = System.currentTimeMillis();
        eventService.processBatch(largeBatch);
        long endTime = System.currentTimeMillis();

        System.out.println("BENCHMARK RESULT: " + (endTime - startTime) + "ms");

    }
}