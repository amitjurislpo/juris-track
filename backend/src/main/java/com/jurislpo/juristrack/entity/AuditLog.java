package com.jurislpo.juristrack.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.jurislpo.juristrack.tenancy.TenantContext;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One audited change: who did it ({@code actorId}), to whom ({@code subjectId}), the record before and after, and why.
 * Written once, never changed. {@code businessId} is null for a system event outside any business.
 */
@Entity
@Immutable
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @Column(name = "id")
    private UUID id;

    /** Filled from TenantContext when not set (system mode must set it). */
    @Column(name = "business_id")
    private UUID businessId;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "subject_id")
    private UUID subjectId;

    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "entity_id")
    private String entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before")
    private JsonNode before;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after")
    private JsonNode after;

    @Column(name = "reason")
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void fillDefaults() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (id == null) id = UUID.randomUUID();
        if (businessId == null) businessId = TenantContext.current();
        if (createdAt == null) createdAt = now;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getBusinessId() { return businessId; }
    public void setBusinessId(UUID businessId) { this.businessId = businessId; }
    public UUID getActorId() { return actorId; }
    public void setActorId(UUID actorId) { this.actorId = actorId; }
    public UUID getSubjectId() { return subjectId; }
    public void setSubjectId(UUID subjectId) { this.subjectId = subjectId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }
    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }
    public JsonNode getBefore() { return before; }
    public void setBefore(JsonNode before) { this.before = before; }
    public JsonNode getAfter() { return after; }
    public void setAfter(JsonNode after) { this.after = after; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
