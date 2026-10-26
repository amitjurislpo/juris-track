package com.jurislpo.juristrack.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.jurislpo.juristrack.config.AuditProperties;
import com.jurislpo.juristrack.entity.AuditLog;
import com.jurislpo.juristrack.repository.AuditLogRepository;
import com.jurislpo.juristrack.security.AuditRequestContext;
import com.jurislpo.juristrack.security.AuthenticationSource;
import com.jurislpo.juristrack.security.CurrentUser;
import com.jurislpo.juristrack.security.IntegrationServiceClaims;
import com.jurislpo.juristrack.tenancy.TenantContext;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes the audit log: who ({@code actorId}, with their role) did what ({@link AuditAction}) to whom ({@code subjectId}) and to which
 * record, the record before and after, why, how it ended ({@link AuditOutcome}) and from which request. The request context (source,
 * IP address, user agent, request id, method, path) is filled in here from {@link AuditRequestContext}, so callers never pass it.
 * <ul>
 *   <li>{@link #record} joins the caller's transaction: the entry is written together with the change or not at all.</li>
 *   <li>{@link #recordIndependently} writes in a transaction of its own, so the entry stays although the request fails or its
 *       transaction rolls back (refusals). Call it only when no transaction of the request is open or holds a connection (after a
 *       rollback, or from the exception handler): it needs a second connection, which a request that already holds one must not
 *       take. A failure to write is logged, never thrown.</li>
 *   <li>{@link #recordOncePerWindow} does the same for high-volume security events, at most once per key in each
 *       {@link AuditAggregation#WINDOW}.</li>
 *   <li>{@link #recordAccess} records that somebody read another person's data; reading one's own data is not recorded. It joins the
 *       read's own transaction (which is therefore not read-only): one connection per request, the entry is written with the read or
 *       the read fails, so data is never served without its entry.</li>
 * </ul>
 * An entry that nobody signed in caused (a sign-in attempt, a refused token or cookie) counts against the caller's IP address:
 * {@link AuditProperties#unauthenticatedEntriesPerIp()} per window, and what is over the cap is not written (the log is append-only
 * and must not be growable without bound by anonymous callers); a warning in the server log says so.
 * Secrets never reach the log: values of keys that name a password, token, secret, cookie, authorization header or one-time code are
 * replaced by {@value #REDACTED} in {@code before} and {@code after}. The application never updates or deletes an entry (the database
 * refuses it as well).
 */
@Service
public class AuditService {

    /**
     * One audit entry.
     *
     * @param businessId  the business it belongs to; null = the current business (TenantContext), or a platform event in system mode
     * @param actorId     who did it (null: nobody signed in, e.g. a failed sign-in for an unknown email)
     * @param subjectId   the user it was done to or is about, if any
     * @param action      what happened
     * @param entityType  the kind of record ({@code session}, {@code user}, {@code business}, ...)
     * @param entityId    the record's id, if any
     * @param before      the record before (any JSON-serialisable value), or null
     * @param after       the record after, or the details, or null
     * @param reason      why (mandatory for corrections; the code of a refusal), or null
     * @param outcome     how it ended; null = the action's {@link AuditAction#defaultOutcome()}
     */
    public record AuditEntry(UUID businessId, UUID actorId, UUID subjectId, AuditAction action, String entityType, String entityId,
            Object before, Object after, String reason, AuditOutcome outcome) {

        /** An entry with the action's usual outcome. */
        public AuditEntry(UUID businessId, UUID actorId, UUID subjectId, AuditAction action, String entityType, String entityId,
                Object before, Object after, String reason) {
            this(businessId, actorId, subjectId, action, entityType, entityId, before, after, reason, null);
        }
    }

    static final String REDACTED = "[redacted]";
    private static final List<String> SENSITIVE_KEY_PARTS = List.of("password", "token", "secret", "cookie", "authorization", "otp", "codehash");
    private static final int MAXIMUM_REASON_LENGTH = 500;
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final AuditAggregation auditAggregation;
    private final TransactionTemplate independentTransaction;
    private final RateLimiter rateLimiter;
    private final int unauthenticatedEntriesPerIp;

    public AuditService(AuditLogRepository auditLogRepository, ObjectMapper objectMapper, JdbcTemplate jdbcTemplate, AuditAggregation auditAggregation,
            PlatformTransactionManager transactionManager, RateLimiter rateLimiter, AuditProperties auditProperties) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.auditAggregation = auditAggregation;
        this.rateLimiter = rateLimiter;
        this.unauthenticatedEntriesPerIp = auditProperties.unauthenticatedEntriesPerIp();
        this.independentTransaction = new TransactionTemplate(transactionManager);
        this.independentTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Writes the entry in the caller's transaction (or a new one when there is none). */
    @Transactional
    public void record(AuditEntry entry) {
        if (isOverUnauthenticatedCap(entry)) return;
        auditLogRepository.save(toAuditLog(entry));
    }

    /** Writes the entry in its own transaction: it stays even when the caller's work fails or rolls back. Never throws. */
    public void recordIndependently(AuditEntry entry) {
        if (isOverUnauthenticatedCap(entry)) return;
        try {
            independentTransaction.executeWithoutResult(transactionStatus -> auditLogRepository.save(toAuditLog(entry)));
        } catch (RuntimeException e) {
            log.error("[audit] could not record {}: the audit log has a gap", entry.action().storedName(), e);
        }
    }

    /**
     * True when nobody is signed in for this entry (no actor, no signed-in user) and the request's IP address has already caused its
     * share of such entries in this window. Entries outside a request (commands, jobs) are never capped.
     */
    private boolean isOverUnauthenticatedCap(AuditEntry entry) {
        AuditRequestContext requestContext = AuditRequestContext.current();
        if (requestContext == null || entry.actorId() != null || signedInUser() != null || isIntegrationServiceSignedIn()) return false;
        String ipAddress = requestContext.ipAddress() == null ? "unknown" : requestContext.ipAddress();
        boolean isOverCap = rateLimiter.checkAndHit("audit-unauthenticated:" + ipAddress, unauthenticatedEntriesPerIp, AuditAggregation.WINDOW.toMillis())
                .isLimited();
        if (isOverCap) log.warn("[audit] {} not recorded: {} has caused {} entries without a signed-in user in this window", entry.action().storedName(),
                ipAddress, unauthenticatedEntriesPerIp);
        return isOverCap;
    }

    /** Like {@link #recordIndependently}, but only the first time {@code aggregationKey} occurs in its window. */
    public void recordOncePerWindow(String aggregationKey, AuditEntry entry) {
        if (auditAggregation.isFirstInWindow(entry.action().storedName() + "|" + aggregationKey)) recordIndependently(entry);
    }

    /**
     * Records that {@code viewer} read data of somebody else: {@code subjectId} (null for a list of several people), the record
     * ({@code entityType}, {@code entityId}) and the details (filters, range). Nothing is recorded when the viewer read their own data.
     * Written in the read's own transaction (call it from a transaction that is not read-only), so a read needs one connection only
     * and is never served without its entry.
     */
    public void recordAccess(AuditAction action, CurrentUser viewer, UUID subjectId, String entityType, String entityId, Map<String, ?> details) {
        if (viewer == null || viewer.userId().equals(subjectId)) return;
        record(new AuditEntry(null, viewer.userId(), subjectId, action, entityType, entityId, null, details, null));
    }

    private AuditLog toAuditLog(AuditEntry entry) {
        AuditRequestContext requestContext = AuditRequestContext.current();
        CurrentUser currentUser = signedInUser();
        AuditLog auditLog = new AuditLog();
        auditLog.setBusinessId(entry.businessId() != null ? entry.businessId() : TenantContext.current());
        auditLog.setActorId(entry.actorId());
        auditLog.setActorRole(actorRoleOf(entry.actorId(), currentUser));
        auditLog.setSubjectId(entry.subjectId());
        auditLog.setAction(entry.action().storedName());
        auditLog.setCategory(entry.action().category().storedName());
        auditLog.setOutcome((entry.outcome() != null ? entry.outcome() : entry.action().defaultOutcome()).name());
        auditLog.setEntityType(entry.entityType());
        auditLog.setEntityId(entry.entityId());
        auditLog.setBefore(toJson(entry.before()));
        auditLog.setAfter(toJson(entry.after()));
        auditLog.setReason(entry.reason() == null ? null : entry.reason().substring(0, Math.min(MAXIMUM_REASON_LENGTH, entry.reason().length())));
        auditLog.setSource(sourceOf(requestContext, currentUser).name());
        if (requestContext != null) {
            auditLog.setRequestId(requestContext.requestId());
            auditLog.setIpAddress(requestContext.ipAddress());
            auditLog.setUserAgent(requestContext.userAgent());
            auditLog.setHttpMethod(requestContext.httpMethod());
            auditLog.setRequestPath(requestContext.requestPath());
        }
        return auditLog;
    }

    /** JurisOne's service token authenticated this request (the roster sync): an authenticated caller without a user, never capped. */
    private static boolean isIntegrationServiceSignedIn() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof IntegrationServiceClaims;
    }

    private static CurrentUser signedInUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof CurrentUser currentUser ? currentUser : null;
    }

    /** SYSTEM without a request; INTEGRATION for JurisOne's token; else the signed-in user's source, or STANDALONE (sign-in requests). */
    private static AuditSource sourceOf(AuditRequestContext requestContext, CurrentUser currentUser) {
        if (requestContext == null) return AuditSource.SYSTEM;
        if (requestContext.isIntegrationRequest() || (currentUser != null && currentUser.source() == AuthenticationSource.INTEGRATION)) {
            return AuditSource.INTEGRATION;
        }
        return AuditSource.STANDALONE;
    }

    /** The actor's role now (the signed-in user's, else read from the database); null without an actor. */
    private String actorRoleOf(UUID actorId, CurrentUser currentUser) {
        if (actorId == null) return null;
        if (currentUser != null && actorId.equals(currentUser.userId())) return currentUser.role().name();
        return jdbcTemplate.queryForList("SELECT role::text FROM users WHERE id = ?", String.class, actorId).stream().findFirst().orElse(null);
    }

    private JsonNode toJson(Object value) {
        if (value == null) return null;
        JsonNode node = objectMapper.valueToTree(value);
        if (node == null || node.isNull()) return null;
        redactSecrets(node);
        return node;
    }

    /** Replaces the value of every key that names a secret, at any depth. */
    static void redactSecrets(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            for (Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields(); fields.hasNext();) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (isSensitiveKey(field.getKey())) field.setValue(TextNode.valueOf(REDACTED));
                else redactSecrets(field.getValue());
            }
        } else if (node instanceof ArrayNode arrayNode) {
            arrayNode.forEach(AuditService::redactSecrets);
        }
    }

    static boolean isSensitiveKey(String key) {
        String lowerCaseKey = key.toLowerCase(Locale.ROOT);
        return SENSITIVE_KEY_PARTS.stream().anyMatch(lowerCaseKey::contains);
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
