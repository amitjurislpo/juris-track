package com.jurislpo.juristrack.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.juristrack.entity.AuditLog;
import com.jurislpo.juristrack.repository.AuditLogRepository;
import com.jurislpo.juristrack.tenancy.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the audit log: who ({@code actorId}) did what ({@link AuditAction}) to whom ({@code subjectId}) and to which record, the
 * record before and after, and why. Joins the caller's transaction, so the entry is written together with the change or not at all.
 */
@Service
public class AuditService {

    /**
     * One audit entry.
     *
     * @param businessId  the business it belongs to; null = the current business (TenantContext), or a platform event in system mode
     * @param actorId     who did it (null: nobody signed in, e.g. a failed sign-in for an unknown email)
     * @param subjectId   the user it was done to, if any
     * @param action      what happened
     * @param entityType  the kind of record ({@code session}, {@code user}, {@code business}, ...)
     * @param entityId    the record's id, if any
     * @param before      the record before (any JSON-serialisable value), or null
     * @param after       the record after, or null
     * @param reason      why (mandatory for corrections), or null
     */
    public record AuditEntry(UUID businessId, UUID actorId, UUID subjectId, AuditAction action, String entityType, String entityId,
            Object before, Object after, String reason) {}

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void record(AuditEntry entry) {
        AuditLog auditLog = new AuditLog();
        auditLog.setBusinessId(entry.businessId() != null ? entry.businessId() : TenantContext.current());
        auditLog.setActorId(entry.actorId());
        auditLog.setSubjectId(entry.subjectId());
        auditLog.setAction(entry.action().storedName());
        auditLog.setEntityType(entry.entityType());
        auditLog.setEntityId(entry.entityId());
        auditLog.setBefore(toJson(entry.before()));
        auditLog.setAfter(toJson(entry.after()));
        auditLog.setReason(entry.reason());
        auditLogRepository.save(auditLog);
    }

    private JsonNode toJson(Object value) {
        if (value == null) return null;
        JsonNode node = objectMapper.valueToTree(value);
        return node == null || node.isNull() ? null : node;
    }

    /** {@code a***@company.com}: enough to recognise an address in the log without storing it whole for unknown accounts. */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) return "";
        String trimmedEmail = email.trim();
        int atIndex = trimmedEmail.indexOf('@');
        if (atIndex < 1) return "***";
        return trimmedEmail.charAt(0) + "***" + trimmedEmail.substring(atIndex);
    }
}
