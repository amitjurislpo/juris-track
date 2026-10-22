package com.jurislpo.juristrack.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One customer business (multi-business: every business-owned row carries its id).
 */
@Entity
@Table(name = "businesses")
public class Business {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "time_zone", nullable = false)
    private String timeZone = "Asia/Kolkata";

    @Column(name = "stale_workday_hours", nullable = false)
    private int staleWorkdayHours = 14;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "display_time_zones", nullable = false, columnDefinition = "text[]")
    private List<String> displayTimeZones = new ArrayList<>(List.of("Asia/Kolkata", "America/New_York"));

    @Column(name = "jurisone_business_id")
    private UUID jurisoneBusinessId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void fillDefaults() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void touchUpdatedAt() {
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTimeZone() { return timeZone; }
    public void setTimeZone(String timeZone) { this.timeZone = timeZone; }
    public int getStaleWorkdayHours() { return staleWorkdayHours; }
    public void setStaleWorkdayHours(int staleWorkdayHours) { this.staleWorkdayHours = staleWorkdayHours; }
    public List<String> getDisplayTimeZones() { return displayTimeZones; }
    public void setDisplayTimeZones(List<String> displayTimeZones) { this.displayTimeZones = displayTimeZones; }
    public UUID getJurisoneBusinessId() { return jurisoneBusinessId; }
    public void setJurisoneBusinessId(UUID jurisoneBusinessId) { this.jurisoneBusinessId = jurisoneBusinessId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
