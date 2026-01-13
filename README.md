
# ForgeStream Engine 🚀

**ForgeStream Engine** is a high-performance, concurrency-safe manufacturing event processing system built with **Spring Boot**.

---

## 📌 Overview

ForgeStream Engine implements a **backend event ingestion and analytics system** for a factory environment where machines continuously emit telemetry events.

The system ingests **large batches of events**, performs **strict validation**, **real-time deduplication**, and **conflict-safe updates**, and exposes **analytics APIs** for querying machine-level and factory-level statistics.

### The system is designed to be:

* **Correct under concurrency**
* **Fast enough to process 1000 events < 1 second**
* **Fully testable and deterministic**
* **Easy to reason about, explain, and extend**



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

## 🎯 Design Philosophy

This system was built with four core principles:

1. **Correctness over cleverness**
   Especially under concurrent writes and retries.

2. **Deterministic behavior**
   Same input → same output, regardless of timing or thread scheduling.

3. **Interview-grade clarity**
   Every design choice has a clear “why”.

4. **Performance within realistic constraints**
   Fast enough without premature or unnecessary optimization.

---

## 🧠 Problem This System Solves

Each machine in a factory:

* Produces items
* Sometimes produces defective items
* Emits telemetry events continuously

The backend must:

* Accept **large batches** of events
* Handle **duplicate and out-of-order events**
* Resolve **conflicting updates deterministically**
* Provide **accurate statistics over time windows**
* Remain **thread-safe under concurrent ingestion**

---

## 🏗️ Architecture & Flow

The system follows a clean, decoupled 3-tier architecture. Below is the data flow when a batch of events hits the API:

```bash
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
│ ├─ validate() → durationMs (0 - 6 hr), eventTime(< +15 min) │
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
```

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



## 🚀 API Documentation

### 1. Ingest Event Batch

**Endpoint:** `POST /events/batch`

**Description:** Processes a batch of telemetry events. Handles validation, deduplication, and conflict resolution.

**Sample Request Body:**

```json
[
  {
    "eventId": "E-001",
    "eventTime": "2026-01-12T10:00:00Z",
    "receivedTime": "2026-01-12T10:05:00Z",
    "machineId": "M-101",
    "lineId": "L-A",
    "factoryId": "F-HQ",
    "durationMs": 1200,
    "defectCount": 2
  },
  {
    "eventId": "E-002",
    "eventTime": "2026-01-12T10:10:00Z",
    "receivedTime": "2026-01-12T10:15:00Z",
    "machineId": "M-101",
    "lineId": "L-A",
    "factoryId": "F-HQ",
    "durationMs": 30000000, 
    "defectCount": -1
  }
]

```

**Sample Response:**

```json
{
  "accepted": 1,
  "deduped": 0,
  "updated": 0,
  "rejected": 1,
  "rejections": [
    {
      "eventId": "E-002",
      "reason": "INVALID_DURATION"
    }
  ]
}

```

---

### 2. Query Machine Stats

**Endpoint:** `GET /stats`

**Parameters:** * `machineId` (String)

* `start` (ISO-8601 String, Inclusive)
* `end` (ISO-8601 String, Exclusive)

**Sample Request:** `GET /stats?machineId=M1&start=2026-01-12T10:00:00Z&end=2026-01-12T11:00:00Z`

**Sample Response:**

```json
{
  "machineId": "M1",
  "start": "2026-01-12T10:00:00Z",
  "end": "2026-01-12T11:00:00Z",
  "eventsCount": 2,
  "defectsCount": 5,
  "avgDefectRate": 5.0,
  "status": "Warning"
}

```

*Note: `avgDefectRate` is calculated as `totalDefects / windowHours`. A rate ≥ 2.0 triggers a "Warning" status.*

---

### 3. Top Defect Lines

**Endpoint:** `GET /stats/top-defect-lines`

**Parameters:** `factoryId` (String)

* `from` (String)
* `to` (IString)
* `limit` (Integer)

**Sample Request:** `GET /stats/top-defect-lines?factoryId=F1&from=2026-01-12T00:00:00Z&to=2026-01-12T23:59:59Z&limit=5`

**Sample Response:**

```json
[
  {
    "lineId": "L1",
    "totalDefects": 15,
    "eventCount": 3,
    "defectsPercent": 500.0
  },
  {
    "lineId": "L2",
    "totalDefects": 2,
    "eventCount": 10,
    "defectsPercent": 20.0
  }
]

```


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


## Why Hashing?

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

## 🗄️ Why H2 as the Database?

### Decision

Use **H2 (embedded, in-memory / file-based)**.

### Why This Was Chosen

* **Zero external dependencies** → runs locally without Docker or credentials
* **Deterministic tests** → clean state for concurrency and benchmark testing
* **Supports real SQL semantics** → transactions, isolation, constraints, indexes
* **Stateless** → The service logic is written to be Stateless. The exact same code would work with a persistent PostgreSQL or Oracle instance by simply changing the application.properties connection string.


---

## 🔐 Why `eventId` as the Primary Key?

* Represents **business-level uniqueness**
* Database enforces correctness under concurrent inserts
* Simplifies concurrency by letting the DB be the source of truth

---

## ⚡ Why Payload Hashing for Deduplication?

### Problem

Field-by-field comparison is expensive and verbose.

### Solution

Reduce logical event content to a single `payloadHash`.

### Benefits

* **O(1) equality check**
* **CPU-efficient**
* **Clean deduplication logic**

Collision risk is negligible for this domain; worst case is a false dedupe, not corruption.

Cryptographic hashes were intentionally avoided — deduplication ≠ security.

---

## 🔁 Why Server-Generated `receivedTime`?

* Client clocks are unreliable
* Guarantees deterministic ordering
* Enables safe retries and idempotency

**Rule:**

> The event with the latest server-observed `receivedTime` always wins.

---

## 🔒 Why `@Transactional(isolation = SERIALIZABLE)`?

### Reasoning

* Strongest correctness guarantee
* Eliminates write-write anomalies
* Simplifies mental model for correctness

Performance cost is acceptable for the target scale.

**Production alternatives:**

* Optimistic locking + retry
* Kafka partitioned single-writer model

---

## Thread-Safety & Concurrency

Handling 20+ parallel sensor streams requires a multi-layered approach to prevent data corruption and race conditions:

* **Transactional Semantics**: The `processBatch` method is marked with `@Transactional(isolation = Isolation.SERIALIZABLE)`. This is the highest isolation level, ensuring that concurrent transactions do not result in "phantom reads."
* **Database Constraints**: We treat the Database as the single source of truth. By using the `eventId` as a Primary Key, we rely on the DB's internal locking mechanisms to prevent duplicate identity insertion.
* **The "Flush & Catch" Strategy**: Instead of using heavy Java-level synchronized blocks (which would slow down the app), we use **Optimistic Concurrency Control**.
* The code calls `repository.flush()` immediately after a `save`.
* If two threads attempt to insert the same `eventId` at the exact same microsecond, the database will throw a `UniqueConstraintViolation`.
* Our Service catches this specific exception and redirects the "losing" thread to re-run the deduplication/update logic, ensuring no data is lost and no duplicates are created.



---

## Performance Strategy (1,000 events / 1 sec)

To meet the strict sub-second requirement, ForgeStream employs the following optimizations:

* **Payload Hashing ($O(1)$ Comparison)**: Comparing seven different fields is CPU-intensive. By pre-calculating a payloadHash (Integer), we detect data changes in a single clock cycle ($O(1)$).
* **Short-Circuit Validation**: We perform validation (Future-dating and Duration checks) before any database connection is opened. This prevents "junk data" from consuming expensive DB resources.
* **Indexed Time-Series Lookups**: The `EventRepository` is optimized with composite indexing on `(machineId, eventTime)`. This allows filtering millions of rows in logarithmic time ($O(\log n)$), rather than a slow full-table scan ($O(n)$).
* **Minimal Object Allocation**: We use a focused DTO-to-Entity mapping strategy to reduce Garbage Collection (GC) overhead during high-load batches.

---

## Edge Cases & Assumptions

Engineering involves trade-offs. Here is how ForgeStream handles specific scenarios:

* **Clock Drift**
  - **Problem**: A sensor's clock might be slightly ahead of the server.
  - **Solution**: We allow a **15-minute buffer** for future-dated events.  
    Anything beyond that is rejected to prevent skewed *Machine Health* stats.

* **The "Winning" Record (Conflict Resolution)**
  - **Assumption**: The server's `receivedTime` is the ultimate source of truth for data freshness.
  - **Decision**: If two events with the same ID but different data arrive,  
    the one the server sees **last** wins, provided the data isn't older than the current record.  
    This ensures eventual consistency even if network packets arrive out of order.

* **Missing Defect Data**
  - **Handling**: A `defectCount` of `-1` is treated as *Sensor Error/Unknown*.
  - **Trade-off**: We still count the event (so `eventsCount` is accurate)  
    but exclude the `-1` from the total sum and averages to avoid poisoning the Defect Rate metrics.

* **Time Window Boundaries**
  - **Decision**: We use **Start-Inclusive, End-Exclusive** logic.
  - **Reasoning**: This prevents an event at exactly `11:00:00` from being counted in two different hourly reports.


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




## 🔮 Future Improvements

* Redis cache for dedupe acceleration
* JDBC batch inserts
* Partitioned tables


































---


