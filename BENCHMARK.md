
# ForgeStream Engine: Performance Benchmark

## 1. Environment Specifications

* **CPU:** Intel Core i5‑13450HX (10 cores, 16 threads, 2.40 GHz base, up to 4.6 GHz boost)
* **RAM:** 16.0 GB DDR4-3200
* **Storage:** SSD (NVMe recommended)
* **OS:** Windows 11 Home/Pro (64-bit)
* **Runtime:** Java 17 (OpenJDK), H2 In-Memory Database
---

## 2. Benchmark Execution

The benchmark was performed using a dedicated JUnit integration test that simulates a high-load scenario.

### Command to run:

```bash
mvn test -Dtest=EventServiceTest#runBenchmark

```

### Measured Results:

| Task | Batch Size | Measured Time | Status |
| --- | --- |---------------| --- |
| **Ingest & Process Batch** | 1,000 Events | 702 ms        | **PASSED** |

---

## 3. Optimizations Attempted

To achieve sub-second processing for 1,000 events, the following optimizations were implemented:

* **Payload Hashing ( Comparison):** Instead of comparing 6+ fields (Strings/Dates) individually to detect updates, I implemented a pre-calculated integer `payloadHash`. This allows the service to determine if a record is a duplicate using a single numerical comparison.
* **Manual Flush Strategy:** By using `repository.flush()` immediately after saving new records, the system triggers database unique-constraint checks early. This allows the `try-catch` block to handle race conditions instantly without waiting for the full transaction commit.
* **Database Indexing:** Added a composite index on `(machine_id, event_time)` to ensure that the Query Stats and Ranking endpoints remain fast even as the database grows.
* **Batch Persistence:** Utilized Spring Data JPA's ability to handle the list efficiently, reducing the overhead of opening and closing database connections for every single event.

---

## 4. Concurrency Test Results

The system was tested with **20 parallel threads** hitting the `/events/batch` endpoint simultaneously.

* **Result:** 0% data corruption.
* **Handling:** The `Isolation.SERIALIZABLE` level combined with the `try-catch` conflict resolution logic successfully reconciled overlapping `eventId` requests.
