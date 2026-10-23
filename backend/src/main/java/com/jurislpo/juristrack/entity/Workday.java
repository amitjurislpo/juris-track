package com.jurislpo.juristrack.entity;

import com.jurislpo.juristrack.tenancy.TenantContext;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One employee's workday. {@code workDate} is the calendar date in the business time zone when work started;
 * {@code activeEmployeeId} equals the employee while the day is open. The totals are a cache recomputed from the sessions.
 */
@Entity
@Table(name = "workdays")
public class Workday {

    @Id
    @Column(name = "id")
    private UUID id;

    /** Filled from TenantContext when not set (system mode must set it). */
    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    /** The employee's manager when the workday started (reporting snapshot). */
    @Column(name = "manager_id")
    private UUID managerId;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(name = "time_zone", nullable = false)
    private String timeZone;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false, columnDefinition = "workday_status")
    private WorkdayStatus status = WorkdayStatus.WORKING;

    @Column(name = "active_employee_id")
    private UUID activeEmployeeId;

    @Column(name = "productive_seconds")
    private Integer productiveSeconds;

    @Column(name = "break_seconds")
    private Integer breakSeconds;

    @Column(name = "break_count")
    private Integer breakCount;

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
    public void setId(UUID id) { this.id = id; }
    public UUID getBusinessId() { return businessId; }
    public void setBusinessId(UUID businessId) { this.businessId = businessId; }
    public UUID getEmployeeId() { return employeeId; }
    public void setEmployeeId(UUID employeeId) { this.employeeId = employeeId; }
    public UUID getManagerId() { return managerId; }
    public void setManagerId(UUID managerId) { this.managerId = managerId; }
    public LocalDate getWorkDate() { return workDate; }
    public void setWorkDate(LocalDate workDate) { this.workDate = workDate; }
    public String getTimeZone() { return timeZone; }
    public void setTimeZone(String timeZone) { this.timeZone = timeZone; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
    public WorkdayStatus getStatus() { return status; }
    public void setStatus(WorkdayStatus status) { this.status = status; }
    public UUID getActiveEmployeeId() { return activeEmployeeId; }
    public void setActiveEmployeeId(UUID activeEmployeeId) { this.activeEmployeeId = activeEmployeeId; }
    public Integer getProductiveSeconds() { return productiveSeconds; }
    public void setProductiveSeconds(Integer productiveSeconds) { this.productiveSeconds = productiveSeconds; }
    public Integer getBreakSeconds() { return breakSeconds; }
    public void setBreakSeconds(Integer breakSeconds) { this.breakSeconds = breakSeconds; }
    public Integer getBreakCount() { return breakCount; }
    public void setBreakCount(Integer breakCount) { this.breakCount = breakCount; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
