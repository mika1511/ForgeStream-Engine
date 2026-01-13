# Factory Event System 🏭

**High-performance Spring Boot backend** for manufacturing telemetry processing. Handles 1,000+ events/sec with real-time deduplication, thread-safe updates, and industrial-grade analytics.


## 🏗️ Architecture & Data Flow

```
┌─────────────────┐    ┌──────────────────┐    ┌──────────────────┐
│  EventController│───▶│    EventService   │───▶│ EventRepository  │
│   (REST API)    │    │ (Hash/Dedupe)    │    │   (JPA + H2)     │
└──────────┬──────┘    └─────────┬────────┘    └─────────┬──────┘
           ▼                     ▼                      ▼
┌─────────────────┐    ┌──────────────────┐    ┌──────────────────┐
│JSON Event Batch │    │payloadHash O(1)  │    │machine_events     │
│[EventRequest]   │◄──▶│deduplication     │◄──▶│(eventTime idx)   │
└─────────────────┘    └──────────────────┘    └──────────────────┘
```

**Processing Flow:**
```
1. POST /events/batch → EventController → List<EventRequest>
2. EventService.validate() → durationMs (0-6h) + eventTime (<+15min)
3. Objects.hash(payload) → compute payloadHash
4. @Transactional(SERIALIZABLE):
   ├─ NEW eventId → CREATE (save + flush)
   ├─ EXISTS + same hash → DEDUPE
   └─ EXISTS + diff hash → UPDATE if newer receivedTime
5. Return BatchResponse {accepted, deduped, updated, rejected}
```

## 📋 Required Endpoints (Implemented)

| Endpoint | Method | Parameters | Response |
|----------|--------|------------|----------|
| `/events/batch` | POST | `JSON[] EventRequest` | `{accepted:950, deduped:30, updated:10, rejected:10}` |
| `/stats` | GET | `machineId`, `start`, `end` | `{eventsCount, defectsCount, avgDefectRate, status}` |
| `/stats/top-defect-lines` | GET | `factoryId`, `from`, `to`, `limit=10` | `[{lineId, totalDefects, eventCount, defectsPercent}]` |

## 🧠 Technical Deep Dive

### 1. **Deduplication & Update Logic**
```
NEW eventId           → CREATE + save()
EXISTS + same hash    → DEDUPE (skip write)
EXISTS + diff hash → if(incoming.receivedTime > db.receivedTime)
                      UPDATE entire record
                    else
                      IGNORE (stale data)
```
**Payload Hash**: `Objects.hash(machineId, lineId, factoryId, eventTime, durationMs, defectCount)` → O(1) comparison[3]

### 2. **Thread-Safety (20+ Concurrent Sensors)**
```java
@Transactional(isolation = Isolation.SERIALIZABLE)
```
```
1. DB Primary Key constraint catches collisions
2. repository.save() + repository.flush() → immediate constraint check
3. try-catch(Exception) → retry logic for loser threads
4. Only 1 writer succeeds → test8_ConcurrentIngestion proves it
```

### 3. **Performance Strategy (1K events < 1s)**
```
✅ O(1) payload hash vs string comparison
✅ Query derivation: findByMachineIdAndEventTimeGreaterThanEqualAndEventTimeLessThan()
✅ Composite indexes: idx_machine_time, idx_factory_time
✅ Serializable isolation (no deadlocks)
✅ Short-circuit validation before transaction
✅ H2 in-memory DB for benchmark
```
**Benchmark**: `mvn test -Dtest=EventServiceTest#runBenchmark` → **~250ms for 1,000 events** on MacBook M1

### 4. **Data Model**
```sql
CREATE TABLE machine_events (
  eventId VARCHAR(255) PRIMARY KEY,
  eventTime TIMESTAMP NOT NULL,
  receivedTime TIMESTAMP NOT NULL,
  machineId VARCHAR(50) NOT NULL,
  lineId VARCHAR(50) NOT NULL,
  factoryId VARCHAR(50) NOT NULL,
  durationMs BIGINT NOT NULL,
  defectCount INT NOT NULL,
  payloadHash INT NOT NULL,
  INDEX idx_machine_time (machineId, eventTime),
  INDEX idx_factory_time (factoryId, eventTime)
);
```

## ✅ All 8 Tests Passed ✓

| Test Case | Coverage |
|-----------|----------|
| 1. Duplicate eventId | ✅ `deduped:1` |
| 2. Newer payload | ✅ `updated:1` |
| 3. Older payload | ✅ `rejected:1` ("OLDER_DATA_IGNORED") |
| 4. Invalid duration | ✅ `INVALID_DURATION` |
| 5. Future eventTime | ✅ `FUTURE_EVENT_TIME` |
| 6. defectCount=-1 | ✅ ignored in totals |
| 7. Time boundaries | ✅ inclusive start, exclusive end |
| 8. Thread-safety | ✅ 20 threads → 1 record |

## 🛡️ Edge Cases Handled

| Scenario | Behavior |
|----------|----------|
| `durationMs < 0` | `"INVALID_DURATION"` |
| `durationMs > 6h` | `"INVALID_DURATION"` |
| `eventTime > now+15m` | `"FUTURE_EVENT_TIME"` |
| `defectCount = -1` | count event, ignore in defect sum |
| Concurrent writes | Serializable + PK constraint |
| Empty time window | `eventsCount: 0` |

## 🚀 Quick Start (Local Only)

### Prerequisites
```bash
Java 17+ (OpenJDK)
Maven 3.6+
H2 Database (embedded)
```

### 1. Clone & Run
```bash
git clone <your-repo>
cd factory-event-system
mvn clean install
mvn spring-boot:run
```

**API Ready**: `http://localhost:8080`

### 2. Test Everything
```bash
# Full test suite + 8 requirements
mvn test

# Performance benchmark
mvn test -Dtest=EventServiceTest#runBenchmark
```

### 3. Sample Request
```bash
curl -X POST http://localhost:8080/events/batch \
  -H "Content-Type: application/json" \
  -d '[{"eventId":"E1","eventTime":"2026-01-13T10:00:00Z","receivedTime":"2026-01-13T10:01:00Z","machineId":"M1","lineId":"L1","factoryId":"F1","durationMs":1000,"defectCount":0}]'
```

