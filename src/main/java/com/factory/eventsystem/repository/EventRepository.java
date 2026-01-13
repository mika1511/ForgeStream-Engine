package com.factory.eventsystem.repository;

import com.factory.eventsystem.model.MachineEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface EventRepository extends JpaRepository<MachineEvent, String > {

    List<MachineEvent> findByMachineIdAndEventTimeGreaterThanEqualAndEventTimeLessThan(
            String machineId,
            Instant start,
            Instant end
    );

    List<MachineEvent> findByFactoryIdAndEventTimeGreaterThanEqualAndEventTimeLessThan(
            String factoryId, Instant from, Instant to
    );

}
