package com.jurislpo.juristrack.entity;

import com.jurislpo.juristrack.tenancy.TenantContext;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** One roster entry: on {@code entryDate} the employee works {@code shiftId}, or has a week off when it is null. */
@Entity
@Table(name = "employee_schedule_entries")
public class ScheduleEntry {

    @Id
    @Column(name = "id")
    private UUID id;

    /** Filled from TenantContext when not set (system mode must set it). */
    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Column(name = "shift_id")
    private UUID shiftId;

    @Column(name = "reason")
    private String reason;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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

    public UUID getId() { return id; }
    public UUID getBusinessId() { return businessId; }
    public void setBusinessId(UUID businessId) { this.businessId = businessId; }
    public UUID getEmployeeId() { return employeeId; }
    public void setEmployeeId(UUID employeeId) { this.employeeId = employeeId; }
    public LocalDate getEntryDate() { return entryDate; }
    public void setEntryDate(LocalDate entryDate) { this.entryDate = entryDate; }
    public UUID getShiftId() { return shiftId; }
    public void setShiftId(UUID shiftId) { this.shiftId = shiftId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
}
