package com.jurislpo.juristrack.service;

import com.jurislpo.juristrack.engine.ReportingLine;
import com.jurislpo.juristrack.exception.ApiException;
import com.jurislpo.juristrack.repository.EmployeeQueryRepository;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Whom a manager may see (INTEGRATION.md section 3b): the people under them in the reporting line, read from the business's
 * {@code users.manager_id} and resolved by the {@link ReportingLine} engine. Depth 1 = direct reports (the default), depth 2 = also
 * the reports of the direct reports ("Include indirect reports"). The manager is never in their own scope. Deactivated users stay
 * in the reporting line (their history stays visible to their manager); callers filter by status where they need to.
 * Not wired to an endpoint yet: the Team tab (module 3) uses it.
 */
@Service
public class ReportingScopeService {

    /** The widest depth the API accepts today; ReportingLine itself takes any depth, so this can grow without a redesign. */
    public static final int MAXIMUM_DEPTH = ReportingLine.WITH_INDIRECT_REPORTS;

    private final EmployeeQueryRepository employeeQueryRepository;

    public ReportingScopeService(EmployeeQueryRepository employeeQueryRepository) {
        this.employeeQueryRepository = employeeQueryRepository;
    }

    /**
     * The ids of the users of {@code businessId} that {@code managerId} may see at {@code depth}; empty for someone without reports.
     *
     * @throws ApiException 400 when the depth is not 1 or 2
     */
    @Transactional(readOnly = true)
    public Set<UUID> visibleEmployeeIds(UUID businessId, UUID managerId, int depth) {
        if (depth < ReportingLine.DIRECT_REPORTS || depth > MAXIMUM_DEPTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The depth is 1 (direct reports) or 2 (also indirect reports).");
        }
        return new ReportingLine(employeeQueryRepository.findManagerIdsByEmployeeId(businessId)).visibleEmployeeIds(managerId, depth);
    }
}
