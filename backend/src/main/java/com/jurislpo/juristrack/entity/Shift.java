package com.jurislpo.juristrack.entity;

import com.jurislpo.juristrack.engine.ShiftSchedule;
import com.jurislpo.juristrack.tenancy.TenantContext;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * A shift template of a business: a start and an end time on the business time zone's wall clock (an end at or before the start means
 * the next day) and the attribution grace. Exactly one shift per business is the default (the calendar day).
 */
@Entity
@Table(name = "shifts")
public class Shift {

    @Id
    @Column(name = "id")
    private UUID id;

    /** Filled from TenantContext when not set (system mode must set it). */
    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "name", nullable = false)
    private String name;

    /** The code JurisOne gave the shift (unique inside the business); null for a shift made in JurisTrack. */
    @Column(name = "code")
    private String code;

    /** Minutes after midnight on the business time zone's wall clock (stored as a number so no time zone setting can shift it). */
    @Column(name = "start_minute", nullable = false)
    private int startMinute;

    @Column(name = "end_minute", nullable = false)
    private int endMinute;

    @Column(name = "early_start_minutes", nullable = false)
    private int earlyStartMinutes = DEFAULT_GRACE_MINUTES;

    @Column(name = "late_end_minutes", nullable = false)
    private int lateEndMinutes = DEFAULT_GRACE_MINUTES;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static final int DEFAULT_GRACE_MINUTES = 180;
    public static final int MAXIMUM_GRACE_MINUTES = 720;

    @PrePersist
    void fillDefaults() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (id == null) id = UUID.randomUUID();
        if (businessId == null) businessId = TenantContext.current();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void touchUpdatedAt() {
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** The shift as the engine uses it. */
    public ShiftSchedule toSchedule() {
        return new ShiftSchedule(id, name, getStartTime(), getEndTime(), earlyStartMinutes, lateEndMinutes);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getBusinessId() { return businessId; }
    public void setBusinessId(UUID businessId) { this.businessId = businessId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public LocalTime getStartTime() { return LocalTime.of(startMinute / 60, startMinute % 60); }
    public void setStartTime(LocalTime startTime) { this.startMinute = startTime.getHour() * 60 + startTime.getMinute(); }
    public LocalTime getEndTime() { return LocalTime.of(endMinute / 60, endMinute % 60); }
    public void setEndTime(LocalTime endTime) { this.endMinute = endTime.getHour() * 60 + endTime.getMinute(); }
    public int getEarlyStartMinutes() { return earlyStartMinutes; }
    public void setEarlyStartMinutes(int earlyStartMinutes) { this.earlyStartMinutes = earlyStartMinutes; }
    public int getLateEndMinutes() { return lateEndMinutes; }
    public void setLateEndMinutes(int lateEndMinutes) { this.lateEndMinutes = lateEndMinutes; }
    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }
    public boolean isActive() { return isActive; }
    public void setActive(boolean isActive) { this.isActive = isActive; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
