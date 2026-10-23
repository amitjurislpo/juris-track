package com.jurislpo.juristrack.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.juristrack.common.InputValidator;
import com.jurislpo.juristrack.common.ListPage;
import com.jurislpo.juristrack.common.ListQuery;
import com.jurislpo.juristrack.engine.BusinessCalendar;
import com.jurislpo.juristrack.entity.Role;
import com.jurislpo.juristrack.exception.ApiException;
import com.jurislpo.juristrack.exception.Problems;
import com.jurislpo.juristrack.repository.AuditLogQueryRepository;
import com.jurislpo.juristrack.repository.AuditLogQueryRepository.AuditLogFilter;
import com.jurislpo.juristrack.repository.AuditLogQueryRepository.AuditLogRecord;
import com.jurislpo.juristrack.repository.BusinessRepository;
import com.jurislpo.juristrack.security.CurrentUser;
import com.jurislpo.juristrack.service.AuditService.AuditEntry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The audit log as the owner (their business) and the platform super admin (every business, and platform events without one) read it:
 * a filtered, sorted page, one entry in full, and the CSV export of the same filter. Only reading: nobody changes or deletes entries.
 * Viewing the log is not audited; every export is ({@code audit.exported}, with the filters and the row count).
 *
 * <p>Filters: {@code from} / {@code to} (calendar dates, inclusive; in the business time zone for the owner, UTC for the super admin),
 * {@code actorId}, {@code subjectId}, {@code action} (exact, or a prefix when it ends with a dot, e.g. {@code time.}), {@code category},
 * {@code entityType}, {@code entityId}, {@code outcome}, {@code source}, {@code search} (action, names, request id, entity id, IP address),
 * and for the super admin {@code businessId} (a business id, or {@code none} for entries outside any business).
 */
@Service
public class AuditLogService {

    /** A row of the list. {@code actor} / {@code subject} are null when the entry has none. */
    public record AuditLogRow(UUID id, Instant createdAt, UUID businessId, String businessName, AuditActor actor, AuditSubject subject, String action,
            String category, String outcome, String entityType, String entityId, String source, String ipAddress, String requestId, String reason,
            String summary) {}

    /** One entry in full: the row's fields plus the record before and after and the request it came from. */
    public record AuditLogDetail(UUID id, Instant createdAt, UUID businessId, String businessName, AuditActor actor, AuditSubject subject,
            String action, String category, String outcome, String entityType, String entityId, String source, String ipAddress, String requestId,
            String reason, String summary, JsonNode before, JsonNode after, String userAgent, String httpMethod, String requestPath) {}

    /** Who did it: {@code name} is null when the person is outside the viewer's business (the platform super admin in an owner's log). */
    public record AuditActor(UUID id, String name, String role) {}

    /** Whom it was about. */
    public record AuditSubject(UUID id, String name) {}

    /** A CSV file: its name and its bytes (UTF-8 with a byte order mark, CRLF line ends). */
    public record CsvFile(String fileName, byte[] content) {}

    /** The sort keys of {@code GET /api/audit-logs}. */
    public static final List<String> SORT_KEYS = List.of("createdAt", "action", "category", "outcome", "actor", "subject");
    /** The most rows one export may hold; a larger filter answers 422 {@code EXPORT_TOO_LARGE}. */
    public static final int MAXIMUM_EXPORT_ROWS = 10_000;
    static final String NOT_FOUND = "Audit entry not found.";
    private static final String WITHOUT_BUSINESS = "none";
    private static final Pattern ACTION_PATTERN = Pattern.compile("^[a-z_]+(\\.[a-z_]*)?$");
    private static final int MAXIMUM_FILTER_LENGTH = 100;
    private static final List<String> FILTER_PARAMETERS = List.of("from", "to", "actorId", "subjectId", "action", "category", "entityType", "entityId",
            "outcome", "source", "search", "businessId", "sort", "sortDirection");
    private static final List<String> CSV_HEADER = List.of("Time (UTC)", "Business", "Actor", "Actor role", "Subject", "Action", "Category", "Outcome",
            "Entity type", "Entity id", "Source", "IP address", "Request id", "Method", "Path", "Reason", "Summary", "Before", "After");

    private final AuditLogQueryRepository auditLogQueryRepository;
    private final BusinessRepository businessRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public AuditLogService(AuditLogQueryRepository auditLogQueryRepository, BusinessRepository businessRepository, AuditService auditService,
            ObjectMapper objectMapper) {
        this.auditLogQueryRepository = auditLogQueryRepository;
        this.businessRepository = businessRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /** One page of the entries the viewer may see (owner: their business; super admin: every one), newest first by default. */
    @Transactional(readOnly = true)
    public ListPage<AuditLogRow> list(CurrentUser viewer, Map<String, String> queryParameters) {
        Map<String, String> parameters = withDefaultSortDirection(queryParameters);
        InputValidator validator = new InputValidator();
        ListQuery listQuery = ListQuery.of(parameters, SORT_KEYS, "createdAt", validator);
        AuditLogFilter filter = filterOf(viewer, parameters, validator);
        validator.throwIfInvalid();
        List<AuditLogRow> rows = auditLogQueryRepository.findPage(filter, listQuery, listQuery.offset(), listQuery.pageSize()).stream()
                .map(this::rowOf).toList();
        return new ListPage<>(rows, auditLogQueryRepository.count(filter, listQuery), listQuery.page(), listQuery.pageSize());
    }

    /** One entry in full; 404 when unknown or of another business. */
    @Transactional(readOnly = true)
    public AuditLogDetail find(CurrentUser viewer, UUID auditLogId) {
        AuditLogRecord entry = auditLogQueryRepository.findById(auditLogId)
                .filter(found -> viewer.role() == Role.SUPER_ADMIN || Objects.equals(found.businessId(), viewer.businessId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND));
        AuditLogRow row = rowOf(entry);
        return new AuditLogDetail(row.id(), row.createdAt(), row.businessId(), row.businessName(), row.actor(), row.subject(), row.action(), row.category(),
                row.outcome(), row.entityType(), row.entityId(), row.source(), row.ipAddress(), row.requestId(), row.reason(), row.summary(),
                parse(entry.beforeJson()), parse(entry.afterJson()), entry.userAgent(), entry.httpMethod(), entry.requestPath());
    }

    /**
     * Every entry of the filter (sorted like the list, paging ignored) as CSV, at most {@value #MAXIMUM_EXPORT_ROWS} rows (422
     * {@code EXPORT_TOO_LARGE} beyond). The export itself is audited ({@code audit.exported}) with the filters and the row count.
     */
    @Transactional
    public CsvFile export(CurrentUser viewer, Map<String, String> queryParameters) {
        Map<String, String> parameters = withDefaultSortDirection(queryParameters);
        InputValidator validator = new InputValidator();
        ListQuery listQuery = ListQuery.of(parameters, SORT_KEYS, "createdAt", validator);
        AuditLogFilter filter = filterOf(viewer, parameters, validator);
        validator.throwIfInvalid();
        long total = auditLogQueryRepository.count(filter, listQuery);
        if (total > MAXIMUM_EXPORT_ROWS) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Problems.EXPORT_TOO_LARGE, "The export would hold " + total + " entries; narrow the filters to at most "
                    + MAXIMUM_EXPORT_ROWS + ".");
        }
        List<AuditLogRecord> entries = auditLogQueryRepository.findPage(filter, listQuery, 0, MAXIMUM_EXPORT_ROWS);
        Map<String, Object> details = new LinkedHashMap<>();
        for (String parameter : FILTER_PARAMETERS) if (queryParameters.containsKey(parameter)) details.put(parameter, queryParameters.get(parameter));
        details.put("rowCount", entries.size());
        auditService.record(new AuditEntry(null, viewer.userId(), null, AuditAction.AUDIT_LOG_EXPORTED, "audit_log", null, null, details, null));

        StringBuilder csv = new StringBuilder("﻿");
        appendCsvLine(csv, CSV_HEADER);
        for (AuditLogRecord entry : entries) {
            AuditLogRow row = rowOf(entry);
            List<String> cells = new ArrayList<>();
            cells.add(row.createdAt().toString());
            cells.add(row.businessName());
            cells.add(row.actor() == null ? null : row.actor().name());
            cells.add(row.actor() == null ? null : row.actor().role());
            cells.add(row.subject() == null ? null : row.subject().name());
            cells.add(row.action());
            cells.add(row.category());
            cells.add(row.outcome());
            cells.add(row.entityType());
            cells.add(row.entityId());
            cells.add(row.source());
            cells.add(row.ipAddress());
            cells.add(row.requestId());
            cells.add(entry.httpMethod());
            cells.add(entry.requestPath());
            cells.add(row.reason());
            cells.add(row.summary());
            cells.add(entry.beforeJson());
            cells.add(entry.afterJson());
            appendCsvLine(csv, cells);
        }
        LocalDate today = LocalDate.now(zoneOf(viewer));
        return new CsvFile("audit-log-" + today + ".csv", csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> withDefaultSortDirection(Map<String, String> queryParameters) {
        Map<String, String> parameters = new HashMap<>(queryParameters);
        parameters.putIfAbsent("sortDirection", "desc");
        return parameters;
    }

    /** Reads the filters; problems go to {@code validator}. The owner is always limited to their business. */
    private AuditLogFilter filterOf(CurrentUser viewer, Map<String, String> parameters, InputValidator validator) {
        boolean isSuperAdmin = viewer.role() == Role.SUPER_ADMIN;
        UUID businessId = isSuperAdmin ? null : viewer.businessId();
        boolean isWithoutBusiness = false;
        String rawBusinessId = text(parameters, "businessId");
        if (isSuperAdmin && rawBusinessId != null) {
            if (WITHOUT_BUSINESS.equalsIgnoreCase(rawBusinessId)) isWithoutBusiness = true;
            else businessId = uuid(validator, "businessId", rawBusinessId);
        }
        ZoneId zone = zoneOf(viewer);
        LocalDate fromDate = date(validator, "from", text(parameters, "from"));
        LocalDate toDate = date(validator, "to", text(parameters, "to"));
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            LocalDate earlierDate = toDate;
            toDate = fromDate;
            fromDate = earlierDate;
        }
        Instant from = fromDate == null ? null : BusinessCalendar.startOfDay(fromDate, zone);
        Instant toExclusive = toDate == null ? null : BusinessCalendar.startOfDay(toDate.plusDays(1), zone);

        String action = text(parameters, "action");
        boolean isActionPrefix = false;
        if (action != null) {
            action = action.toLowerCase(Locale.ROOT);
            if (!ACTION_PATTERN.matcher(action).matches()) validator.reject("action", "The action is a name such as time.workday_started, or a prefix such as time.");
            isActionPrefix = action.endsWith(".");
        }
        String category = text(parameters, "category");
        if (category != null) {
            Optional<AuditCategory> knownCategory = AuditCategory.fromStoredName(category);
            if (knownCategory.isEmpty()) validator.reject("category", "The category is one of: " + String.join(", ", categoryNames()) + ".");
            category = knownCategory.map(AuditCategory::storedName).orElse(null);
        }
        String outcome = enumValue(validator, "outcome", text(parameters, "outcome"), AuditOutcome.values());
        String source = enumValue(validator, "source", text(parameters, "source"), AuditSource.values());
        return new AuditLogFilter(businessId, isWithoutBusiness, from, toExclusive, uuid(validator, "actorId", text(parameters, "actorId")),
                uuid(validator, "subjectId", text(parameters, "subjectId")), action, isActionPrefix, category, text(parameters, "entityType"),
                text(parameters, "entityId"), outcome, source);
    }

    /** The owner's dates are in the business time zone, the super admin's in UTC. */
    private ZoneId zoneOf(CurrentUser viewer) {
        if (viewer.businessId() == null) return ZoneOffset.UTC;
        return businessRepository.findById(viewer.businessId()).map(business -> BusinessCalendar.zoneOf(business.getTimeZone())).orElse(ZoneOffset.UTC);
    }

    private AuditLogRow rowOf(AuditLogRecord entry) {
        AuditActor actor = entry.actorId() == null ? null : new AuditActor(entry.actorId(), entry.actorName(), entry.actorRole());
        AuditSubject subject = entry.subjectId() == null ? null : new AuditSubject(entry.subjectId(), entry.subjectName());
        return new AuditLogRow(entry.id(), entry.createdAt(), entry.businessId(), entry.businessName(), actor, subject, entry.action(), entry.category(),
                entry.outcome(), entry.entityType(), entry.entityId(), entry.source(), entry.ipAddress(), entry.requestId(), entry.reason(),
                summaryOf(entry));
    }

    /**
     * A short machine-made summary: the changed fields of an update ({@code "changed: role, managerId"}), else the reason of a refusal
     * or failure, else null.
     */
    private String summaryOf(AuditLogRecord entry) {
        JsonNode before = parse(entry.beforeJson());
        JsonNode after = parse(entry.afterJson());
        if (before != null && after != null && before.isObject() && after.isObject()) {
            Set<String> fieldNames = new LinkedHashSet<>();
            before.fieldNames().forEachRemaining(fieldNames::add);
            after.fieldNames().forEachRemaining(fieldNames::add);
            List<String> changedFields = new ArrayList<>();
            for (Iterator<String> names = fieldNames.iterator(); names.hasNext();) {
                String fieldName = names.next();
                if (!Objects.equals(before.get(fieldName), after.get(fieldName))) changedFields.add(fieldName);
            }
            if (!changedFields.isEmpty()) return "changed: " + String.join(", ", changedFields);
        }
        if (!AuditOutcome.SUCCESS.name().equals(entry.outcome()) && entry.reason() != null) return entry.reason();
        return null;
    }

    private JsonNode parse(String json) {
        if (json == null) return null;
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A stored audit entry is not valid JSON", e);
        }
    }

    /** One CSV line: every cell quoted, quotes doubled, a cell that a spreadsheet would run as a formula prefixed with {@code '}. */
    private static void appendCsvLine(StringBuilder csv, List<String> cells) {
        List<String> quotedCells = new ArrayList<>();
        for (String cell : cells) {
            String value = cell == null ? "" : cell;
            if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) value = "'" + value;
            quotedCells.add("\"" + value.replace("\"", "\"\"") + "\"");
        }
        csv.append(String.join(",", quotedCells)).append("\r\n");
    }

    private static String text(Map<String, String> parameters, String name) {
        String value = parameters.get(name);
        if (value == null || value.isBlank()) return null;
        String trimmedValue = value.trim();
        return trimmedValue.length() > MAXIMUM_FILTER_LENGTH ? trimmedValue.substring(0, MAXIMUM_FILTER_LENGTH) : trimmedValue;
    }

    private static UUID uuid(InputValidator validator, String field, String value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            validator.reject(field, "The " + field + " is not a valid id.");
            return null;
        }
    }

    private static LocalDate date(InputValidator validator, String field, String value) {
        if (value == null) return null;
        Optional<LocalDate> date = BusinessCalendar.parseDateKey(value);
        if (date.isEmpty()) validator.reject(field, "Use the date format YYYY-MM-DD.");
        return date.orElse(null);
    }

    private static String enumValue(InputValidator validator, String field, String value, Enum<?>[] allowedValues) {
        if (value == null) return null;
        for (Enum<?> allowedValue : allowedValues) if (allowedValue.name().equalsIgnoreCase(value)) return allowedValue.name();
        List<String> names = new ArrayList<>();
        for (Enum<?> allowedValue : allowedValues) names.add(allowedValue.name());
        validator.reject(field, "The " + field + " is one of: " + String.join(", ", names) + ".");
        return null;
    }

    private static List<String> categoryNames() {
        List<String> names = new ArrayList<>();
        for (AuditCategory category : AuditCategory.values()) names.add(category.storedName());
        return names;
    }
}
