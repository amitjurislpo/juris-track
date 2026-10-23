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
 * One employee's workday. {@code workDate} is the SHIFT DATE: the date of the shift occurrence the day was attributed to when it
 * started, in the business time zone (the calendar date for the default shift; a night shift keeps the date it started on);
 * {@code activeEmployeeId} equals the employee while the day is open. The shift fields are a snapshot taken when the day started. The totals are a cache recomputed from the sessions.
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

    /** The shift the day was attributed to when it started; null for a record written without one. */
    @Column(name = "shift_id")
    private UUID shiftId;

    @Column(name = "shift_name")
    private String shiftName;

    @Column(name = "scheduled_start_at")
    private Instant scheduledStartAt;

    @Column(name = "scheduled_end_at")
    private Instant scheduledEndAt;

    /** The day started in no shift's attribution window (the nearest shift was taken). */
    @Column(name = "is_outside_shift", nullable = false)
    private boolean isOutsideShift;

    /** The day was started on a week off of the roster: all of its productive time is extra time. */
    @Column(name = "is_off_day", nullable = false)
    private boolean isOffDay;

    /** Cached with the other totals when the day is completed: productive seconds outside the shift's scheduled window. */
    @Column(name = "extra_seconds")
    private Integer extraSeconds;

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
    public UUID getShiftId() { return shiftId; }
    public void setShiftId(UUID shiftId) { this.shiftId = shiftId; }
    public String getShiftName() { return shiftName; }
    public void setShiftName(String shiftName) { this.shiftName = shiftName; }
    public Instant getScheduledStartAt() { return scheduledStartAt; }
    public void setScheduledStartAt(Instant scheduledStartAt) { this.scheduledStartAt = scheduledStartAt; }
    public Instant getScheduledEndAt() { return scheduledEndAt; }
    public void setScheduledEndAt(Instant scheduledEndAt) { this.scheduledEndAt = scheduledEndAt; }
    public boolean isOutsideShift() { return isOutsideShift; }
    public void setOutsideShift(boolean isOutsideShift) { this.isOutsideShift = isOutsideShift; }
    public boolean isOffDay() { return isOffDay; }
    public void setOffDay(boolean isOffDay) { this.isOffDay = isOffDay; }
    public Integer getExtraSeconds() { return extraSeconds; }
    public void setExtraSeconds(Integer extraSeconds) { this.extraSeconds = extraSeconds; }
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
