package com.factory.eventsystem.model;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "machine_events",
        indexes = {
                @Index(name = "idx_machine_time", columnList = "machineId,eventTime"),
                @Index(name = "idx_factory_time", columnList = "factoryId,eventTime")
        })
public class MachineEvent {

    @Id
    @Column(unique = true, nullable = false)
    private String eventId;

    @Column(nullable = false)
    private Instant eventTime;

    @Column(nullable = false)
    private Instant receivedTime;

    @Column(nullable = false)
    private String machineId;

    @Column(nullable = false)
    private String lineId;

    @Column(nullable = false)
    private String factoryId;

    @Column(nullable = false)
    private Long durationMs;

    @Column(nullable = false)
    private Integer defectCount;

    @Column(nullable = false)
    private Integer payloadHash;

    // Constructors
    public MachineEvent() {
    }

    public MachineEvent(String eventId, Instant eventTime, Instant receivedTime,
                        String machineId, String lineId, String factoryId,
                        Long durationMs, Integer defectCount, Integer payloadHash) {
        this.eventId = eventId;
        this.eventTime = eventTime;
        this.receivedTime = receivedTime;
        this.machineId = machineId;
        this.lineId = lineId;
        this.factoryId = factoryId;
        this.durationMs = durationMs;
        this.defectCount = defectCount;
        this.payloadHash = payloadHash;
    }

    // Getters
    public String getEventId() {
        return eventId;
    }

    public Instant getEventTime() {
        return eventTime;
    }

    public Instant getReceivedTime() {
        return receivedTime;
    }

    public String getMachineId() {
        return machineId;
    }

    public String getLineId() {
        return lineId;
    }

    public String getFactoryId() {
        return factoryId;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public Integer getDefectCount() {
        return defectCount;
    }

    public Integer getPayloadHash() {
        return payloadHash;
    }

    // Setters
    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public void setEventTime(Instant eventTime) {
        this.eventTime = eventTime;
    }

    public void setReceivedTime(Instant receivedTime) {
        this.receivedTime = receivedTime;
    }

    public void setMachineId(String machineId) {
        this.machineId = machineId;
    }

    public void setLineId(String lineId) {
        this.lineId = lineId;
    }

    public void setFactoryId(String factoryId) {
        this.factoryId = factoryId;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public void setDefectCount(Integer defectCount) {
        this.defectCount = defectCount;
    }

    public void setPayloadHash(Integer payloadHash) {
        this.payloadHash = payloadHash;
    }

    // Optional: toString for debugging
    @Override
    public String toString() {
        return "MachineEvent{" +
                "eventId='" + eventId + '\'' +
                ", eventTime=" + eventTime +
                ", receivedTime=" + receivedTime +
                ", machineId='" + machineId + '\'' +
                ", lineId='" + lineId + '\'' +
                ", factoryId='" + factoryId + '\'' +
                ", durationMs=" + durationMs +
                ", defectCount=" + defectCount +
                ", payloadHash=" + payloadHash +
                '}';
    }

    // Optional: equals and hashCode (important for JPA)
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MachineEvent that = (MachineEvent) o;
        return eventId != null && eventId.equals(that.eventId);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}