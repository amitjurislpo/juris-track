package com.jurislpo.juristrack.repository;

import com.jurislpo.juristrack.entity.AuditLog;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The audit log (written by service.AuditService, never changed). */
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {}
