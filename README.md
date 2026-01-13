
# ForgeStream Engine 🚀

**ForgeStream Engine** is a high-performance manufacturing event processing system built with Spring Boot. It is engineered to ingest large batches of machine telemetry data, perform real-time deduplication, and provide industrial-grade analytics with sub-second latency.

## 📌 Overview

This project implements a **backend event processing system** for a factory environment where machines continuously emit telemetry events.
The system ingests large batches of events, performs **strict validation**, **deduplication**, and **conflict-safe updates**, and exposes **analytics APIs** for querying machine and factory-level statistics.

The solution is designed to be:

* **Correct under concurrency**
* **Fast enough to process 1000 events < 1 second**
* **Fully testable and deterministic**
* **Easy to reason about and extend in an interview**

---

## 🧠 What Problem This Solves

Each machine in a factory:

* Produces items
* Sometimes produces defective items
* Sends events whenever something happens

The backend must:

* Accept **large batches** of events
* Handle **duplicate and out-of-order events**
* Provide **accurate statistics over time windows**
* Remain **thread-safe** under concurrent ingestion

---

## 🏗️ 1. Architecture & Flow

The system follows a clean, decoupled 3-tier architecture. Below is the data flow when a batch of events hits the API:

┌─────────────────────────────────────────────────────────────┐
│ 1. PRESENTATION LAYER (EventController)                     │
│ ├─ @RestController                                          │
│ ├─ POST /events/batch → List<EventRequest>                  │
│ ├─ GET /stats?machineId=M1&start=...&end=...                │
│ └─ GET /stats/top-defect-lines?from=...&to=..&limit=        │
└─────────────────────────────────────────────────────────────┘
           ↓ Delegates to Service Layer
┌─────────────────────────────────────────────────────────────┐
│ 2. BUSINESS LOGIC LAYER (EventService)                      │
│ ├─ @Service + @Transactional(isolation=SERIALIZABLE)        │
│ ├─ validate() → durationMs (0-21.6Mms), eventTime (<+15min) │
│ ├─ Objects.hash(payload fields) → payloadHash (O(1))        │
│ ├─ processBatch(){}  → CREATE/DEDUPED/UPDATED/REJECTED      │
│ └─ Stats calculation → eventsCount, defectsCount, rate,     |
│        avgDefectRate, status, totalDefects, eventCount,     |
|        defectsPercent                                       |
└─────────────────────────────────────────────────────────────┘
           ↓ JPA Repository Calls
┌─────────────────────────────────────────────────────────────────────────┐
│ 3. DATA ACCESS LAYER (EventRepository + H2)                             │
│ ├─ @Repository interface                                                │
│ ├─ findByFactoryIdAndEventTimeGreaterThanEqualAndEventTimeLessThan();   |   
│ ├─ findByMachineIdAndEventTimeGreaterThanEqualAndEventTimeLessThan();   │
│ └─ Composite indexes: idx_machine_time, idx_factory_time                │
└─────────────────────────────────────────────────────────────────────────┘


### Data Flow for Batch Ingestion

1. **Ingest**
   `POST /events/batch` receives a JSON array of events

2. **Validate**

   * durationMs ∈ [0, 6 hours]
   * eventTime ≤ now + 15 minutes
   * receivedTime from client is ignored (server-controlled)

3. **Hash Payload**

   * Generate a `payloadHash` from the event’s logical content
   * Used for O(1) deduplication checks

4. **Resolve by eventId**

   * New `eventId` → **Create**
   * Same `eventId` + same hash → **Deduplicate**
   * Same `eventId` + different hash:

     * If incoming `receivedTime` is newer → **Update**
     * Else → **Reject as stale**

5. **Persist**

   * Saved using JPA with DB constraints for safety

---

## 📂 Project Structure

```
src/main/java/com/factory/eventsystem
├── controller
│   └── EventController.java        # REST endpoints
├── service
│   └── EventService.java           # Core engine logic
├── repository
│   └── EventRepository.java        # JPA data access
├── model
│   └── MachineEvent.java           # DB entity
├── dto
│   ├── EventRequest.java
│   ├── BatchResponse.java
│   ├── StatsResponse.java
│   └── TopLineResponse.java
└── config
    └── TimeConfig.java             # Central Clock bean

src/test/java
└── EventServiceTest.java            # Mandatory test suite + benchmark
```

---

## 🗄️ Data Model

### `machine_events` Table

| Column       | Type    | Purpose                      |
| ------------ | ------- | ---------------------------- |
| eventId (PK) | String  | Unique event identity        |
| eventTime    | Instant | Used for query windows       |
| receivedTime | Instant | Used for conflict resolution |
| machineId    | String  | Machine identifier           |
| lineId       | String  | Production line              |
| factoryId    | String  | Factory identifier           |
| durationMs   | Long    | Event duration               |
| defectCount  | Integer | `-1` means unknown           |
| payloadHash  | Integer | Fast dedupe comparison       |

### Indexes

* `(machineId, eventTime)` → fast machine stats
* `(factoryId, eventTime)` → fast factory stats

---

## 🔁 Deduplication & Update Logic (Core Design)

### Why Hashing?

Instead of comparing every field every time:

* The **entire payload is reduced to one integer**
* Enables **single-CPU-instruction comparison**

### Rules Implemented

| Case                                                      | Action      |
| --------------------------------------------------------- | ----------- |
| New eventId                                               | Accept      |
| Same eventId + same payloadHash                           | Deduplicate |
| Same eventId + different payloadHash + newer receivedTime | Update      |
| Same eventId + different payloadHash + older receivedTime | Reject      |

### Winning Record

The event with the **latest server-observed `receivedTime`** always wins.

---

## 🔒 Thread Safety & Concurrency

This system is safe under **20+ concurrent writers**.

### Techniques Used

1. **Serializable Transactions**

```java
@Transactional(isolation = Isolation.SERIALIZABLE)
```

2. **Database as the Source of Truth**

* `eventId` is a **primary key**
* Concurrent inserts naturally collide

3. **Conflict Recovery**

```java
repository.save(...)
repository.flush()   // force constraint detection
```

If a collision happens:

* The losing thread re-reads the DB
* Re-applies dedupe/update rules safely

✅ Verified by a **20-thread concurrent ingestion test**

---

## ⚡ Performance Strategy (1000 events < 1 sec)

* Payload hashing avoids unnecessary DB writes
* Query derivation pushes filtering into the DB
* Validation short-circuits bad records early
* Indexed time-series queries
* No recalculation of historical aggregates

### Benchmark Result

Measured in `EventServiceTest#runBenchmark`

```
1000 events ingested in ~XXX ms on a standard laptop
```

(Exact number documented in `BENCHMARK.md`)

---

## 📡 API Endpoints

### 1️⃣ Batch Ingestion

`POST /events/batch`

**Input**

```json
[
  {
    "eventId": "E-1",
    "eventTime": "2026-01-12T10:00:00Z",
    "machineId": "M1",
    "lineId": "L1",
    "factoryId": "F1",
    "durationMs": 1000,
    "defectCount": 0
  }
]
```

**Response**

```json
{
  "accepted": 950,
  "deduped": 30,
  "updated": 10,
  "rejected": 10,
  "rejections": [
    { "eventId": "E-99", "reason": "INVALID_DURATION" }
  ]
}
```

---

### 2️⃣ Machine Stats

`GET /stats?machineId=M1&start=...&end=...`

**Rules**

* start → inclusive
* end → exclusive
* defectCount = `-1` ignored

**Response**

```json
{
  "eventsCount": 1200,
  "defectsCount": 6,
  "avgDefectRate": 2.1,
  "status": "Warning"
}
```

---

### 3️⃣ Top Defect Lines

`GET /stats/top-defect-lines?factoryId=F1&from=...&to=...&limit=10`

Returns top production lines sorted by total defects.

---

## 🧪 Test Coverage (Mandatory – All Implemented)

✔ Identical duplicates deduped
✔ Newer updates applied
✔ Older updates rejected
✔ Invalid duration rejected
✔ Future eventTime rejected
✔ defectCount = -1 ignored
✔ Inclusive/exclusive boundaries
✔ Concurrent ingestion safety
✔ Performance benchmark

All tests run via:

```bash
mvn test
```

---

## 🛠️ Setup & Run

### Prerequisites

* Java 17
* Maven 3.6+

### Run Locally


# Clone

```bash
git clone <repo>
cd factory-event-system
```
# Build & Run

```bash
mvn clean install
mvn spring-boot:run
```

# Test everything

```bash
mvn test
```

# Run benchmark

```bash
mvn test -Dtest=EventServiceTest#runBenchmark

```

---

## 🔮 Improvements With More Time

* Redis cache for eventId → payloadHash
* JDBC batch inserts (`saveAll`)
* Prometheus / Actuator metrics
* Pagination for large stats queries
* Partitioned tables for very large datasets



