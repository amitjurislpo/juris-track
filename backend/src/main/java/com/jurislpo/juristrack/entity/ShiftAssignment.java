package com.jurislpo.juristrack.entity;

import com.jurislpo.juristrack.tenancy.TenantContext;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** From {@code effectiveFrom} (a shift date) on, the employee works {@code shiftId}; the latest assignment not after a date applies. */
@Entity
@Table(name = "employee_shift_assignments")
public class ShiftAssignment {

    @Id
    @Column(name = "id")
    private UUID id;

    /** Filled from TenantContext when not set (system mode must set it). */
    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "shift_id", nullable = false)
    private UUID shiftId;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void fillDefaults() {
        if (id == null) id = UUID.randomUUID();
        if (businessId == null) businessId = TenantContext.current();
        if (createdAt == null) createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() { return id; }
    public UUID getBusinessId() { return businessId; }
    public void setBusinessId(UUID businessId) { this.businessId = businessId; }
    public UUID getEmployeeId() { return employeeId; }
    public void setEmployeeId(UUID employeeId) { this.employeeId = employeeId; }
    public UUID getShiftId() { return shiftId; }
    public void setShiftId(UUID shiftId) { this.shiftId = shiftId; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(LocalDate effectiveFrom) { this.effectiveFrom = effectiveFrom; }
}
